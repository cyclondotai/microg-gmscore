/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log

/**
 * Core pushes the sealed baseline back through this provider after a factory reset (CYC-2935). It is one-way: no method
 * returns a stored token or ID. Android does not apply the manifest permission to [call], so it is checked here.
 */
class FactoryBaselineProvider : ContentProvider() {
    internal var host: (Context) -> FactoryBaselineHost = ::AndroidFactoryBaselineHost

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = requireNotNull(context)
        requireCore(app)
        if (arg != null || method !in METHODS) throw SecurityException("Unsupported method")
        val identity = Binder.clearCallingIdentity()
        try {
            return if (method == "status") status(app) else restore(app, extras)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun requireCore(app: Context) {
        app.enforceCallingPermission(PERMISSION, "Core only")
        val uid = Binder.getCallingUid()
        val core = try {
            app.packageManager.getApplicationInfo(FactoryBaseline.CORE_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            throw SecurityException("Core absent")
        }
        if (uid != core.uid || core.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
            app.packageManager.getPackagesForUid(uid)?.toList() != listOf(FactoryBaseline.CORE_PACKAGE)) {
            throw SecurityException("Core only")
        }
    }

    /** A failed read answers without `restoreContract`, which Core treats as deferred. */
    private fun status(app: Context): Bundle = try {
        val state = FactoryBaseline.state(host(app))
        Bundle().apply {
            putInt("restoreContract", 1)
            putString("state", state)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Status unavailable: ${e.javaClass.simpleName}")
        Bundle()
    }

    private fun restore(app: Context, extras: Bundle?): Bundle {
        val payload = extras?.getByteArray("microg")
        val outcome = try {
            if (payload == null || extras.getInt("schema") != 1 || extras.getString("reason") !in REASONS) {
                FactoryBaseline.INVALID_BASELINE
            } else {
                FactoryBaseline.restore(host(app), payload)
            }
        } catch (e: Exception) {
            // The class name only: a failed settings write puts the values it tried to store in its message.
            Log.w(TAG, "Restore deferred: ${e.javaClass.simpleName}")
            FactoryBaseline.BUSY
        } finally {
            payload?.fill(0)
        }
        return Bundle().apply { putString("outcome", outcome) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
        throw SecurityException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw SecurityException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw SecurityException()

    companion object {
        private const val TAG = "CyclonFactoryBaseline"
        const val AUTHORITY = "com.google.android.gms.cyclon.factory_baseline"
        const val PERMISSION = "ai.cyclon.microg.permission.ENROLLMENT_CHALLENGE"
        private val METHODS = setOf("status", "restore")
        private val REASONS = setOf("just_reset", "target_unprovisioned")
    }
}
