/*
 * SPDX-FileCopyrightText: 2025 e foundation
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.checkin

import android.content.ContentValues
import android.content.Context
import org.microg.gms.settings.SettingsContract

data class LastCheckinInfo(
    val lastCheckin: Long,
    val androidId: Long,
    val securityToken: Long,
    val digest: String,
    val versionInfo: String,
    val deviceDataVersionInfo: String,
) {

    companion object {
        @JvmStatic
        fun read(context: Context): LastCheckinInfo {
            val projection = arrayOf(
                SettingsContract.CheckIn.ANDROID_ID,
                SettingsContract.CheckIn.DIGEST,
                SettingsContract.CheckIn.LAST_CHECK_IN,
                SettingsContract.CheckIn.SECURITY_TOKEN,
                SettingsContract.CheckIn.VERSION_INFO,
                SettingsContract.CheckIn.DEVICE_DATA_VERSION_INFO,
            )
            return SettingsContract.getSettings(
                context,
                SettingsContract.CheckIn.getContentUri(context),
                projection
            ) { c ->
                LastCheckinInfo(
                    androidId = c.getLong(0),
                    digest = c.getString(1),
                    lastCheckin = c.getLong(2),
                    securityToken = c.getLong(3),
                    versionInfo = c.getString(4),
                    deviceDataVersionInfo = c.getString(5),
                )
            }
        }

        @JvmStatic
        fun clear(context: Context) =
            SettingsContract.setSettings(context, SettingsContract.CheckIn.getContentUri(context)) {
                put(SettingsContract.CheckIn.ANDROID_ID, 0L)
                put(SettingsContract.CheckIn.DIGEST, SettingsContract.CheckIn.INITIAL_DIGEST)
                put(SettingsContract.CheckIn.LAST_CHECK_IN, 0L)
                put(SettingsContract.CheckIn.SECURITY_TOKEN, 0L)
                put(SettingsContract.CheckIn.VERSION_INFO, "")
                put(SettingsContract.CheckIn.DEVICE_DATA_VERSION_INFO, "")
                putNull(SettingsContract.CheckIn.RESTORE_MARKER)
            }

        /** Cyclon: the marker of a factory-baseline restore that has not finished, or null (CYC-2935). */
        @JvmStatic
        fun restoreMarker(context: Context): String? = SettingsContract.getSettings(
            context,
            SettingsContract.CheckIn.getContentUri(context),
            arrayOf(SettingsContract.CheckIn.RESTORE_MARKER),
        ) { c -> c.getString(0) }

        @JvmStatic
        fun clearRestoreMarker(context: Context) =
            SettingsContract.setSettingsDurably(context, SettingsContract.CheckIn.getContentUri(context)) {
                putNull(SettingsContract.CheckIn.RESTORE_MARKER)
            }
    }

    fun write(context: Context) =
        SettingsContract.setSettings(context, SettingsContract.CheckIn.getContentUri(context)) { putIdentity() }

    /** Cyclon: this identity and its restore marker in one commit, so neither can be on disk without the other. */
    fun write(context: Context, restoreMarker: String) =
        SettingsContract.setSettingsDurably(context, SettingsContract.CheckIn.getContentUri(context)) {
            putIdentity()
            put(SettingsContract.CheckIn.RESTORE_MARKER, restoreMarker)
        }

    private fun ContentValues.putIdentity() {
        put(SettingsContract.CheckIn.ANDROID_ID, androidId)
        put(SettingsContract.CheckIn.DIGEST, digest)
        put(SettingsContract.CheckIn.LAST_CHECK_IN, lastCheckin)
        put(SettingsContract.CheckIn.SECURITY_TOKEN, securityToken)
        put(SettingsContract.CheckIn.VERSION_INFO, versionInfo)
        put(SettingsContract.CheckIn.DEVICE_DATA_VERSION_INFO, deviceDataVersionInfo)
    }
}
