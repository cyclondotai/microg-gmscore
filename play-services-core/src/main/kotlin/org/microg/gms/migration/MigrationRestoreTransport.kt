/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder

/** Transfer Phone owns the durable lease on the owner's selected backup transport. */
internal class MigrationRestoreTransport(private val context: Context) {
    fun acquire() {
        val identity = Binder.clearCallingIdentity()
        try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY.authority!!, 0) ?: return
            if (provider.packageName != "ai.cyclon.migration" ||
                (provider.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return
            val result = context.contentResolver.call(AUTHORITY, "acquire", null, null)
            check(result?.getBoolean("success") == true) { "Migration restore transport acquire failed" }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    /** Persists a new staged batch identity as pending publication and attempts immediate delivery. */
    fun publishNewBatch(batchId: String): Boolean {
        synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            check(
                prefs.edit()
                    .putString(KEY_BATCH_ID, batchId)
                    .putBoolean(KEY_BATCH_NOTIFIED, false)
                    .commit(),
            )
        }
        return ensureBatchNotified()
    }

    /** Retries publication of the persisted staged batch identity until Transfer Phone acknowledges it. */
    fun ensureBatchNotified(): Boolean {
        val id = synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_BATCH_NOTIFIED, true)) return true
            prefs.getString(KEY_BATCH_ID, null)?.takeIf { it.isNotBlank() } ?: return true
        }
        if (!notifyBatchStarted(id)) return false
        synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_BATCH_ID, null) == id) {
                check(prefs.edit().putBoolean(KEY_BATCH_NOTIFIED, true).commit())
            }
        }
        return true
    }

    /**
     * Notifies Transfer Phone that `migration-staging/` rotated to a new transfer batch (both Android D2D and iOS).
     * Returns `true` when Transfer Phone acknowledges (`success == true`) or when no trusted companion provider
     * exists on an older image; returns `false` on a dropped/failed delivery so the caller retries idempotently
     * with the same [batchId].
     */
    fun notifyBatchStarted(batchId: String? = null): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY.authority!!, 0) ?: return true
            if (provider.packageName != "ai.cyclon.migration" ||
                (provider.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return true
            val reply = runCatching {
                context.contentResolver.call(AUTHORITY, "batchStarted", batchId, null)
            }.getOrNull()
            reply?.getBoolean("success", false) == true
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    companion object {
        private val AUTHORITY = Uri.parse("content://ai.cyclon.migration.restore")
        private const val BATCH_PREFS = "migration_staging_batch"
        private const val KEY_BATCH_ID = "batch_id"
        private const val KEY_BATCH_NOTIFIED = "batch_notified"
        private val BATCH_LOCK = Any()
    }
}
