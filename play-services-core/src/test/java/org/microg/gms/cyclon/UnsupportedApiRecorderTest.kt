/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cyclon

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.common.moduleinstall.internal.ApiFeatureRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.AbstractGmsServiceBroker
import org.microg.gms.common.GmsService
import org.microg.gms.moduleinstall.ModuleInstallServiceImpl
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.File
import java.util.EnumSet

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class UnsupportedApiRecorderTest {
    private val corePkg = "ai.cyclon.core"
    private val coreUid = 10123
    private val chromePkg = "com.android.chrome"
    private val chromeUid = 10243
    private lateinit var context: Context
    private lateinit var provider: UnsupportedApiProvider

    @Before
    fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.google.android.gms"
        }
        installPackage(corePkg, coreUid, system = true, versionCode = 500L, versionName = "1.0-core")
        installPackage(chromePkg, chromeUid, system = false, versionCode = 812709204L, versionName = "158.0.8127.92")
        installPackage("com.google.android.gms", Process.myUid(), system = true, versionCode = 264531136L, versionName = "0.3.18.264531")
        UnsupportedApiRecorder.flushPersist()
        snapshotDir().deleteRecursively()
        UnsupportedApiRecorder.store.clear()
        UnsupportedApiRecorder.init(context)
        UnsupportedApiRecorder.flushPersist()
        ShadowBinder.setCallingUid(coreUid)
        provider = UnsupportedApiProvider().also {
            it.attachInfo(context, ProviderInfo().apply {
                authority = UnsupportedApiProvider.AUTHORITY
                packageName = "com.google.android.gms"
            })
        }
    }

    @After
    fun tearDown() {
        UnsupportedApiRecorder.flushPersist()
        UnsupportedApiRecorder.store.clear()
        snapshotDir().deleteRecursively()
        ShadowBinder.reset()
    }

    private fun snapshotDir(): File = File(context.noBackupFilesDir, UnsupportedApiRecorder.DIR_NAME)

    private fun installPackage(
        pkg: String,
        uid: Int,
        system: Boolean,
        sharedPkg: String? = null,
        versionCode: Long = 1L,
        versionName: String = "1.0",
    ) {
        val spm = shadowOf(context.packageManager)
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            this.uid = uid
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
        spm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
            longVersionCode = versionCode
            this.versionName = versionName
        })
        spm.setPackagesForUid(uid, *(if (sharedPkg == null) arrayOf(pkg) else arrayOf(pkg, sharedPkg)))
    }

    @Test
    fun missingFeaturesComputesAbsentAndLowerVersions() {
        val missing = UnsupportedApiStore.missingFeatures(
            requested = listOf(
                FeatureSpec("present_exact", 2),
                FeatureSpec("present_higher", 1),
                FeatureSpec("too_old", 3),
                FeatureSpec("absent_feat", 1),
            ),
            advertised = listOf(
                FeatureSpec("present_exact", 2),
                FeatureSpec("present_higher", 5),
                FeatureSpec("too_old", 1),
            ),
        )
        assertEquals(listOf("absent_feat@1", "too_old@3(have:1)"), missing)
    }

    @Test
    fun storeDeduplicatesThrottlesAndDrains() {
        var now = 10_000L
        val store = UnsupportedApiStore(maxEntries = 3, reReportWindowMs = 60_000L, clock = { now })

        assertTrue(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))
        // Duplicate while still dirty increments count and returns true so cross-process snapshots persist.
        now = 11_000L
        assertTrue(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))

        // Recent sees it without clearing dirty.
        val recent = store.recentFor(chromePkg, windowMs = 10_000L)
        assertEquals(1, recent.size)
        assertEquals(2L, recent[0].count)

        // Drain returns it once and clears dirty.
        val drained1 = store.drainDirty()
        assertEquals(1, drained1.size)
        assertEquals(0, store.drainDirty().size)

        // Repeat inside re-report window persists updated timestamp/count without re-dirtying.
        now = 20_000L
        assertTrue(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))
        assertEquals(0, store.drainDirty().size)
        assertEquals(3L, store.recentFor(chromePkg, windowMs = 5_000L)[0].count)

        // After re-report window, a new hit marks it dirty again.
        now = 90_000L
        assertTrue(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))
        assertEquals(1, store.drainDirty().size)
    }

    @Test
    fun recentBeforeDrainDoesNotHideSiblingRecords() {
        val now = System.currentTimeMillis()
        val siblingEntry = UnsupportedApiEntry(
            packageName = chromePkg,
            kind = UnsupportedApiStore.KIND_MISSING_FEATURES,
            service = "CHROME_SYNC_FIRST_PARTY",
            serviceId = 204,
            gmsVersion = 264380000,
            features = listOf("FIRSTPARTY_PASSWORD_DELETE@1"),
            count = 1L,
            firstSeenAt = now,
            lastSeenAt = now,
        )
        val dir = snapshotDir().apply { mkdirs() }
        File(dir, "persistent.json").writeText(
            UnsupportedApiRecorder.serializeSnapshot(listOf(PersistedUnsupportedApiSlot(siblingEntry))),
            Charsets.UTF_8,
        )

        ShadowBinder.setCallingUid(coreUid)
        val recent = JSONArray(provider.call(UnsupportedApiProvider.METHOD_RECENT, chromePkg, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(1, recent.length())
        assertEquals("CHROME_SYNC_FIRST_PARTY", recent.getJSONObject(0).getString("service"))

        // Drain after recent must still deliver the sibling record once.
        val drained = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(1, drained.length())
        assertEquals("CHROME_SYNC_FIRST_PARTY", drained.getJSONObject(0).getString("service"))
        val drainedAgain = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(0, drainedAgain.length())
    }

    @Test
    fun repeatedFailurePersistsUpdatedCountAndReTriggersAfterWindow() {
        val dummyBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ANY)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitComplete(ConnectionResult.API_DISABLED, null, null)
            }
        }
        val req = GetServiceRequest(GmsService.SAFE_BROWSING.SERVICE_ID).apply {
            packageName = chromePkg
            gmsVersion = 264380000
            apiFeatures = arrayOf(Feature("feature_standalone_safe_browsing", 1))
        }
        ShadowBinder.setCallingUid(chromeUid)
        dummyBroker.getService(RecordingCallback(), req)
        assertTrue(UnsupportedApiRecorder.flushPersist())
        val ownFile = snapshotDir().listFiles { f -> f.name.endsWith(".json") }!!.single()
        val firstSaved = UnsupportedApiRecorder.parseSnapshot(ownFile.readText(Charsets.UTF_8)).single()
        assertEquals(1L, firstSaved.entry.count)

        // Repeat while entry is still dirty in this process (like :persistent before Core drains default).
        dummyBroker.getService(RecordingCallback(), req)
        assertTrue(UnsupportedApiRecorder.flushPersist())
        val secondSaved = UnsupportedApiRecorder.parseSnapshot(ownFile.readText(Charsets.UTF_8)).single()
        assertEquals(2L, secondSaved.entry.count)
        assertTrue(secondSaved.entry.lastSeenAt >= firstSaved.entry.lastSeenAt)

        // Cross-process default store sees repeat after re-report window and drains it again.
        var now = 10_000L
        val defaultStore = UnsupportedApiStore(reReportWindowMs = 60_000L, clock = { now })
        val persistentStore = UnsupportedApiStore(reReportWindowMs = 60_000L, clock = { now })
        persistentStore.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED")
        defaultStore.mergeExternal(persistentStore.snapshotAll(), markDirtyIfNewer = true)
        assertEquals(1, defaultStore.drainDirty().size)

        now = 90_000L
        assertTrue(persistentStore.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED"))
        defaultStore.mergeExternal(persistentStore.snapshotAll(), markDirtyIfNewer = true)
        val reDrained = defaultStore.drainDirty()
        assertEquals(1, reDrained.size)
        assertEquals(2L, reDrained[0].count)
    }

    @Test
    fun restartPreservesUndeliveredAndDrainedState() {
        val dummyBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ANY)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitComplete(ConnectionResult.API_DISABLED, null, null)
            }
        }
        ShadowBinder.setCallingUid(chromeUid)
        dummyBroker.getService(RecordingCallback(), GetServiceRequest(GmsService.SAFE_BROWSING.SERVICE_ID).apply {
            packageName = chromePkg
            gmsVersion = 264380000
        })
        assertTrue(UnsupportedApiRecorder.flushPersist())

        // Simulate process restart before Core calls drain.
        UnsupportedApiRecorder.store.clear()
        UnsupportedApiRecorder.init(context)
        assertTrue(UnsupportedApiRecorder.flushPersist())

        ShadowBinder.setCallingUid(coreUid)
        val drainedAfterCrash = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(1, drainedAfterCrash.length())
        assertEquals("SAFE_BROWSING", drainedAfterCrash.getJSONObject(0).getString("service"))
        assertTrue(UnsupportedApiRecorder.flushPersist())

        // Simulate second restart after drain: already-delivered record stays clean.
        UnsupportedApiRecorder.store.clear()
        UnsupportedApiRecorder.init(context)
        assertTrue(UnsupportedApiRecorder.flushPersist())

        val drainedAfterCleanRestart = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(0, drainedAfterCleanRestart.length())
    }

    @Test
    fun snapshotSerializationStaysWithinMaxFileBytes() {
        val longFeatures = (1..UnsupportedApiStore.MAX_FEATURES).map { idx ->
            "feature_${idx}_" + "a".repeat(100) + "@1"
        }
        val slots = (1..UnsupportedApiStore.MAX_ENTRIES).map { idx ->
            PersistedUnsupportedApiSlot(
                entry = UnsupportedApiEntry(
                    packageName = chromePkg,
                    kind = UnsupportedApiStore.KIND_MISSING_FEATURES,
                    service = "SVC_$idx",
                    serviceId = idx,
                    gmsVersion = 264380000,
                    features = longFeatures,
                    count = 1L,
                    firstSeenAt = idx.toLong(),
                    lastSeenAt = idx.toLong(),
                ),
                dirty = idx == UnsupportedApiStore.MAX_ENTRIES,
                lastDrainedAt = if (idx == UnsupportedApiStore.MAX_ENTRIES) 0L else 100L,
            )
        }
        val payload = UnsupportedApiRecorder.serializeSnapshot(slots, UnsupportedApiRecorder.MAX_FILE_BYTES)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= UnsupportedApiRecorder.MAX_FILE_BYTES)
        val parsed = UnsupportedApiRecorder.parseSnapshot(payload)
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.any { it.entry.service == "SVC_${UnsupportedApiStore.MAX_ENTRIES}" && it.dirty })
    }

    @Test
    fun peekAndAckPreserveUndeliveredAndRespectNewerObservations() {
        var now = 10_000L
        val store = UnsupportedApiStore(clock = { now })
        store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", appVersionCode = 100L, microgVersionCode = 264531136L)

        val (peek1, hasMore1) = store.peekDirty()
        assertEquals(1, peek1.size)
        assertFalse(hasMore1)
        // Peek leaves the entry dirty if delivery fails before ack.
        assertEquals(1, store.peekDirty().first.size)

        // Newer observation arrives after peek: stale ack must NOT clear the entry.
        now = 20_000L
        store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", appVersionCode = 100L, microgVersionCode = 264531136L)
        assertEquals(0, store.ackDrained(listOf(peek1[0].key to peek1[0].lastSeenAt)))
        assertEquals(1, store.peekDirty().first.size)

        // Matching ack clears it.
        val (peek2, _) = store.peekDirty()
        assertEquals(1, store.ackDrained(listOf(peek2[0].key to peek2[0].lastSeenAt)))
        assertEquals(0, store.peekDirty().first.size)
    }

    @Test
    fun ipcReplyStaysBoundedByMaxBytesAndPaginates() {
        var now = 10_000L
        val store = UnsupportedApiStore(clock = { now })
        val longFeatures = (1..UnsupportedApiStore.MAX_FEATURES).map { idx -> "feature_${idx}_" + "x".repeat(80) + "@1" }
        for (i in 1..40) {
            now += 10L
            store.record(chromePkg, UnsupportedApiStore.KIND_MISSING_FEATURES, "SVC_$i", i, 264380000, features = longFeatures)
        }
        val cap = 16 * 1024
        val seenServices = HashSet<String>()
        var pages = 0
        while (pages < 10) {
            val (batch, hasMore) = store.peekDirty(limit = 64, maxBytes = cap)
            if (batch.isEmpty()) break
            val arr = JSONArray()
            for (e in batch) {
                arr.put(e.toJson())
                seenServices.add(e.service)
            }
            assertTrue(arr.toString().toByteArray(Charsets.UTF_8).size <= cap)
            assertEquals(batch.size, store.ackDrained(batch.map { it.key to it.lastSeenAt }))
            pages++
            if (!hasMore) break
        }
        assertTrue(pages > 1)
        assertEquals(40, seenServices.size)
        assertEquals(0, store.peekDirty().first.size)
    }

    @Test
    fun observationTimeVersionsSurviveAppUpdateViaProviderPeekAndAck() {
        val dummyBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ANY)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitComplete(ConnectionResult.API_DISABLED, null, null)
            }
        }
        ShadowBinder.setCallingUid(chromeUid)
        dummyBroker.getService(RecordingCallback(), GetServiceRequest(GmsService.SAFE_BROWSING.SERVICE_ID).apply {
            packageName = chromePkg
            gmsVersion = 264380000
        })

        // App updates to a newer version before Core drains.
        installPackage(chromePkg, chromeUid, system = false, versionCode = 812709999L, versionName = "159.0.0.1")

        ShadowBinder.setCallingUid(coreUid)
        val peekedBundle = provider.call(UnsupportedApiProvider.METHOD_PEEK, null, null)
        val entries = JSONArray(peekedBundle.getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(1, entries.length())
        val first = entries.getJSONObject(0)
        assertEquals(812709204L, first.getLong("appVersionCode"))
        assertEquals("158.0.8127.92", first.getString("appVersionName"))
        assertEquals(264531136L, first.getLong("microgVersionCode"))

        val ackPayload = JSONArray().put(
            JSONObject().put("key", first.getString("key")).put("lastSeenAt", first.getLong("lastSeenAt")),
        ).toString()
        val ackedBundle = provider.call(
            UnsupportedApiProvider.METHOD_ACK,
            null,
            Bundle().apply { putString(UnsupportedApiProvider.KEY_ACKS, ackPayload) },
        )
        assertEquals(1, ackedBundle.getInt(UnsupportedApiProvider.KEY_ACKED))
        val afterAck = JSONArray(provider.call(UnsupportedApiProvider.METHOD_PEEK, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(0, afterAck.length())
    }

    @Test
    fun brokerRecordsDummyApiDisabledAndMissingFeaturesAndIgnoresSatisfiedCalls() {
        val dummyBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ANY)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitComplete(ConnectionResult.API_DISABLED, null, null)
            }
        }
        val callback = RecordingCallback()
        ShadowBinder.setCallingUid(chromeUid)
        val req1 = GetServiceRequest(GmsService.SAFE_BROWSING.SERVICE_ID).apply {
            packageName = chromePkg
            gmsVersion = 264380000
            apiFeatures = arrayOf(Feature("feature_standalone_safe_browsing", 1))
        }
        dummyBroker.getService(callback, req1)
        assertEquals(ConnectionResult.API_DISABLED, callback.lastStatus)

        // Implemented service advertising lower version records missing_features; matching version does not.
        val partialBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.CREDENTIAL_MANAGER)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitCompleteWithConnectionInfo(
                    ConnectionResult.SUCCESS,
                    null,
                    ConnectionInfo().apply {
                        features = arrayOf(Feature("existing_feat", 1))
                    },
                )
            }
        }
        partialBroker.getService(callback, GetServiceRequest(GmsService.CREDENTIAL_MANAGER.SERVICE_ID).apply {
            packageName = chromePkg
            gmsVersion = 264380000
            apiFeatures = arrayOf(Feature("existing_feat", 1))
        })
        partialBroker.getService(callback, GetServiceRequest(GmsService.CREDENTIAL_MANAGER.SERVICE_ID).apply {
            packageName = chromePkg
            gmsVersion = 264380000
            apiFeatures = arrayOf(Feature("existing_feat", 2), Feature("password_checkup_first_party_api", 1))
        })

        // Unsupported service throws and records unsupported_service.
        val narrowBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.SAFE_BROWSING)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) = Unit
        }
        try {
            narrowBroker.getService(callback, GetServiceRequest(99999).apply {
                packageName = chromePkg
                gmsVersion = 264380000
            })
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected
        }

        // ModuleInstall probe records requested features.
        ModuleInstallServiceImpl().areModulesAvailable(null, ApiFeatureRequest().apply {
            callingPackage = chromePkg
            features = listOf(Feature("get_storage_info_api", 1))
        })

        // Query through provider as system Core.
        ShadowBinder.setCallingUid(coreUid)
        val recentBundle = provider.call(UnsupportedApiProvider.METHOD_RECENT, chromePkg, null)
        assertEquals(UnsupportedApiProvider.VERSION, recentBundle.getInt(UnsupportedApiProvider.KEY_VERSION))
        val entries = JSONArray(recentBundle.getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(4, entries.length())

        val kinds = (0 until entries.length()).map { entries.getJSONObject(it).getString("kind") }.toSet()
        assertEquals(
            setOf(
                UnsupportedApiStore.KIND_SERVICE_ERROR,
                UnsupportedApiStore.KIND_MISSING_FEATURES,
                UnsupportedApiStore.KIND_UNSUPPORTED_SERVICE,
                UnsupportedApiStore.KIND_MODULE_INSTALL,
            ),
            kinds,
        )

        val missingEntry = (0 until entries.length()).map { entries.getJSONObject(it) }
            .single { it.getString("kind") == UnsupportedApiStore.KIND_MISSING_FEATURES }
        val feats = missingEntry.getJSONArray("features")
        assertEquals(listOf("existing_feat@2(have:1)", "password_checkup_first_party_api@1"),
            (0 until feats.length()).map { feats.getString(it) })

        // Drain returns the 4 entries once, then 0.
        val drained = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(4, drained.length())
        val drainedAgain = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(0, drainedAgain.length())
    }

    @Test
    fun spoofedPackageNameUsesCallingUidPackage() {
        val dummyBroker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ANY)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitComplete(ConnectionResult.API_DISABLED, null, null)
            }
        }
        ShadowBinder.setCallingUid(chromeUid)
        dummyBroker.getService(RecordingCallback(), GetServiceRequest(GmsService.SAFE_BROWSING.SERVICE_ID).apply {
            packageName = "com.spoofed.other"
        })
        ShadowBinder.setCallingUid(coreUid)
        val chromeEntries = JSONArray(provider.call(UnsupportedApiProvider.METHOD_RECENT, chromePkg, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(1, chromeEntries.length())
        assertEquals(chromePkg, chromeEntries.getJSONObject(0).getString("packageName"))
    }

    @Test
    fun providerRejectsNonCoreNonSystemAndSharedUidCallers() {
        // Third-party caller refused
        ShadowBinder.setCallingUid(chromeUid)
        expectSecurityException { provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null) }

        // Non-system Core refused
        installPackage(corePkg, coreUid, system = false)
        ShadowBinder.setCallingUid(coreUid)
        expectSecurityException { provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null) }

        // Shared UID Core refused
        installPackage(corePkg, coreUid, system = true, sharedPkg = "com.other.shared")
        ShadowBinder.setCallingUid(coreUid)
        expectSecurityException { provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null) }

        // Restore valid Core: unknown method or unexpected drain arg refused
        installPackage(corePkg, coreUid, system = true)
        ShadowBinder.setCallingUid(coreUid)
        expectSecurityException { provider.call("unknown", null, null) }
        expectSecurityException { provider.call(UnsupportedApiProvider.METHOD_DRAIN, "unexpected", null) }
    }

    @Test
    fun ignoresSelfPackageAndNonUnsupportedErrors() {
        val broker = object : AbstractGmsServiceBroker(EnumSet.of(GmsService.ANY)) {
            override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
                callback.onPostInitComplete(ConnectionResult.SIGN_IN_REQUIRED, null, null)
            }
        }
        ShadowBinder.setCallingUid(chromeUid)
        broker.getService(RecordingCallback(), GetServiceRequest(GmsService.SAFE_BROWSING.SERVICE_ID).apply {
            packageName = chromePkg
        })
        assertFalse(UnsupportedApiRecorder.store.record("com.google.android.gms", UnsupportedApiStore.KIND_UNRESOLVED_ACTION, "com.example.ACT"))
        ShadowBinder.setCallingUid(coreUid)
        val entries = JSONArray(provider.call(UnsupportedApiProvider.METHOD_DRAIN, null, null).getString(UnsupportedApiProvider.KEY_ENTRIES))
        assertEquals(0, entries.length())
    }

    private fun expectSecurityException(block: () -> Unit) {
        try {
            block()
            fail("Expected SecurityException")
        } catch (_: SecurityException) {
            // Expected
        }
    }

    private class RecordingCallback : IGmsCallbacks.Stub() {
        var lastStatus: Int = -1
        override fun onPostInitComplete(statusCode: Int, binder: IBinder?, params: Bundle?) {
            lastStatus = statusCode
        }
        override fun onAccountValidationComplete(statusCode: Int, params: Bundle?) = Unit
        override fun onPostInitCompleteWithConnectionInfo(statusCode: Int, binder: IBinder?, info: ConnectionInfo?) {
            lastStatus = statusCode
        }
    }
}
