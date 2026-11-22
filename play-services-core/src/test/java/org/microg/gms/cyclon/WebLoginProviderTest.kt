/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cyclon

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBinder

@Implements(Binder::class)
class WebLoginBinderIdentityShadow : ShadowBinder() {
    companion object {
        var clears = 0
        var restores = 0
        var uidDuringAuth: Int? = null

        @JvmStatic
        @Implementation
        fun clearCallingIdentity(): Long {
            val prior = Binder.getCallingUid()
            clears++
            ShadowBinder.setCallingUid(Process.myUid())
            return prior.toLong()
        }

        @JvmStatic
        @Implementation
        fun restoreCallingIdentity(token: Long) {
            restores++
            ShadowBinder.setCallingUid(token.toInt())
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35], shadows = [WebLoginBinderIdentityShadow::class])
class WebLoginProviderTest {
    private val corePkg = "ai.cyclon.core"
    private val coreUid = 12345
    private lateinit var context: Context
    private lateinit var provider: WebLoginProvider
    private val requestedCalls = mutableListOf<Pair<String, String>>()
    private var nextAuthResponse: String? = "https://accounts.google.com/MergeSession?uberauth=test-token&continue=https%3A%2F%2Faccounts.google.com"

    @Before
    fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getPackageName(): String = "com.google.android.gms"
        }
        installCore(system = true)
        ShadowBinder.setCallingUid(coreUid)
        WebLoginBinderIdentityShadow.clears = 0
        WebLoginBinderIdentityShadow.restores = 0
        WebLoginBinderIdentityShadow.uidDuringAuth = null
        requestedCalls.clear()
        provider = WebLoginProvider().also {
            it.authRequester = { _, accountName, service ->
                WebLoginBinderIdentityShadow.uidDuringAuth = Binder.getCallingUid()
                requestedCalls += accountName to service
                nextAuthResponse
            }
            it.attachInfo(context, ProviderInfo().apply { authority = WebLoginProvider.AUTHORITY })
        }
    }

    @After
    fun cleanup() {
        ShadowBinder.reset()
    }

    private fun installCore(system: Boolean, sharedWith: String? = null) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = corePkg
            applicationInfo = ApplicationInfo().apply {
                packageName = corePkg
                uid = coreUid
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
        if (sharedWith != null) {
            shadowOf(context.packageManager).setPackagesForUid(coreUid, corePkg, sharedWith)
        } else {
            shadowOf(context.packageManager).setPackagesForUid(coreUid, corePkg)
        }
    }

    private fun addGoogleAccount(email: String, deviceMarker: Boolean = false) {
        val am = AccountManager.get(context)
        val account = Account(email, "com.google")
        shadowOf(am).addAccount(account)
        if (deviceMarker) {
            am.setUserData(account, DeviceAccount.MARKER, "1")
        }
    }

    @Test
    fun rejectsUnauthorizedCallersAndMethods() {
        ShadowBinder.setCallingUid(coreUid + 1)
        assertSecurityException { provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null) }

        ShadowBinder.setCallingUid(coreUid)
        installCore(system = false)
        assertSecurityException { provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null) }

        installCore(system = true, sharedWith = "com.other.app")
        assertSecurityException { provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null) }

        installCore(system = true)
        assertSecurityException { provider.call("other_method", null, null) }
        assertSecurityException { provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, "unexpected_arg", null) }
    }

    @Test
    fun excludesCyclonDeviceAccountAndReturnsNoAccountWhenOnlyDeviceAccountExists() {
        addGoogleAccount("cyclondevice@gmail.com")
        addGoogleAccount("marked@gmail.com", deviceMarker = true)

        val result = provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null)
        assertEquals(WebLoginProvider.VERSION, result.getInt(WebLoginProvider.KEY_VERSION))
        assertEquals(WebLoginProvider.STATUS_NO_ACCOUNT, result.getString(WebLoginProvider.KEY_STATUS))
        assertNull(result.getString(WebLoginProvider.KEY_URL))
        assertTrue(requestedCalls.isEmpty())
    }

    @Test
    fun mintsMergeSessionUrlForVisibleOwnerAccountAndClearsBinderIdentity() {
        addGoogleAccount("cyclondevice@gmail.com")
        addGoogleAccount("owner@example.com")

        val result = provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null)
        assertEquals(WebLoginProvider.STATUS_OK, result.getString(WebLoginProvider.KEY_STATUS))
        assertEquals(nextAuthResponse, result.getString(WebLoginProvider.KEY_URL))
        assertEquals(listOf("owner@example.com" to "weblogin:continue=https%3A%2F%2Faccounts.google.com"), requestedCalls)
        assertEquals(Process.myUid(), WebLoginBinderIdentityShadow.uidDuringAuth)
        assertEquals(1, WebLoginBinderIdentityShadow.clears)
        assertEquals(1, WebLoginBinderIdentityShadow.restores)
        assertEquals(coreUid, Binder.getCallingUid())
    }

    @Test
    fun selectsRequestedAccountCaseInsensitivelyAndRejectsDeviceAccountByName() {
        addGoogleAccount("cyclondevice@gmail.com")
        addGoogleAccount("first@example.com")
        addGoogleAccount("second@example.com")

        val chosen = provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, Bundle().apply {
            putString(WebLoginProvider.KEY_ACCOUNT, "SECOND@EXAMPLE.COM")
            putString(WebLoginProvider.KEY_CONTINUE_URL, "https://myaccount.google.com")
        })
        assertEquals(WebLoginProvider.STATUS_OK, chosen.getString(WebLoginProvider.KEY_STATUS))
        assertEquals(listOf("second@example.com" to "weblogin:continue=https%3A%2F%2Fmyaccount.google.com"), requestedCalls)

        val hidden = provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, Bundle().apply {
            putString(WebLoginProvider.KEY_ACCOUNT, "cyclondevice@gmail.com")
        })
        assertEquals(WebLoginProvider.STATUS_NO_ACCOUNT, hidden.getString(WebLoginProvider.KEY_STATUS))
    }

    @Test
    fun rejectsInvalidContinueUrlAndInvalidAuthResponses() {
        addGoogleAccount("owner@example.com")

        for (badContinue in listOf(
            "http://accounts.google.com",
            "https://evil.example.com",
            "https://user@accounts.google.com",
            "https://accounts.google.com:8443",
            "javascript:alert(1)",
        )) {
            val res = provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, Bundle().apply {
                putString(WebLoginProvider.KEY_CONTINUE_URL, badContinue)
            })
            assertEquals(badContinue, WebLoginProvider.STATUS_INVALID_CONTINUE_URL, res.getString(WebLoginProvider.KEY_STATUS))
        }
        assertTrue(requestedCalls.isEmpty())

        nextAuthResponse = "https://accounts.google.com/MergeSession?uberauth=WILL_NOT_SIGN_IN"
        assertEquals(
            WebLoginProvider.STATUS_WILL_NOT_SIGN_IN,
            provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null).getString(WebLoginProvider.KEY_STATUS),
        )

        nextAuthResponse = "https://evil.example.com/MergeSession?uberauth=abc"
        assertEquals(
            WebLoginProvider.STATUS_INVALID_URL,
            provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null).getString(WebLoginProvider.KEY_STATUS),
        )

        nextAuthResponse = null
        assertEquals(
            WebLoginProvider.STATUS_TOKEN_FAILED,
            provider.call(WebLoginProvider.METHOD_MERGE_SESSION_URL, null, null).getString(WebLoginProvider.KEY_STATUS),
        )
    }

    @Test
    fun urlPolicyValidatesContinueAndMergeSessionUrls() {
        assertEquals(WebLoginUrlPolicy.DEFAULT_CONTINUE_URL, WebLoginUrlPolicy.validateContinueUrl(null))
        assertEquals(WebLoginUrlPolicy.DEFAULT_CONTINUE_URL, WebLoginUrlPolicy.validateContinueUrl("   "))
        assertEquals("https://accounts.google.com/ServiceLogin", WebLoginUrlPolicy.validateContinueUrl("https://accounts.google.com/ServiceLogin"))
        assertNull(WebLoginUrlPolicy.validateContinueUrl("https://accounts.google.com.evil.com"))

        assertTrue(WebLoginUrlPolicy.isValidMergeSessionUrl("https://accounts.google.com/MergeSession?uberauth=xyz&continue=https%3A%2F%2Faccounts.google.com"))
        assertFalse(WebLoginUrlPolicy.isValidMergeSessionUrl("https://accounts.google.com/signin"))
        assertFalse(WebLoginUrlPolicy.isValidMergeSessionUrl("http://accounts.google.com/MergeSession?uberauth=xyz"))
        assertFalse(WebLoginUrlPolicy.isValidMergeSessionUrl("https://accounts.google.com:8443/MergeSession?uberauth=xyz"))
        assertFalse(WebLoginUrlPolicy.isValidMergeSessionUrl("https://user@accounts.google.com/MergeSession?uberauth=xyz"))
    }

    private fun assertSecurityException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected SecurityException")
        } catch (_: SecurityException) {
            // expected
        }
    }
}
