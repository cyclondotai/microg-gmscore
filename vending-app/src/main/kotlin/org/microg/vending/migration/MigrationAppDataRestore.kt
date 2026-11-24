/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import java.util.concurrent.Executors

/**
 * Triggers single-package third-party app-data restore via Transfer Phone's
 * [RestoreTransportProvider] (`content://ai.cyclon.migration.restore`) after a migrated
 * app finishes installing. Failures or older images without the provider/method are logged
 * and ignored so installs never fail.
 */
internal object MigrationAppDataRestore {
    private const val TAG = "MigrationAppDataRestore"
    private const val PACKAGE_AUTHORITY_NAME = "ai.cyclon.migration.restore.package"
    private const val LEGACY_AUTHORITY_NAME = "ai.cyclon.migration.restore"
    private const val COMPANION_PACKAGE = "ai.cyclon.migration"
    const val METHOD_RESTORE_PACKAGE = "restorePackage"
    const val METHOD_REGISTER_SESSION = "registerSession"
    const val EXTRA_TRANSFER_ID = "transfer_id"
    const val EXTRA_REQUIRE_REGISTERED = "require_registered"

    enum class RegistrationStatus { REGISTERED, UNSUPPORTED, FAILED }

    data class Result(val ok: Boolean, val status: String)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "migration-app-data-restore").apply { isDaemon = true }
    }

    fun triggerAsync(
        context: Context,
        packageName: String,
        sessionId: String? = null,
        d2dSeenBootCount: Int = -1,
        d2dSeenElapsedMs: Long = -1L,
        ensureRegistered: (() -> String?)? = null,
        transferId: String? = null,
        shouldRun: () -> Boolean = { true },
        onResult: ((Result) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext ?: context
        if (resolveTrustedAuthority(appContext) == null) {
            onResult?.invoke(Result(ok = false, status = "unsupported"))
            return
        }
        executor.execute {
            if (!shouldRun()) {
                onResult?.invoke(Result(ok = false, status = "stale_session"))
                return@execute
            }
            val protocol = if (ensureRegistered != null) {
                ensureRegistered.invoke() ?: run {
                    onResult?.invoke(Result(ok = false, status = "error"))
                    return@execute
                }
            } else null
            val requireReg = protocol == "v2"
            val result = runCatching {
                restorePackage(
                    appContext,
                    packageName,
                    sessionId,
                    d2dSeenBootCount,
                    d2dSeenElapsedMs,
                    transferId = if (requireReg) transferId else null,
                    requireRegistered = requireReg,
                )
            }
                .onFailure { Log.w(TAG, "Post-install app-data restore failed for $packageName", it) }
                .getOrElse { Result(ok = false, status = "error") }
            onResult?.invoke(result)
        }
    }

    internal fun hasTrustedProvider(context: Context): Boolean = resolveTrustedAuthority(context) != null

    /**
     * Explicitly binds a new Android D2D FakeStore session to Transfer Phone's staging batch
     * using the shared Switch `transferId`. Distinguishes acknowledged v2 registration, legacy
     * unsupported companions, and transient failures (which callers retry instead of falling back).
     */
    internal fun registerSession(context: Context, sessionId: String, transferId: String): RegistrationStatus {
        val authorityName = resolveTrustedAuthority(context) ?: return RegistrationStatus.UNSUPPORTED
        val identity = Binder.clearCallingIdentity()
        return try {
            val extras = Bundle().apply {
                putString("sessionId", sessionId)
                putString(EXTRA_TRANSFER_ID, transferId)
            }
            val reply = context.contentResolver.call(
                Uri.parse("content://$authorityName"),
                METHOD_REGISTER_SESSION,
                sessionId,
                extras,
            ) ?: return RegistrationStatus.UNSUPPORTED
            when {
                reply.getBoolean("ok", false) -> RegistrationStatus.REGISTERED
                !reply.containsKey("ok") && !reply.getBoolean("supported", false) -> RegistrationStatus.UNSUPPORTED
                else -> RegistrationStatus.FAILED
            }
        } catch (e: SecurityException) {
            if (e.message?.contains("Unsupported", ignoreCase = true) == true) {
                RegistrationStatus.UNSUPPORTED
            } else {
                Log.w(TAG, "registerSession security failure for $sessionId", e)
                RegistrationStatus.FAILED
            }
        } catch (t: Throwable) {
            Log.w(TAG, "registerSession transient failure for $sessionId: ${t.javaClass.simpleName}", t)
            RegistrationStatus.FAILED
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    internal fun resolveTrustedAuthority(context: Context): String? {
        for (authority in listOf(PACKAGE_AUTHORITY_NAME, LEGACY_AUTHORITY_NAME)) {
            val provider = runCatching {
                context.packageManager.resolveContentProvider(authority, 0)
            }.getOrNull() ?: continue
            val flags = provider.applicationInfo?.flags ?: 0
            if (provider.packageName == COMPANION_PACKAGE &&
                (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
            ) {
                return authority
            }
        }
        return null
    }

    internal fun restorePackage(
        context: Context,
        packageName: String,
        sessionId: String? = null,
        d2dSeenBootCount: Int = -1,
        d2dSeenElapsedMs: Long = -1L,
        transferId: String? = null,
        requireRegistered: Boolean = false,
    ): Result {
        val authorityName = resolveTrustedAuthority(context)
        if (authorityName == null) {
            Log.d(TAG, "RestoreTransportProvider absent or untrusted; skipping app-data restore for $packageName")
            return Result(ok = false, status = "unsupported")
        }
        val identity = Binder.clearCallingIdentity()
        return try {
            val extras = Bundle().apply {
                if (!sessionId.isNullOrEmpty()) putString("sessionId", sessionId)
                if (d2dSeenElapsedMs >= 0L) {
                    putInt("d2d_seen_boot_count", d2dSeenBootCount)
                    putLong("d2d_seen_elapsed_ms", d2dSeenElapsedMs)
                }
                if (!transferId.isNullOrBlank()) putString(EXTRA_TRANSFER_ID, transferId)
                if (requireRegistered) putBoolean(EXTRA_REQUIRE_REGISTERED, true)
            }
            val reply = context.contentResolver.call(
                Uri.parse("content://$authorityName"),
                METHOD_RESTORE_PACKAGE,
                packageName,
                extras,
            )
            if (reply == null) {
                Log.d(TAG, "RestoreTransportProvider returned null for $packageName")
                return Result(ok = false, status = "unsupported")
            }
            val ok = reply.getBoolean("ok", false)
            val status = reply.getString("status") ?: if (ok) "restored" else "unsupported"
            Log.i(TAG, "Post-install app-data restore for $packageName: ok=$ok status=$status")
            Result(ok = ok, status = status)
        } catch (t: Throwable) {
            Log.w(TAG, "Post-install app-data restore unavailable for $packageName: ${t.javaClass.simpleName}", t)
            Result(ok = false, status = "error")
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }
}
