/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Binder
import android.os.Bundle
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

    /** Notifies Transfer Phone that `migration-staging/` rotated to a new transfer batch (both Android D2D and iOS). */
    fun notifyBatchStarted(transferId: String? = null) {
        val identity = Binder.clearCallingIdentity()
        try {
            val provider = context.packageManager.resolveContentProvider(AUTHORITY.authority!!, 0) ?: return
            if (provider.packageName != "ai.cyclon.migration" ||
                (provider.applicationInfo?.flags ?: 0) and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return
            val extras = transferId?.takeIf { it.isNotBlank() }?.let { Bundle().apply { putString(EXTRA_TRANSFER_ID, it) } }
            runCatching { context.contentResolver.call(AUTHORITY, "batchStarted", null, extras) }
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    companion object {
        private val AUTHORITY = Uri.parse("content://ai.cyclon.migration.restore")
        const val EXTRA_TRANSFER_ID = "transfer_id"
        private const val SWITCH_GENERATION_KEY = "usb_migration_expected_version"

        internal fun currentTransferId(context: Context, callingPid: Int): String {
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)
            val gen = runCatching { Settings.Secure.getLong(context.contentResolver, SWITCH_GENERATION_KEY, 0L) }.getOrDefault(0L)
            return "boot=$boot:gen=$gen:pid=$callingPid"
        }
    }
}
