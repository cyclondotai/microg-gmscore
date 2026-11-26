/*
 * SPDX-FileCopyrightText: 2023 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.credential

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Parcel
import android.util.Base64
import android.util.Log
import androidx.core.app.PendingIntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import com.google.android.gms.credential.manager.EXTRA_KEY_ACCOUNT_NAME
import com.google.android.gms.credential.manager.PASSWORD_MANAGER_CLASS_NAME
import com.google.android.gms.credential.manager.common.IBooleanCallback
import com.google.android.gms.credential.manager.common.IPendingIntentCallback
import com.google.android.gms.credential.manager.common.ISettingsCallback
import com.google.android.gms.credential.manager.firstparty.internal.ICredentialManagerService
import com.google.android.gms.credential.manager.invocationparams.CredentialManagerInvocationParams
import org.microg.gms.BaseService
import org.microg.gms.common.Constants
import org.microg.gms.common.GmsService
import org.microg.gms.common.GooglePackagePermission
import org.microg.gms.common.PackageUtils
import org.microg.gms.utils.toBase64
import org.microg.gms.utils.warnOnTransactionIssues

private const val TAG = "CredentialManager"

class CredentialManagerService : BaseService(TAG, GmsService.CREDENTIAL_MANAGER) {
    override fun handleServiceRequest(callback: IGmsCallbacks, request: GetServiceRequest, service: GmsService) {
        if (!PackageUtils.callerHasGooglePackagePermission(this, GooglePackagePermission.CREDENTIALS)) {
            Log.d(TAG, "No access to ${request.packageName}, lacks permission.")
            callback.onPostInitComplete(ConnectionResult.API_DISABLED_FOR_CONNECTION, null, null)
            return
        }
        callback.onPostInitCompleteWithConnectionInfo(ConnectionResult.SUCCESS, CredentialManagerServiceImpl(this, lifecycle), ConnectionInfo().apply {
            features = arrayOf(
                Feature("credential_manager_first_party_api", 1),
                Feature("password_checkup_first_party_api", 1),
                Feature("user_service_security", 1),
                // Chrome on Play services 26.19.30+ reads and writes "use screen lock before filling passwords" here.
                Feature("credential_manager_first_party_get_biometric_reauth", 1),
                Feature("credential_manager_first_party_set_biometric_reauth", 1),
            )
        })
    }

}

private class CredentialManagerServiceImpl(private val context: Context, override val lifecycle: Lifecycle) : ICredentialManagerService.Stub(), LifecycleOwner {

    override fun getCredentialManagerIntent(callback: IPendingIntentCallback?, params: CredentialManagerInvocationParams) {
        Log.d(TAG, "Method getCredentialManagerIntent $params called")
        lifecycleScope.launchWhenStarted {
            runCatching {
                val intent = Intent().apply {
                    setClassName(Constants.GMS_PACKAGE_NAME, PASSWORD_MANAGER_CLASS_NAME)
                    params.account?.name?.let { putExtra(EXTRA_KEY_ACCOUNT_NAME, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val pendingIntent = PendingIntentCompat.getActivity(context, 0, intent, 0, false)
                callback?.onPendingIntent(Status.SUCCESS, pendingIntent)
            }.onFailure {
                Log.d(TAG, "getCredentialManagerIntent error", it)
                callback?.onPendingIntent(Status.INTERNAL_ERROR, null)
            }
        }
    }

    override fun getSetting(callback: ISettingsCallback?, key: String?) {
        Log.d(TAG, "Not yet implemented: getSetting $key")
        lifecycleScope.launchWhenStarted {
            try {
                callback?.onSetting(Status.INTERNAL_ERROR, null)
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
    }

    override fun setSetting(callback: IStatusCallback?, key: String?, value: ByteArray?) {
        Log.d(TAG, "Not yet implemented: setSetting $key ${value?.toBase64(Base64.NO_WRAP)}")
        lifecycleScope.launchWhenStarted {
            try {
                callback?.onResult(Status.INTERNAL_ERROR)
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
    }

    override fun isBiometricReauthBeforeAutofillingRequired(callback: IBooleanCallback?, apiMetadata: ApiMetadata?) {
        lifecycleScope.launchWhenStarted {
            val required = ReauthBeforeAutofilling.isRequired(context)
            Log.d(TAG, "isBiometricReauthBeforeAutofillingRequired: $required")
            runCatching { callback?.onBoolean(Status.SUCCESS, required, ApiMetadata.DEFAULT) }.onFailure { Log.w(TAG, it) }
        }
    }

    override fun setIsReauthBeforeAutofillingRequired(callback: IStatusCallback?, required: Boolean, apiMetadata: ApiMetadata?) {
        lifecycleScope.launchWhenStarted {
            val status = if (ReauthBeforeAutofilling.setRequired(context, required)) Status.SUCCESS
            else Status(8, "Failed to save 'reauth before autofilling' in storage.")
            Log.d(TAG, "setIsReauthBeforeAutofillingRequired($required): ${status.statusCode}")
            runCatching { callback?.onResult(status) }.onFailure { Log.w(TAG, it) }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = warnOnTransactionIssues(code, reply, flags, TAG) { super.onTransact(code, data, reply, flags) }
}
/**
 * The device-wide "use screen lock before filling passwords" choice Chrome shows in its password settings. As in
 * Play services, it is off until the owner turns it on, and it only applies while the phone has a screen lock.
 */
internal object ReauthBeforeAutofilling {
    private const val PREFERENCES = "credential_manager"
    private const val KEY_REQUIRED = "reauth_before_autofilling"

    fun isRequired(context: Context): Boolean =
        isRequired(context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true, stored(context))

    fun isRequired(deviceSecure: Boolean, stored: Boolean): Boolean = deviceSecure && stored

    fun setRequired(context: Context, required: Boolean): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_REQUIRED, required).commit()

    private fun stored(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_REQUIRED, false)
}
