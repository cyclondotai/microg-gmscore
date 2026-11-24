/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle

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

    /** Allocates and persists a monotonic staged batch generation and attempts immediate delivery. */
    fun publishNewBatch(): Boolean {
        synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            val nextSeq = prefs.getLong(KEY_BATCH_SEQ, 0L) + 1L
            check(
                prefs.edit()
                    .putLong(KEY_BATCH_SEQ, nextSeq)
                    .putBoolean(KEY_BATCH_NOTIFIED, false)
                    .commit(),
            )
        }
        return ensureBatchNotified()
    }

    /** Retries publication of the persisted staged batch generation until Transfer Phone acknowledges it. */
    fun ensureBatchNotified(): Boolean {
        val seq = synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_BATCH_NOTIFIED, true)) return true
            prefs.getLong(KEY_BATCH_SEQ, 0L).takeIf { it > 0L } ?: return true
        }
        if (!notifyBatchStarted(seq)) return false
        synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong(KEY_BATCH_SEQ, 0L) == seq) {
                check(prefs.edit().putBoolean(KEY_BATCH_NOTIFIED, true).commit())
            }
        }
        return true
    }

    /**
     * Notifies Transfer Phone that `migration-staging/` rotated to a new transfer batch (both Android D2D and iOS).
     * Returns `true` when Transfer Phone acknowledges (`success == true`) or when no trusted companion provider
     * exists on an older image; returns `false` on a dropped/failed delivery so the caller retries idempotently
     * with the same monotonic [batchSeq].
     */
    fun notifyBatchStarted(batchSeq: Long = -1L): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY.authority!!, 0) ?: return true
            if (provider.packageName != "ai.cyclon.migration" ||
                (provider.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return true
            val arg = batchSeq.takeIf { it > 0L }?.toString()
            val extras = batchSeq.takeIf { it > 0L }?.let { Bundle().apply { putLong(EXTRA_BATCH_SEQ, it) } }
            val reply = runCatching {
                context.contentResolver.call(AUTHORITY, "batchStarted", arg, extras)
            }.getOrNull()
            reply?.getBoolean("success", false) == true
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    companion object {
        private val AUTHORITY = Uri.parse("content://ai.cyclon.migration.restore")
        const val EXTRA_BATCH_SEQ = "batch_seq"
        private const val BATCH_PREFS = "migration_staging_batch"
        private const val KEY_BATCH_SEQ = "batch_seq"
        private const val KEY_BATCH_NOTIFIED = "batch_notified"
        private val BATCH_LOCK = Any()
    }
}
