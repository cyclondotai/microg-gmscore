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
    private const val AUTHORITY_NAME = "ai.cyclon.migration.restore"
    private const val COMPANION_PACKAGE = "ai.cyclon.migration"
    private val AUTHORITY: Uri = Uri.parse("content://$AUTHORITY_NAME")
    const val METHOD_RESTORE_PACKAGE = "restorePackage"

    data class Result(val ok: Boolean, val status: String)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "migration-app-data-restore").apply { isDaemon = true }
    }

    fun triggerAsync(context: Context, packageName: String, sessionId: String? = null) {
        val appContext = context.applicationContext ?: context
        if (!hasTrustedProvider(appContext)) return
        executor.execute {
            runCatching { restorePackage(appContext, packageName, sessionId) }
                .onFailure { Log.w(TAG, "Post-install app-data restore failed for $packageName", it) }
        }
    }

    internal fun hasTrustedProvider(context: Context): Boolean {
        val provider = runCatching {
            context.packageManager.resolveContentProvider(AUTHORITY_NAME, 0)
        }.getOrNull() ?: return false
        val flags = provider.applicationInfo?.flags ?: 0
        return provider.packageName == COMPANION_PACKAGE &&
            (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }

    internal fun restorePackage(context: Context, packageName: String, sessionId: String? = null): Result {
        if (!hasTrustedProvider(context)) {
            Log.d(TAG, "RestoreTransportProvider absent or untrusted; skipping app-data restore for $packageName")
            return Result(ok = false, status = "unsupported")
        }
        val identity = Binder.clearCallingIdentity()
        return try {
            val extras = Bundle().apply {
                if (!sessionId.isNullOrEmpty()) putString("sessionId", sessionId)
            }
            val reply = context.contentResolver.call(AUTHORITY, METHOD_RESTORE_PACKAGE, packageName, extras)
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
