/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.people

import android.accounts.Account
import android.app.Application
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentValues
import android.content.SyncResult
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.people.sync.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * CYC-2304: each pass wrote to the provider in a way that queued the next LOCAL upload sync, so the adapter ran every
 * 60-120 s around the clock. A pass must not request another sync, and Android's LOCAL upload syncs must stay cheap and
 * rare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ContactsLocalSyncLoopTest {
    private val account = Account("loop-fixture@invalid", "com.google")
    private val binding = ContactSyncService.privatePeopleContext.binding
    private val label = ContactGroup.parse(JSONObject().put("resourceName", PrivatePeopleApi.groupResource("family"))
        .put("name", "Family").put("etag", "g1").put("groupType", "USER_CONTACT_GROUP"))

    private fun person(id: String = "17", phone: String = "123", etag: String = "e1"): Person = Person.parse(
        JSONObject().put("resourceName", "people/fsa_$id").put("metadata", JSONObject().put("sources", JSONArray()
            .put(JSONObject().put("type", "CONTACT").put("id", id).put("etag", etag))))
            .put("names", JSONArray().put(JSONObject().put("givenName", "Synthetic")))
            .put("phoneNumbers", JSONArray().put(JSONObject().put("value", phone).put("type", "mobile"))))

    /** Like Google's API, a sync token returns only what changed since it was handed out. */
    private class Api : ContactsApi, GroupsApi {
        val remote = linkedMapOf<String, Person>()
        val labels = linkedMapOf<String, ContactGroup>()
        var creates = 0; var updates = 0
        private val sent = mutableSetOf<String>()
        private fun <T> changed(token: String?, items: Collection<T>, key: (T) -> String) =
            items.filter { token == null || key(it) !in sent }.also { list -> list.forEach { sent.add(key(it)) } }
        override fun list(syncToken: String?, pageToken: String?) = Page(changed(syncToken, remote.values) { it.json.toString() }, null, "cursor")
        override fun get(resource: String) = remote[resource]
        override fun create(body: JSONObject): Person {
            creates++
            val json = JSONObject(body.toString()).put("resourceName", "people/fsa_a$creates").put("metadata", JSONObject()
                .put("sources", JSONArray().put(JSONObject().put("type", "CONTACT").put("id", "a$creates").put("etag", "created"))))
            return Person.parse(json).also { remote[it.resource] = it }
        }
        override fun update(person: Person, fields: Set<String>, body: JSONObject): Person {
            updates++
            val json = JSONObject(remote.getValue(person.resource).json.toString())
            for (field in fields) json.put(field, body.getJSONArray(field))
            json.getJSONObject("metadata").getJSONArray("sources").getJSONObject(0).put("etag", "updated-$updates")
            return Person.parse(json).also { remote[it.resource] = it }
        }
        override fun delete(resource: String) { remote.remove(resource) }
        override fun listGroups(syncToken: String?, pageToken: String?) = GroupPage(changed(syncToken, labels.values) { it.json.toString() }, null, "labels")
        override fun getGroup(resource: String) = labels[resource]
        override fun createGroup(name: String, creationId: String): ContactGroup = error("No label uploads in this fixture")
        override fun updateGroup(group: ContactGroup, name: String): ContactGroup = error("No label uploads in this fixture")
        override fun deleteGroup(resource: String) = error("No label uploads in this fixture")
    }

    private inner class Fixture(val provider: ImportContactsProvider, val client: ContentProviderClient, val prefs: ContactSyncPreferences) {
        val api = Api()
        val context: android.content.Context get() = RuntimeEnvironment.getApplication()
        val gate get() = LocalUploadSync(client, account, prefs)
        fun store(groups: AndroidGroupStore? = null) = AndroidContactStore(client, account, prefs, groups, api, PrivatePeopleApi.OWNER, binding, deferCheckpoint = true)
        /** The provider work of one ContactSyncService pass, in its order, against a fake backend. */
        fun pass(): SyncReport {
            check(store().preparePrivateSync())
            prefs.initializePrivateFloors(0, 0)
            val groups = AndroidGroupStore(client, account, prefs, PrivatePeopleApi.OWNER, binding, deferCheckpoint = true)
            groups.initialize()
            val groupReport = GroupsSync(api, groups) { prefs.mode }.sync()
            val store = store(groups)
            store.configureMode()
            store.clearUnchangedDirty()
            val report = groupReport + ContactsSync(api, store) { prefs.mode }.sync()
            store.commitCheckpoint(); groups.commitCheckpoint()
            return report
        }
        fun ownedId(): Long = store().contacts().single().id
        fun raw(id: Long, column: String): String? = provider.query(RawContacts.CONTENT_URI, arrayOf(column), "_id=?", arrayOf(id.toString()), null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        fun settings(): Pair<Int, Int>? = provider.query(ContactsContract.Settings.CONTENT_URI, arrayOf("should_sync", "ungrouped_visible"),
            "account_name=? AND account_type=?", arrayOf(account.name, account.type), null).use {
            if (it.moveToFirst()) it.getInt(0) to it.getInt(1) else null
        }
        /** The owner (or an app) edits a number, as the Contacts app does: a normal write. */
        fun editPhone(id: Long, number: String) = provider.update(Data.CONTENT_URI, ContentValues().apply { put(Phone.NUMBER, number) },
            "${Data.RAW_CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(id.toString(), Phone.CONTENT_ITEM_TYPE))
        fun adapter(): AbstractThreadedSyncAdapter {
            val service = Robolectric.buildService(ContactSyncService::class.java).create().get()
            return ContactSyncService::class.java.getDeclaredMethod("getAdapter").apply { isAccessible = true }.invoke(service) as AbstractThreadedSyncAdapter
        }
        fun localUploadSync(): SyncResult = SyncResult().also {
            adapter().onPerformSync(account, Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true) }, ContactsContract.AUTHORITY, client, it)
        }
    }

    private fun fixture(mode: SyncMode = SyncMode.TWO_WAY, block: Fixture.() -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("contacts-sync", 0).edit().clear().commit()
        context.getSharedPreferences("contacts-migration-privacy", 0).edit().clear().commit()
        val provider = ImportContactsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = ContactsContract.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
        val prefs = ContactSyncPreferences(context, account)
        prefs.configure(mode, 0)
        context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!.use {
            try { Fixture(provider, it, prefs).block() } finally { provider.shutdown() }
        }
    }

    @Test fun aFinishedSyncQueuesNoLocalUploadSyncAndLeavesNothingDirty() = fixture {
        api.remote[person().resource] = person(); api.labels[label.resource] = label
        assertTrue(pass().downloaded >= 1)
        val id = ownedId()
        // Every write of the pass went in as the sync adapter: ContactsProvider2 queues no LOCAL sync after it.
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.uploadSyncRequests)
        assertTrue(provider.writes.isNotEmpty())
        assertFalse(gate.hasLocalChanges()); assertEquals("0", raw(id, RawContacts.DIRTY))

        // A repeat pass with nothing new on either side writes nothing to contacts, labels or settings.
        provider.writes.clear()
        pass()
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.writes.filter { it.table != "syncstate" })

        // An owner edit requests an upload sync; the pass that uploads it requests none and leaves the row clean.
        editPhone(id, "456")
        assertEquals(listOf(ImportContactsProvider.Write("data", false)), provider.uploadSyncRequests.takeLast(1))
        assertTrue(gate.hasLocalChanges())
        provider.writes.clear()
        assertEquals(1, pass().uploaded); assertEquals(1, api.updates)
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.uploadSyncRequests)
        assertEquals("0", raw(id, RawContacts.DIRTY)); assertFalse(gate.hasLocalChanges())
        // The LOCAL sync Android queued for that edit, arriving after the pass, finds nothing to send.
        assertEquals(LocalUploadSync.Action.NOTHING_TO_UPLOAD, gate.decide().action)
    }

    @Test fun configureModeWritesSettingsOnlyWhenAValueChanges() = fixture {
        val store = store()
        store.configureMode()
        assertEquals(1 to 1, settings())
        assertTrue(provider.writes.isNotEmpty()); assertTrue(provider.writes.all { it.syncAdapter })

        provider.writes.clear()
        repeat(3) { store.configureMode() }
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.writes)

        // A changed value is put back once, as the sync adapter.
        provider.update(ContactsContract.Settings.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            ContentValues().apply { put("ungrouped_visible", 0) }, null, null)
        provider.writes.clear()
        store.configureMode()
        assertEquals(1 to 1, settings())
        assertEquals(listOf(ImportContactsProvider.Write("settings", true)), provider.writes)

        // A mode change still reaches the synced rows, once.
        api.remote[person().resource] = person(); pass()
        prefs.configure(SyncMode.DOWNLOAD, 0)
        provider.writes.clear()
        store.configureMode()
        assertEquals("1", raw(ownedId(), RawContacts.RAW_CONTACT_IS_READ_ONLY))
        assertEquals(listOf(ImportContactsProvider.Write("raw_contacts", true)), provider.writes)
        provider.writes.clear()
        store.configureMode()
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.writes)
    }

    @Test fun movedDeviceContactsStayDirtyAndUploadOnce() = fixture {
        val device = provider.addRaw(Account("DEVICE", "com.android.contacts"), dirty = false)
        provider.addFields(device, person(phone = "555"))
        provider.writes.clear()

        assertEquals(DeviceContacts.MoveResult(1, 0), DeviceContacts(client, context).moveTo(account, listOf(device)))
        // A normal write, not a sync-adapter one: Android queues the upload and the copy waits DIRTY for it.
        assertTrue(provider.writes.isNotEmpty()); assertTrue(provider.writes.none { it.syncAdapter })
        val moved = provider.query(RawContacts.CONTENT_URI, arrayOf("_id"), "account_name=? AND account_type=?",
            arrayOf(account.name, account.type), null).use { assertTrue(it.moveToFirst()); it.getLong(0) }
        assertEquals("1", raw(moved, RawContacts.DIRTY)); assertNull(raw(moved, RawContacts.SYNC4))
        assertTrue(gate.hasLocalChanges())
        assertEquals(LocalUploadSync.Action.RUN, gate.decide().action)

        provider.writes.clear()
        pass()
        assertEquals(1, api.creates); assertEquals(moved, ownedId()); assertEquals("0", raw(moved, RawContacts.DIRTY))
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.uploadSyncRequests)
    }

    @Test fun repeatLocalUploadSyncsWaitAtLeastFifteenMinutes() = fixture {
        val cooldown = LocalUploadSync.COOLDOWN_MILLIS
        assertTrue(cooldown >= 15 * 60 * 1000L)
        api.remote[person().resource] = person(); pass()
        editPhone(ownedId(), "456")
        val start = 1_800_000_000_000L
        val run = gate.decide(start)
        assertEquals(LocalUploadSync.Action.RUN, run.action); assertEquals(start + cooldown, run.holdUntilMillis)
        for (later in listOf(1_000L, 60_000L, 120_000L, cooldown - 1)) {
            val repeat = gate.decide(start + later)
            assertEquals(LocalUploadSync.Action.DEFER, repeat.action)
            assertEquals(start + cooldown, repeat.holdUntilMillis); assertTrue(repeat.syncWhenHoldEnds)
        }
        assertEquals(LocalUploadSync.Action.RUN, gate.decide(start + cooldown).action)
        // A clock set back does not hold uploads for the size of the jump.
        assertEquals(LocalUploadSync.Action.RUN, gate.decide(start - 3_600_000L).action)
        // A LOCAL sync with nothing to send also starts the cooldown, but asks for no later sync.
        pass()
        val idle = gate.decide(start + 10 * cooldown)
        assertEquals(LocalUploadSync.Action.NOTHING_TO_UPLOAD, idle.action); assertFalse(idle.syncWhenHoldEnds)
        assertEquals(LocalUploadSync.Action.DEFER, gate.decide(start + 10 * cooldown + 1).action)
        assertFalse(gate.decide(start + 10 * cooldown + 1).syncWhenHoldEnds)
    }

    @Test fun localUploadSyncWithNothingToSendEndsWithoutTouchingTheProvider() = fixture {
        api.remote[person().resource] = person(); pass()
        provider.writes.clear()
        val before = System.currentTimeMillis()
        val result = localUploadSync()
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.writes)
        assertFalse(result.hasError()); assertFalse(result.fullSyncRequested)
        // Android holds this account's next automatic sync (and so the next LOCAL one) for the cooldown.
        assertTrue(result.delayUntil * 1000 >= before + LocalUploadSync.COOLDOWN_MILLIS)
        assertEquals("", prefs.status)
    }

    @Test fun repeatLocalUploadSyncInsideTheCooldownDefersToAFullSync() = fixture {
        api.remote[person().resource] = person(); pass()
        editPhone(ownedId(), "456")
        val last = System.currentTimeMillis() - 5 * 60 * 1000L
        prefs.lastLocalUploadSync = last
        provider.writes.clear()
        val result = localUploadSync()
        assertEquals(emptyList<ImportContactsProvider.Write>(), provider.writes)
        assertEquals((last + LocalUploadSync.COOLDOWN_MILLIS + 999) / 1000, result.delayUntil)
        assertTrue(result.fullSyncRequested); assertFalse(result.hasError())
        assertEquals(last, prefs.lastLocalUploadSync)
    }

    @Test fun downloadOnlyAccountsAndManualSyncs() = fixture(SyncMode.DOWNLOAD) {
        api.remote[person().resource] = person(); pass()
        provider.update(RawContacts.CONTENT_URI.buildUpon().appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build(),
            ContentValues().apply { put(RawContacts.DIRTY, 1) }, null, null)
        // Nothing is ever uploaded for a download-only account, dirty rows or not.
        assertEquals(LocalUploadSync.Action.NOTHING_TO_UPLOAD, gate.decide().action)
        // Only Android's LOCAL sync is gated; the owner's "Sync now" always runs.
        assertTrue(LocalUploadSync.isLocalUpload(Bundle().apply { putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true) }))
        assertFalse(LocalUploadSync.isLocalUpload(Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD, true); putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        }))
        assertFalse(LocalUploadSync.isLocalUpload(Bundle()))
    }
}
