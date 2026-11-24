/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings

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

    /** Allocates and persists a monotonic staged batch generation with its original staging timestamp. */
    fun publishNewBatch(
        stagedBootCount: Int = currentBootCount(context),
        stagedElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            val nextSeq = prefs.getLong(KEY_BATCH_SEQ, 0L) + 1L
            check(
                prefs.edit()
                    .putLong(KEY_BATCH_SEQ, nextSeq)
                    .putInt(KEY_BATCH_STAGED_BOOT, stagedBootCount)
                    .putLong(KEY_BATCH_STAGED_ELAPSED, stagedElapsedMs)
                    .putBoolean(KEY_BATCH_NOTIFIED, false)
                    .commit(),
            )
        }
        return ensureBatchNotified()
    }

    /** Retries publication of the persisted staged batch generation until Transfer Phone acknowledges it. */
    fun ensureBatchNotified(): Boolean {
        val (seq, stagedBoot, stagedElapsed) = synchronized(BATCH_LOCK) {
            val prefs = context.applicationContext.getSharedPreferences(BATCH_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_BATCH_NOTIFIED, true)) return true
            val pendingSeq = prefs.getLong(KEY_BATCH_SEQ, 0L).takeIf { it > 0L } ?: return true
            Triple(
                pendingSeq,
                prefs.getInt(KEY_BATCH_STAGED_BOOT, -1),
                prefs.getLong(KEY_BATCH_STAGED_ELAPSED, -1L),
            )
        }
        if (!notifyBatchStarted(seq, stagedBoot, stagedElapsed)) return false
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
     * Returns `true` when Transfer Phone acknowledges (`success == true`), when no trusted companion provider
     * exists, or when an older companion returns a non-null `success == false` (unknown method, so Switch's
     * system restore never breaks on older images); returns `false` on a dropped/failed delivery (`null` reply
     * or exception) so the caller retries idempotently with the same monotonic [batchSeq] and original staging time.
     */
    fun notifyBatchStarted(
        batchSeq: Long = -1L,
        stagedBootCount: Int = -1,
        stagedElapsedMs: Long = -1L,
    ): Boolean {
        val identity = Binder.clearCallingIdentity()
        return try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY.authority!!, 0) ?: return true
            if (provider.packageName != "ai.cyclon.migration" ||
                (provider.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return true
            val arg = batchSeq.takeIf { it > 0L }?.toString()
            val extras = Bundle().apply {
                if (batchSeq > 0L) putLong(EXTRA_BATCH_SEQ, batchSeq)
                if (stagedElapsedMs >= 0L) {
                    putInt(EXTRA_BATCH_STAGED_BOOT, stagedBootCount)
                    putLong(EXTRA_BATCH_STAGED_ELAPSED, stagedElapsedMs)
                }
            }
            val reply = runCatching {
                context.contentResolver.call(AUTHORITY, "batchStarted", arg, extras)
            }.getOrNull() ?: return false
            // Existing companion builds that only know "acquire" reply Bundle(success=false) for unknown methods;
            // treat that explicit unsupported reply as settled so Switch's system restore keeps working on old images.
            reply.getBoolean("success", false) || !reply.containsKey("retryable")
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    companion object {
        private val AUTHORITY = Uri.parse("content://ai.cyclon.migration.restore")
        const val EXTRA_BATCH_SEQ = "batch_seq"
        const val EXTRA_BATCH_STAGED_BOOT = "batch_staged_boot"
        const val EXTRA_BATCH_STAGED_ELAPSED = "batch_staged_elapsed"
        private const val BATCH_PREFS = "migration_staging_batch"
        private const val KEY_BATCH_SEQ = "batch_seq"
        private const val KEY_BATCH_STAGED_BOOT = "batch_staged_boot"
        private const val KEY_BATCH_STAGED_ELAPSED = "batch_staged_elapsed"
        private const val KEY_BATCH_NOTIFIED = "batch_notified"
        private val BATCH_LOCK = Any()

        private fun currentBootCount(context: Context): Int =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)
    }
}
