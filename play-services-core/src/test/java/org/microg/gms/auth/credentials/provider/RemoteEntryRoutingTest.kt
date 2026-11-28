/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.credentials.provider

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.SigningInfo
import android.credentials.CredentialOption as FrameworkCredentialOption
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.service.credentials.CallingAppInfo as FrameworkCallingAppInfo
import android.service.credentials.CreateCredentialRequest as FrameworkCreateCredentialRequest
import android.service.credentials.CredentialProviderService
import android.service.credentials.GetCredentialRequest as FrameworkGetCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialOption
import androidx.credentials.GetPasswordOption
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import com.google.android.gms.fido.fido2.api.common.BrowserPublicKeyCredentialRequestOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.fido.core.ui.ACTION_FIDO_AUTHENTICATE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_CREDENTIAL_ID
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_HYBRID_ONLY
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_OPTIONS
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_SOURCE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.KEY_TYPE
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.SOURCE_BROWSER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_REGISTER
import org.microg.gms.fido.core.ui.AuthenticatorActivity.Companion.TYPE_SIGN
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Android's "another device" entry comes from [RemoteService], the configured hybrid service. Android forwards it
 * every option of a request in the caller's order, and choosing it must open the cross-device QR flow, never a
 * passkey on this phone or its synced Google account (CYC-3677).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteEntryRoutingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val origin = "https://example.com"
    private val getJson = """{"challenge":"AAECAwQFBgcICQoLDA0ODw","rpId":"example.com","allowCredentials":[],"userVerification":"preferred"}"""
    private val createJson = """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"dXNlcg","name":"user","displayName":"User"},
        |"challenge":"AAECAwQFBgcICQoLDA0ODw","pubKeyCredParams":[{"type":"public-key","alg":-7}]}""".trimMargin()

    @Before fun letAppCompatResourceCheckFailOnce() {
        // Config.NONE has no app resources. AppCompat looks up its vector-drawable test resource once per process
        // and remembers that it did, so let that one lookup fail here instead of in whichever test runs first.
        runCatching { Robolectric.buildActivity(PublicKeyProxyActivity::class.java).create() }
    }

    private fun caller() = FrameworkCallingAppInfo("com.android.chrome", SigningInfo(), origin)
    private fun CredentialOption.toFramework() = FrameworkCredentialOption.Builder(type, requestData, candidateQueryData)
        .setIsSystemProviderRequired(isSystemProviderRequired).build()

    private fun proxyIntent(action: String?, data: Uri? = null) =
        Intent(action).setClass(context, PublicKeyProxyActivity::class.java).setData(data)

    private fun Intent.withGet(vararg options: CredentialOption) = putExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST,
        FrameworkGetCredentialRequest(caller(), options.map { it.toFramework() }))

    private fun Intent.withCreate() = putExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST,
        FrameworkCreateCredentialRequest(caller(), CreatePublicKeyCredentialRequest(createJson).type,
            CreatePublicKeyCredentialRequest(createJson).credentialData))

    private fun ActivityController<PublicKeyProxyActivity>.startedFidoIntent(): Intent? =
        shadowOf(get()).nextStartedActivityForResult?.intent

    private fun remoteEntries(): Pair<PendingIntent, PendingIntent> {
        val service = Robolectric.buildService(RemoteService::class.java).create().get()
        var get: BeginGetCredentialResponse? = null
        var create: BeginCreateCredentialResponse? = null
        service.onBeginGetCredentialRequest(BeginGetCredentialRequest(listOf(
            BeginGetPasswordOption(emptySet(), Bundle(), "password"),
            BeginGetPublicKeyCredentialOption(Bundle(), "passkey", getJson),
        )), CancellationSignal(), object : OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException> {
            override fun onResult(result: BeginGetCredentialResponse) { get = result }
            override fun onError(error: GetCredentialException) = fail("get: $error")
        })
        service.onBeginCreateCredentialRequest(BeginCreatePublicKeyCredentialRequest(createJson, null, Bundle()),
            CancellationSignal(), object : OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException> {
                override fun onResult(result: BeginCreateCredentialResponse) { create = result }
                override fun onError(error: CreateCredentialException) = fail("create: $error")
            })
        return requireNotNull(get?.remoteEntry).pendingIntent to requireNotNull(create?.remoteEntry).pendingIntent
    }

    @Test fun remoteServiceMarksGetAndCreateEntriesAsRemoteWithoutSharingPasswordAndPasskeyServiceIntents() {
        val (get, create) = remoteEntries()
        for (entry in listOf(get, create)) {
            val intent = shadowOf(entry).savedIntent
            assertEquals(RemoteService.ACTION_REMOTE_ENTRY, intent.action)
            assertEquals(PublicKeyProxyActivity::class.java.name, intent.component?.className)
            // PasswordAndPasskeyService's entries carry no action, so FLAG_UPDATE_CURRENT can never merge them with these.
            assertFalse(intent.filterEquals(Intent(context, PublicKeyProxyActivity::class.java)))
            assertTrue(shadowOf(entry).isActivity)
        }
        assertNotEquals(shadowOf(get).requestCode, shadowOf(create).requestCode)
    }

    @Test fun passwordFirstRemoteGetPicksThePasskeyOptionAndOpensOnlyTheHybridFlow() {
        val controller = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(RemoteService.ACTION_REMOTE_ENTRY).withGet(GetPasswordOption(), GetPublicKeyCredentialOption(getJson))).create()
        val fido = requireNotNull(controller.startedFidoIntent()) { "No authenticator started; the request failed instead" }
        assertFalse(controller.get().isFinishing)
        assertEquals(ACTION_FIDO_AUTHENTICATE, fido.action)
        assertEquals(TYPE_SIGN, fido.getStringExtra(KEY_TYPE))
        assertEquals(SOURCE_BROWSER, fido.getStringExtra(KEY_SOURCE))
        assertTrue(fido.getBooleanExtra(KEY_HYBRID_ONLY, false))
        val options = BrowserPublicKeyCredentialRequestOptions.deserializeFromBytes(fido.getByteArrayExtra(KEY_OPTIONS))
        assertEquals("example.com", options.publicKeyCredentialRequestOptions.rpId)
        assertEquals(origin, options.origin.toString())
    }

    @Test fun remoteCreateOpensOnlyTheHybridFlow() {
        val controller = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(RemoteService.ACTION_REMOTE_ENTRY).withCreate()).create()
        val fido = requireNotNull(controller.startedFidoIntent())
        assertEquals(TYPE_REGISTER, fido.getStringExtra(KEY_TYPE))
        assertTrue(fido.getBooleanExtra(KEY_HYBRID_ONLY, false))
    }

    @Test fun remoteEntryNeverTakesTheSyncedAccountPasskeyPath() {
        // A synced-passkey ticket would send any other entry to SyncedPasskeyProvider; the remote entry ignores it.
        val ticket = Uri.parse("microg-passkey://ticket")
        val get = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(RemoteService.ACTION_REMOTE_ENTRY, ticket).withGet(GetPublicKeyCredentialOption(getJson))).create()
        assertTrue(requireNotNull(get.startedFidoIntent()).getBooleanExtra(KEY_HYBRID_ONLY, false))
        val create = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(RemoteService.ACTION_REMOTE_ENTRY, ticket).withCreate()).create()
        assertTrue(requireNotNull(create.startedFidoIntent()).getBooleanExtra(KEY_HYBRID_ONLY, false))
    }

    @Test fun passwordAndPasskeyServiceEntriesKeepTheirLocalAndSyncedPaths() {
        // A local passkey entry: the authenticator may still use screen lock and account passkeys.
        val local = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(null).putExtra(KEY_CREDENTIAL_ID, "Y3JlZA").withGet(GetPublicKeyCredentialOption(getJson))).create()
        val fido = requireNotNull(local.startedFidoIntent())
        assertFalse(fido.hasExtra(KEY_HYBRID_ONLY))
        assertEquals("Y3JlZA", fido.getStringExtra(KEY_CREDENTIAL_ID))
        // A synced Google account passkey entry still goes to SyncedPasskeyProvider, not the authenticator.
        val synced = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(null, Uri.parse("microg-passkey://ticket")).withGet(GetPublicKeyCredentialOption(getJson))).create()
        assertNull(synced.startedFidoIntent())
        assertFalse(synced.get().isFinishing)
        synced.destroy()
    }

    @Test fun cancellingTheAuthenticatorReturnsACancellationToTheCaller() {
        val get = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(RemoteService.ACTION_REMOTE_ENTRY).withGet(GetPasswordOption(), GetPublicKeyCredentialOption(getJson))).create()
        shadowOf(get.get()).receiveResult(requireNotNull(get.startedFidoIntent()), Activity.RESULT_CANCELED, null)
        assertTrue(get.get().isFinishing)
        val getError = shadowOf(get.get()).resultIntent
            .getSerializableExtra(CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION) as android.credentials.GetCredentialException
        assertEquals(android.credentials.GetCredentialException.TYPE_USER_CANCELED, getError.type)

        val create = Robolectric.buildActivity(PublicKeyProxyActivity::class.java,
            proxyIntent(RemoteService.ACTION_REMOTE_ENTRY).withCreate()).create()
        shadowOf(create.get()).receiveResult(requireNotNull(create.startedFidoIntent()), Activity.RESULT_CANCELED, null)
        val createError = shadowOf(create.get()).resultIntent
            .getSerializableExtra(CredentialProviderService.EXTRA_CREATE_CREDENTIAL_EXCEPTION) as android.credentials.CreateCredentialException
        assertEquals(android.credentials.CreateCredentialException.TYPE_USER_CANCELED, createError.type)
    }
}
