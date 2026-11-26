/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.gcm

import android.content.Context
import android.os.Parcel
import android.os.Process
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.cloudmessaging.RegisterRequest
import com.google.android.gms.cloudmessaging.UnregisterRequest
import com.google.android.gms.cloudmessaging.internal.ICloudMessagingService
import com.google.android.gms.cloudmessaging.internal.IRegisterCallback
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.squareup.wire.GrpcException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.microg.gms.BaseService
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.common.Constants
import org.microg.gms.common.GmsService
import org.microg.gms.common.PackageUtils
import org.microg.gms.gcm.registration.AndroidRegistrationClient
import org.microg.gms.gcm.registration.DeleteRegistrationRequest
import org.microg.gms.gcm.registration.RegisterAppInstanceRequest
import org.microg.gms.gcm.registration.RegisteringClient
import org.microg.gms.gcm.registration.RegistrationApiClient
import org.microg.gms.utils.ExtendedPackageInfo
import org.microg.gms.utils.warnOnTransactionIssues
import java.io.IOException

private const val TAG = "GmsCloudMessaging"

/** Features the Firebase Messaging library requires of Cloud Messaging service 457 (Play services 26.12+). */
internal val CLOUD_MESSAGING_FEATURES = arrayOf(Feature("register", 1), Feature("unregister", 1))

internal const val FCM_REGISTRATIONS_BASE_URL = "https://fcmregistrations.googleapis.com"

/** Android's UserHandle.PER_USER_RANGE: a uid divided by it is the Android user ID. */
private const val PER_USER_UID_RANGE = 100000

/**
 * Cloud Messaging service 457 (`com.google.android.gms.cloudmessaging.service.START`).
 *
 * The Firebase Messaging library registers here instead of with `com.google.android.c2dm.intent.REGISTER` when the
 * app sets the `firebase_messaging_installation_id_enabled` manifest flag and Play services reports 26.12 or newer.
 * The registration names the app instance by its Firebase installation (FID); Google's FCM registrations service
 * ties it to this phone through the check-in credentials. Messages for it arrive on the same MCS connection and are
 * delivered to the app like any other push message, so the app is recorded in [GcmDatabase] as registered.
 */
class CloudMessagingService : BaseService(TAG, GmsService.CLOUD_MESSAGING) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        val packageName = PackageUtils.getAndCheckCallingPackage(this, request.packageName)
            ?: throw IllegalArgumentException("Missing package name")
        callback.onPostInitCompleteWithConnectionInfo(
            ConnectionResult.SUCCESS,
            CloudMessagingServiceImpl(this, packageName, lifecycle).asBinder(),
            ConnectionInfo().apply { features = CLOUD_MESSAGING_FEATURES }
        )
    }
}

private class CloudMessagingServiceImpl(
    private val context: Context,
    private val packageName: String,
    override val lifecycle: Lifecycle
) : ICloudMessagingService.Stub(), LifecycleOwner {

    override fun register(callback: IRegisterCallback?, request: RegisterRequest?, apiMetadata: ApiMetadata?) {
        Log.d(TAG, "register($packageName): $request")
        lifecycleScope.launch {
            val (status, token) = try {
                Status.SUCCESS to registerAppInstance(request)
            } catch (e: Exception) {
                Log.w(TAG, "register($packageName) failed: ${e.javaClass.simpleName}: ${e.message}")
                FcmRegistration.statusFor(e) to null
            }
            runCatching { callback?.onRegister(status, token, ApiMetadata.DEFAULT) }
                .onFailure { Log.w(TAG, "register($packageName): callback failed", it) }
        }
    }

    override fun unregister(callback: IStatusCallback?, request: UnregisterRequest?, apiMetadata: ApiMetadata?) {
        Log.d(TAG, "unregister($packageName): $request")
        lifecycleScope.launch {
            val status = try {
                deleteRegistration(request)
                Status.SUCCESS
            } catch (e: Exception) {
                Log.w(TAG, "unregister($packageName) failed: ${e.javaClass.simpleName}: ${e.message}")
                FcmRegistration.statusFor(e)
            }
            runCatching { callback?.onResult(status) }
                .onFailure { Log.w(TAG, "unregister($packageName): callback failed", it) }
        }
    }

    private suspend fun registerAppInstance(request: RegisterRequest?): String {
        FcmRegistration.problemWith(request)?.let { throw CloudMessagingException(CommonStatusCodes.DEVELOPER_ERROR, it) }
        request!!
        val database = GcmDatabase(context)
        try {
            // The same rules as a legacy registration (PushRegisterService): push enabled, the app allowed.
            ensureAppRegistrationAllowed(context, database, packageName)
            ensureCheckinIsUpToDate(context)
            val checkin = LastCheckinInfo.read(context)
            if (checkin.androidId == 0L || checkin.securityToken == 0L) {
                throw CloudMessagingException(CommonStatusCodes.INTERNAL_ERROR, "Missing Android ID or token")
            }
            val packageInfo = ExtendedPackageInfo(context, packageName)
            val body = FcmRegistration.registerAppInstanceRequest(
                request, packageName, (Process.myUid() / PER_USER_UID_RANGE).toLong(), Constants.GMS_VERSION_CODE.toLong(),
                android.os.Build.VERSION.SDK_INT.toLong(), packageInfo.targetSdkVersion.toLong()
            )
            val headers = FcmRegistration.registerHeaders(request, checkin.androidId, checkin.securityToken)
            val response = withContext(Dispatchers.IO) {
                createGrpcClient<AndroidRegistrationClient>(FCM_REGISTRATIONS_BASE_URL, HeadersInterceptor(headers))
                    .RegisterAppInstance().executeBlocking(body)
            }
            val token = response.token
            if (token.isNullOrEmpty()) throw CloudMessagingException(CommonStatusCodes.INTERNAL_ERROR, "Missing registration ID")
            database.noteAppRegistered(packageName, packageInfo.firstCertificateSha1Hex, token)
            return token
        } finally {
            database.close()
        }
    }

    private suspend fun deleteRegistration(request: UnregisterRequest?) {
        FcmRegistration.problemWith(request)?.let { throw CloudMessagingException(CommonStatusCodes.DEVELOPER_ERROR, it) }
        request!!
        withContext(Dispatchers.IO) {
            createGrpcClient<RegistrationApiClient>(FCM_REGISTRATIONS_BASE_URL, HeadersInterceptor(FcmRegistration.unregisterHeaders(request)))
                .DeleteRegistration().executeBlocking(FcmRegistration.deleteRegistrationRequest(request))
        }
        val database = GcmDatabase(context)
        try {
            database.noteAppUnregistered(packageName, ExtendedPackageInfo(context, packageName).firstCertificateSha1Hex)
        } finally {
            database.close()
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        warnOnTransactionIssues(code, reply, flags, TAG) { super.onTransact(code, data, reply, flags) }
}

internal class CloudMessagingException(val statusCode: Int, message: String) : Exception(message)

private class HeadersInterceptor(private val headers: Map<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        headers.forEach { (name, value) -> builder.header(name, value) }
        return chain.proceed(builder.build())
    }
}

/** The request contract of Cloud Messaging service 457 and the FCM registrations service behind it. */
internal object FcmRegistration {
    const val HEADER_API_KEY = "X-Goog-Api-Key"
    const val HEADER_INSTALLATION_AUTH = "X-Goog-Firebase-Installations-Auth"
    const val HEADER_AID_LOGIN = "X-Aid-Login"

    /** Why Play services would refuse [request] with DEVELOPER_ERROR, or null. */
    fun problemWith(request: RegisterRequest?): String? = when {
        request == null -> "Request is missing"
        request.apiKey.isNullOrEmpty() -> "API key is missing"
        request.installationAuthToken.isNullOrEmpty() -> "Installation auth token is missing"
        request.gmpAppId.isNullOrEmpty() -> "GMP app ID is missing"
        request.installationId.isNullOrEmpty() -> "Installation ID is missing"
        request.sdkVersion.isNullOrEmpty() -> "Sdk version is missing"
        request.senderId.isNullOrEmpty() -> "Sender ID is missing"
        else -> null
    }

    fun problemWith(request: UnregisterRequest?): String? = when {
        request == null -> "Request is missing"
        request.apiKey.isNullOrEmpty() -> "API key is missing"
        request.installationAuthToken.isNullOrEmpty() -> "Installation auth token is missing"
        request.projectId.isNullOrEmpty() -> "Project ID is missing"
        request.installationId.isNullOrEmpty() -> "Installation ID is missing"
        else -> null
    }

    fun registerAppInstanceRequest(
        request: RegisterRequest, packageName: String, androidUserId: Long, gmsVersionCode: Long, sdkInt: Long, targetSdk: Long
    ) = RegisterAppInstanceRequest.Builder()
        .name("projects/${request.senderId}/installations/${request.installationId}")
        .package_name(packageName)
        .android_user_id(androidUserId)
        .app_version_code(request.appVersionCode)
        .sdk_version(request.sdkVersion)
        .gms_version_code(gmsVersionCode)
        .os_sdk_int(sdkInt)
        .client(RegisteringClient.CLIENT_PLAY_SERVICES)
        .gmp_app_id(request.gmpAppId)
        .target_sdk_version(targetSdk)
        .build()

    fun deleteRegistrationRequest(request: UnregisterRequest) = DeleteRegistrationRequest.Builder()
        .name("projects/${request.projectId}/registrations/${request.installationId}")
        .build()

    fun registerHeaders(request: RegisterRequest, androidId: Long, securityToken: Long) = mapOf(
        HEADER_API_KEY to request.apiKey,
        HEADER_INSTALLATION_AUTH to request.installationAuthToken,
        HEADER_AID_LOGIN to "$androidId:$securityToken"
    )

    fun unregisterHeaders(request: UnregisterRequest) = mapOf(
        HEADER_API_KEY to request.apiKey,
        HEADER_INSTALLATION_AUTH to request.installationAuthToken
    )

    /** The status Play services reports for a failure, by gRPC status where the server answered. */
    fun statusFor(e: Throwable): Status = when (e) {
        is CloudMessagingException -> Status(e.statusCode, e.message)
        is GrpcException -> Status(statusCodeForGrpc(e.grpcStatus.code), e.grpcMessage ?: e.grpcStatus.name)
        is IOException -> Status(CommonStatusCodes.NETWORK_ERROR, e.message)
        else -> Status(CommonStatusCodes.INTERNAL_ERROR, e.message)
    }

    fun statusCodeForGrpc(grpcCode: Int): Int = when (grpcCode) {
        1 -> CommonStatusCodes.CANCELED // CANCELLED
        3 -> CommonStatusCodes.DEVELOPER_ERROR // INVALID_ARGUMENT
        4 -> CommonStatusCodes.TIMEOUT // DEADLINE_EXCEEDED
        5, 7, 12, 16 -> CommonStatusCodes.ERROR // NOT_FOUND, PERMISSION_DENIED, UNIMPLEMENTED, UNAUTHENTICATED
        14 -> CommonStatusCodes.NETWORK_ERROR // UNAVAILABLE
        else -> CommonStatusCodes.INTERNAL_ERROR
    }
}
