/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.credential

import android.os.Binder
import android.os.Parcel
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.credential.manager.common.IBooleanCallback
import com.google.android.gms.credential.manager.common.IPendingIntentCallback
import com.google.android.gms.credential.manager.common.ISettingsCallback
import com.google.android.gms.credential.manager.firstparty.internal.ICredentialManagerService
import com.google.android.gms.credential.manager.invocationparams.CredentialManagerInvocationParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Chrome on Play services 26.19.30+ reads and writes "use screen lock before filling passwords" through the
 * first-party credential manager (CYC-3296): transactions 4 and 5, each followed by ApiMetadata.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ReauthBeforeAutofillingTest {
    private val descriptor = "com.google.android.gms.credential.manager.firstparty.internal.ICredentialManagerService"

    private class Recording : ICredentialManagerService.Stub() {
        var asked = false
        var set: Boolean? = null
        override fun getCredentialManagerIntent(callback: IPendingIntentCallback?, params: CredentialManagerInvocationParams?) = Unit
        override fun getSetting(callback: ISettingsCallback?, key: String?) = Unit
        override fun setSetting(callback: IStatusCallback?, key: String?, value: ByteArray?) = Unit
        override fun isBiometricReauthBeforeAutofillingRequired(callback: IBooleanCallback?, apiMetadata: ApiMetadata?) { asked = true }
        override fun setIsReauthBeforeAutofillingRequired(callback: IStatusCallback?, required: Boolean, apiMetadata: ApiMetadata?) { set = required }
    }

    private fun Recording.call(code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            data.write()
            data.writeInt(1)
            ApiMetadata.DEFAULT.writeToParcel(data, 0)
            data.setDataPosition(0)
            transact(code, data, reply, 0)
        } finally { data.recycle(); reply.recycle() }
    }

    @Test
    fun getIsTransactionFourAndSetIsTransactionFive() {
        val service = Recording()
        service.call(4) { writeStrongBinder(Binder()) }
        assertTrue(service.asked)
        service.call(5) { writeStrongBinder(Binder()); writeInt(1) }
        assertEquals(true, service.set)
    }

    @Test
    fun theCallbackIsOneWayTransactionOneWithStatusValueAndMetadata() {
        var received: Pair<Int, Boolean>? = null
        val callback = object : IBooleanCallback.Stub() {
            override fun onBoolean(status: Status?, value: Boolean, apiMetadata: ApiMetadata?) { received = status!!.statusCode to value }
        }
        IBooleanCallback.Stub.asInterface(callback).onBoolean(Status.SUCCESS, true, ApiMetadata.DEFAULT)
        assertEquals(0 to true, received)
    }

    @Test
    fun requiredOnlyWhenTurnedOnAndThePhoneHasAScreenLock() {
        assertFalse(ReauthBeforeAutofilling.isRequired(deviceSecure = false, stored = true))
        assertFalse(ReauthBeforeAutofilling.isRequired(deviceSecure = true, stored = false))
        assertTrue(ReauthBeforeAutofilling.isRequired(deviceSecure = true, stored = true))
        val context = RuntimeEnvironment.getApplication()
        assertTrue(ReauthBeforeAutofilling.setRequired(context, true))
        assertTrue(context.getSharedPreferences("credential_manager", 0).getBoolean("reauth_before_autofilling", false))
    }
}
