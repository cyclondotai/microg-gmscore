/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cyclon

import android.accounts.AccountManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import org.microg.gms.auth.AuthManager
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** Pure URL policy for Core's one-time Google weblogin MergeSession bridge (CYC-2850). */
internal object WebLoginUrlPolicy {
    const val DEFAULT_CONTINUE_URL = "https://accounts.google.com"
    private const val MAX_CONTINUE_LENGTH = 2048
    private const val MAX_MERGE_URL_LENGTH = 8192
    private val ALLOWED_CONTINUE_HOSTS = setOf(
        "accounts.google.com",
        "myaccount.google.com",
        "www.google.com",
        "mail.google.com",
        "calendar.google.com",
    )

    fun validateContinueUrl(raw: String?): String? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return DEFAULT_CONTINUE_URL
        if (trimmed.length > MAX_CONTINUE_LENGTH) return null
        val uri = try { URI(trimmed) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (host !in ALLOWED_CONTINUE_HOSTS) return null
        return trimmed
    }

    fun isValidMergeSessionUrl(raw: String?): Boolean {
        val trimmed = raw?.trim() ?: return false
        if (trimmed.isEmpty() || trimmed.length > MAX_MERGE_URL_LENGTH) return false
        if (trimmed.contains("WILL_NOT_SIGN_IN")) return false
        val uri = try { URI(trimmed) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        if (host != "accounts.google.com") return false
        val path = uri.path ?: return false
        return path.startsWith("/MergeSession", ignoreCase = true)
    }
}

/**
 * Core-only Binder bridge that mints a one-time Google `weblogin:continue=...` (`MergeSession`) URL for an enrolled
 * owner Google account (CYC-2850), so Cyclon Core can bootstrap Google session cookies into the fallback browser
 * without ever exposing `aas_et` or the `uberauth` URL to Node, models, or logs.
 *
 * Caller authentication matches [org.microg.gms.passwords.PasswordAgentFillProvider]: exact system `ai.cyclon.core`
 * UID with no shared UID. Cyclon's hidden device account ([DeviceAccount]) is always excluded.
 */
class WebLoginProvider : ContentProvider() {
    /** Overridable in Robolectric unit tests to avoid real network AuthManager calls. */
    internal var authRequester: (Context, String, String) -> String? = { ctx, accountName, service ->
        AuthManager(ctx, accountName, ctx.packageName, service).requestAuth(false)?.auth
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Build.VERSION.SDK_INT < 28) throw SecurityException("Unsupported SDK")
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val core = try {
            app.packageManager.getApplicationInfo(CORE, 0)
        } catch (_: Exception) {
            throw SecurityException("Core absent")
        }
        val isSystem = core.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (uid != core.uid || !isSystem || app.packageManager.getPackagesForUid(uid)?.toSet() != setOf(CORE)) {
            throw SecurityException("Core only")
        }
        if (method != METHOD_MERGE_SESSION_URL || arg != null) {
            throw SecurityException("Unsupported method")
        }
        val continueUrl = WebLoginUrlPolicy.validateContinueUrl(extras?.getString(KEY_CONTINUE_URL))
            ?: return statusBundle(STATUS_INVALID_CONTINUE_URL)
        val requestedAccount = extras?.getString(KEY_ACCOUNT)?.trim()?.takeIf { it.isNotEmpty() }

        val identity = Binder.clearCallingIdentity()
        return try {
            // Never sign the owner's browser into Cyclon's hidden device account, even if debug show_device_account is set.
            val ownerAccounts = AccountManager.get(app)
                .getAccountsByType(GOOGLE_ACCOUNT)
                .filterNot { DeviceAccount.isDevice(app, it) }
            val chosen = if (requestedAccount != null) {
                ownerAccounts.firstOrNull { it.name.equals(requestedAccount, ignoreCase = true) }
            } else {
                ownerAccounts.firstOrNull()
            }
            if (chosen == null) {
                return statusBundle(STATUS_NO_ACCOUNT)
            }
            val service = "weblogin:continue=" + URLEncoder.encode(continueUrl, "UTF-8")
            val authUrl = authRequester(app, chosen.name, service)
            when {
                authUrl.isNullOrBlank() -> statusBundle(STATUS_TOKEN_FAILED)
                authUrl.contains("WILL_NOT_SIGN_IN") -> statusBundle(STATUS_WILL_NOT_SIGN_IN)
                !WebLoginUrlPolicy.isValidMergeSessionUrl(authUrl) -> statusBundle(STATUS_INVALID_URL)
                else -> statusBundle(STATUS_OK).apply { putString(KEY_URL, authUrl) }
            }
        } catch (e: Exception) {
            // Never log account names, master tokens, or MergeSession/uberauth URLs.
            Log.w(TAG, "Weblogin merge session failed: ${e.javaClass.simpleName}")
            statusBundle(STATUS_ERROR).apply { putString(KEY_ERROR, e.javaClass.simpleName) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun statusBundle(status: String) = Bundle().apply {
        putInt(KEY_VERSION, VERSION)
        putString(KEY_STATUS, status)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = throw SecurityException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw SecurityException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw SecurityException()

    companion object {
        private const val TAG = "CyclonWebLoginProvider"
        private const val CORE = "ai.cyclon.core"
        private const val GOOGLE_ACCOUNT = "com.google"

        const val AUTHORITY = "com.google.android.gms.cyclon.weblogin"
        const val METHOD_MERGE_SESSION_URL = "merge_session_url"
        const val KEY_VERSION = "version"
        const val KEY_STATUS = "status"
        const val KEY_URL = "url"
        const val KEY_ERROR = "error"
        const val KEY_ACCOUNT = "account"
        const val KEY_CONTINUE_URL = "continue_url"

        const val STATUS_OK = "ok"
        const val STATUS_NO_ACCOUNT = "no_account"
        const val STATUS_INVALID_CONTINUE_URL = "invalid_continue_url"
        const val STATUS_TOKEN_FAILED = "token_failed"
        const val STATUS_WILL_NOT_SIGN_IN = "will_not_sign_in"
        const val STATUS_INVALID_URL = "invalid_url"
        const val STATUS_ERROR = "error"

        const val VERSION = 1
    }
}
