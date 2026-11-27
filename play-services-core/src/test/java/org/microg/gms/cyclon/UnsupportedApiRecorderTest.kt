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
        installPackage(corePkg, coreUid, system = true)
        installPackage(chromePkg, chromeUid, system = false)
        UnsupportedApiRecorder.store.clear()
        UnsupportedApiRecorder.init(context)
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
        UnsupportedApiRecorder.store.clear()
        ShadowBinder.reset()
    }

    private fun installPackage(pkg: String, uid: Int, system: Boolean, sharedPkg: String? = null) {
        val spm = shadowOf(context.packageManager)
        val appInfo = ApplicationInfo().apply {
            packageName = pkg
            this.uid = uid
            flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
        }
        spm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = appInfo
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
        // Duplicate while still dirty increments count and does not trigger new dirty state.
        now = 11_000L
        assertFalse(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))

        // Recent sees it without clearing dirty.
        val recent = store.recentFor(chromePkg, windowMs = 10_000L)
        assertEquals(1, recent.size)
        assertEquals(2L, recent[0].count)

        // Drain returns it once and clears dirty.
        val drained1 = store.drainDirty()
        assertEquals(1, drained1.size)
        assertEquals(0, store.drainDirty().size)

        // Repeat inside re-report window does not re-dirty, but recent still updates.
        now = 20_000L
        assertFalse(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))
        assertEquals(0, store.drainDirty().size)
        assertEquals(3L, store.recentFor(chromePkg, windowMs = 5_000L)[0].count)

        // After re-report window, a new hit marks it dirty again.
        now = 90_000L
        assertTrue(store.record(chromePkg, UnsupportedApiStore.KIND_SERVICE_ERROR, "SAFE_BROWSING", 45, 264380000, ConnectionResult.API_DISABLED, "API_DISABLED", listOf("feature_standalone_safe_browsing@1")))
        assertEquals(1, store.drainDirty().size)
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
