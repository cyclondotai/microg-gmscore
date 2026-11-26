/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.gcm

import android.os.Binder
import android.os.Parcel
import com.google.android.gms.cloudmessaging.RegisterRequest
import com.google.android.gms.cloudmessaging.UnregisterRequest
import com.google.android.gms.cloudmessaging.internal.ICloudMessagingService
import com.google.android.gms.cloudmessaging.internal.IRegisterCallback
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.common.internal.safeparcel.SafeParcelWriter
import com.squareup.wire.GrpcException
import com.squareup.wire.GrpcStatus
import com.squareup.wire.ProtoReader
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.gcm.registration.RegisterAppInstanceResponse
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Cloud Messaging service 457 (CYC-3296): the binder and parcel layout the Firebase Messaging library sends, and the
 * FCM registrations request Play services 26.45 makes from it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CloudMessagingServiceTest {

    private fun registerRequest(
        senderId: String? = "123456789012", gmpAppId: String? = "1:123456789012:android:0000000000000000",
        apiKey: String? = "test-api-key", installationId: String? = "fid-0123456789", authToken: String? = "fis-token",
        sdkVersion: String? = "fcm-25.1.2_1p"
    ) = RegisterRequest(senderId, gmpAppId, apiKey, installationId, authToken, 4242, sdkVersion)

    /** Writes a RegisterRequest by field number, as the client library does, independent of our annotations. */
    private fun Parcel.writeClientRegisterRequest() {
        writeInt(1) // non-null marker
        val start = SafeParcelWriter.writeObjectHeader(this)
        SafeParcelWriter.write(this, 1, "123456789012", false)
        SafeParcelWriter.write(this, 2, "1:123456789012:android:0000000000000000", false)
        SafeParcelWriter.write(this, 3, "test-api-key", false)
        SafeParcelWriter.write(this, 4, "fid-0123456789", false)
        SafeParcelWriter.write(this, 5, "fis-token", false)
        SafeParcelWriter.write(this, 6, 4242)
        SafeParcelWriter.write(this, 7, "fcm-25.1.2_1p", false)
        SafeParcelWriter.finishObjectHeader(this, start)
    }

    private fun Parcel.writeApiMetadata() {
        writeInt(1)
        ApiMetadata.DEFAULT.writeToParcel(this, 0)
    }

    private class RecordingService : ICloudMessagingService.Stub() {
        var registered: RegisterRequest? = null
        var unregistered: UnregisterRequest? = null
        override fun register(callback: IRegisterCallback?, request: RegisterRequest?, apiMetadata: ApiMetadata?) { registered = request }
        override fun unregister(callback: IStatusCallback?, request: UnregisterRequest?, apiMetadata: ApiMetadata?) { unregistered = request }
    }

    @Test
    fun registerIsTransactionOneWithCallbackRequestAndMetadata() {
        val service = RecordingService()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.google.android.gms.cloudmessaging.internal.ICloudMessagingService")
            data.writeStrongBinder(Binder())
            data.writeClientRegisterRequest()
            data.writeApiMetadata()
            data.setDataPosition(0)
            service.transact(1, data, reply, 0)
        } finally {
            data.recycle(); reply.recycle()
        }
        val request = service.registered!!
        assertEquals("123456789012", request.senderId)
        assertEquals("1:123456789012:android:0000000000000000", request.gmpAppId)
        assertEquals("test-api-key", request.apiKey)
        assertEquals("fid-0123456789", request.installationId)
        assertEquals("fis-token", request.installationAuthToken)
        assertEquals(4242, request.appVersionCode)
        assertEquals("fcm-25.1.2_1p", request.sdkVersion)
    }

    @Test
    fun unregisterIsTransactionTwoWithItsFourFields() {
        val service = RecordingService()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("com.google.android.gms.cloudmessaging.internal.ICloudMessagingService")
            data.writeStrongBinder(Binder())
            data.writeInt(1)
            val start = SafeParcelWriter.writeObjectHeader(data)
            SafeParcelWriter.write(data, 1, "my-project", false)
            SafeParcelWriter.write(data, 2, "test-api-key", false)
            SafeParcelWriter.write(data, 3, "fid-0123456789", false)
            SafeParcelWriter.write(data, 4, "fis-token", false)
            SafeParcelWriter.finishObjectHeader(data, start)
            data.writeApiMetadata()
            data.setDataPosition(0)
            service.transact(2, data, reply, 0)
        } finally {
            data.recycle(); reply.recycle()
        }
        val request = service.unregistered!!
        assertEquals("my-project", request.projectId)
        assertEquals("test-api-key", request.apiKey)
        assertEquals("fid-0123456789", request.installationId)
        assertEquals("fis-token", request.installationAuthToken)
        assertEquals("projects/my-project/registrations/fid-0123456789", FcmRegistration.deleteRegistrationRequest(request).name)
    }

    @Test
    fun theServiceAdvertisesRegisterAndUnregister() {
        assertEquals(listOf("register" to 1L, "unregister" to 1L), CLOUD_MESSAGING_FEATURES.map { it.name to it.version })
    }

    @Test
    fun incompleteRequestsAreDeveloperErrorsInPlayServicesOrder() {
        assertNull(FcmRegistration.problemWith(registerRequest()))
        assertEquals("API key is missing", FcmRegistration.problemWith(registerRequest(apiKey = "", authToken = null)))
        assertEquals("Installation auth token is missing", FcmRegistration.problemWith(registerRequest(authToken = null, senderId = null)))
        assertEquals("GMP app ID is missing", FcmRegistration.problemWith(registerRequest(gmpAppId = null)))
        assertEquals("Installation ID is missing", FcmRegistration.problemWith(registerRequest(installationId = "")))
        assertEquals("Sdk version is missing", FcmRegistration.problemWith(registerRequest(sdkVersion = null)))
        assertEquals("Sender ID is missing", FcmRegistration.problemWith(registerRequest(senderId = "")))
        assertEquals("Project ID is missing", FcmRegistration.problemWith(UnregisterRequest("", "k", "fid", "t")))
    }

    @Test
    fun registerAppInstanceUsesTheFieldNumbersOfTheFcmRegistrationsService() {
        val body = FcmRegistration.registerAppInstanceRequest(registerRequest(), "com.example.app", 0, 264531000, 37, 36)
        val fields = HashMap<Int, Any>()
        val reader = ProtoReader(Buffer().write(body.encode()))
        val token = reader.beginMessage()
        while (true) {
            val tag = reader.nextTag()
            if (tag == -1) break
            fields[tag] = when (tag) {
                1, 2, 5, 9 -> com.squareup.wire.ProtoAdapter.STRING.decode(reader)
                else -> com.squareup.wire.ProtoAdapter.INT64.decode(reader)
            }
        }
        reader.endMessageAndGetUnknownFields(token)
        assertEquals("projects/123456789012/installations/fid-0123456789", fields[1])
        assertEquals("com.example.app", fields[2])
        assertEquals(0L, fields[3])
        assertEquals(4242L, fields[4])
        assertEquals("fcm-25.1.2_1p", fields[5])
        assertEquals(264531000L, fields[6])
        assertEquals(37L, fields[7])
        assertEquals(1L, fields[8])
        assertEquals("1:123456789012:android:0000000000000000", fields[9])
        assertEquals(36L, fields[10])
    }

    @Test
    fun theTokenIsFieldOneOfTheResponse() {
        val bytes = Buffer().writeByte(0x0a).writeByte(3).writeUtf8("tok").readByteArray()
        assertEquals("tok", RegisterAppInstanceResponse.ADAPTER.decode(bytes).token)
    }

    @Test
    fun registrationAuthenticatesTheAppAndThisPhone() {
        val headers = FcmRegistration.registerHeaders(registerRequest(), 4815162342L, 987654321L)
        assertEquals(
            mapOf("X-Goog-Api-Key" to "test-api-key", "X-Goog-Firebase-Installations-Auth" to "fis-token", "X-Aid-Login" to "4815162342:987654321"),
            headers
        )
        assertEquals(
            mapOf("X-Goog-Api-Key" to "k", "X-Goog-Firebase-Installations-Auth" to "t"),
            FcmRegistration.unregisterHeaders(UnregisterRequest("p", "k", "fid", "t"))
        )
    }

    @Test
    fun failuresMapToThePlayServicesStatusCodes() {
        fun code(grpc: GrpcStatus) = FcmRegistration.statusFor(GrpcException(grpc, "x")).statusCode
        assertEquals(CommonStatusCodes.CANCELED, code(GrpcStatus.CANCELLED))
        assertEquals(CommonStatusCodes.DEVELOPER_ERROR, code(GrpcStatus.INVALID_ARGUMENT))
        assertEquals(CommonStatusCodes.TIMEOUT, code(GrpcStatus.DEADLINE_EXCEEDED))
        assertEquals(CommonStatusCodes.ERROR, code(GrpcStatus.PERMISSION_DENIED))
        assertEquals(CommonStatusCodes.ERROR, code(GrpcStatus.UNAUTHENTICATED))
        assertEquals(CommonStatusCodes.NETWORK_ERROR, code(GrpcStatus.UNAVAILABLE))
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, code(GrpcStatus.INTERNAL))
        assertEquals(CommonStatusCodes.NETWORK_ERROR, FcmRegistration.statusFor(IOException("offline")).statusCode)
        assertEquals(CommonStatusCodes.DEVELOPER_ERROR, FcmRegistration.statusFor(CloudMessagingException(10, "API key is missing")).statusCode)
    }

    @Test
    fun logsLeaveKeysAndTokensOut() {
        val text = registerRequest().toString() + UnregisterRequest("p", "test-api-key", "fid-0123456789", "fis-token")
        listOf("test-api-key", "fis-token", "fid-0123456789").forEach { assertEquals(false, text.contains(it)) }
    }
}
