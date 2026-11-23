/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import org.microg.gms.people.sync.SyncMode

/**
 * Android's LOCAL upload sync: SyncManager queues one about 30 s after any contacts write made without
 * CALLER_IS_SYNCADAPTER (ContactsProvider2 notifyChange(syncToNetwork)). Such a sync only has to send this account's
 * local changes, so it ends without touching the provider when nothing waits to upload. Each one also holds the
 * account's next automatic sync for [COOLDOWN_MILLIS] (SyncResult.delayUntil); a repeat that still arrives inside that
 * window does no work, and asks for a full sync when the window ends if changes wait (CYC-2304: a pass that wrote and
 * queued the next LOCAL sync ran every 60-120 s around the clock). Manual syncs ("Sync now") ignore the hold.
 */
class LocalUploadSync(private val provider: ContentProviderClient, private val account: Account,
    private val prefs: ContactSyncPreferences) {
    enum class Action { RUN, NOTHING_TO_UPLOAD, DEFER }
    /** [holdUntilMillis]: Android runs no automatic sync of the account before then. [syncWhenHoldEnds]: one full sync then. */
    data class Decision(val action: Action, val holdUntilMillis: Long, val syncWhenHoldEnds: Boolean = false)

    fun decide(now: Long = System.currentTimeMillis(), deviceContactsToMove: () -> Boolean = { false }): Decision {
        val last = prefs.lastLocalUploadSync
        // A clock set back leaves `last` in the future; count that as elapsed rather than holding uploads for the offset.
        if (last in (now - COOLDOWN_MILLIS + 1)..now) {
            return Decision(Action.DEFER, last + COOLDOWN_MILLIS, syncWhenHoldEnds = uploads() && hasLocalChanges(deviceContactsToMove))
        }
        if (!uploads() || !hasLocalChanges(deviceContactsToMove)) {
            return Decision(Action.NOTHING_TO_UPLOAD, 0L)
        }
        prefs.lastLocalUploadSync = now
        return Decision(Action.RUN, now + COOLDOWN_MILLIS)
    }

    /** Download-only accounts never upload; their changes come with the periodic and manual syncs. */
    private fun uploads() = prefs.mode == SyncMode.TWO_WAY

    private fun hasLocalChanges(deviceContactsToMove: () -> Boolean) = hasLocalChanges() || deviceContactsToMove()

    /** Contacts or labels of this account that are dirty or hold an unfinished upload journal (SYNC3). */
    fun hasLocalChanges(): Boolean = any(RawContacts.CONTENT_URI, "${RawContacts.DIRTY}!=0 OR ${RawContacts.SYNC3} IS NOT NULL") ||
        any(Groups.CONTENT_URI, "${Groups.DIRTY}!=0 OR ${Groups.SYNC3} IS NOT NULL")

    private fun any(uri: Uri, changed: String): Boolean = provider.query(uri, arrayOf("_id"),
        "account_name=? AND account_type=? AND data_set IS NULL AND ($changed)", arrayOf(account.name, account.type), null)
        ?.use { it.moveToFirst() } ?: true

    companion object {
        const val COOLDOWN_MILLIS = 15 * 60 * 1000L
        /** SyncManager.scheduleLocalSync sets only SYNC_EXTRAS_UPLOAD; a manual sync is the owner's and always runs. */
        fun isLocalUpload(extras: Bundle): Boolean = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD) &&
            !extras.getBoolean(ContentResolver.SYNC_EXTRAS_MANUAL)
    }
}
