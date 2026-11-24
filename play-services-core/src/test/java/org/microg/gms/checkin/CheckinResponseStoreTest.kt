/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.checkin

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.settings.SettingsProvider
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException

private const val SETTINGS = "com.google.android.gms.microg.settings"
private const val STORED_ID = 1234567890123456789L
private const val STORED_TOKEN = -987654321012345678L // gitleaks:allow synthetic test value
private const val NEW_ID = 555L
private const val NEW_TOKEN = 666777888L // gitleaks:allow synthetic test value

/** A check-in response reaches the settings and GServices only when it is for the identity still stored (CYC-2935). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CheckinResponseStoreTest {
    private class GServicesProvider : ContentProvider() {
        val updates = mutableListOf<String>()
        override fun onCreate() = true
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            updates += values!!.getAsString("name")
            return 1
        }
        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    private lateinit var context: Context
    private val gservices = GServicesProvider()

    @Before fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getPackageName() = "com.google.android.gms"
            override fun getApplicationContext(): Context = this
        }
        val settings = Robolectric.buildContentProvider(SettingsProvider::class.java)
            .create(ProviderInfo().apply { authority = SETTINGS }).get()
        ShadowContentResolver.registerProviderInternal(SETTINGS, settings)
        gservices.attachInfo(context, ProviderInfo().apply { authority = "com.google.android.gsf.gservices" })
        ShadowContentResolver.registerProviderInternal("com.google.android.gsf.gservices", gservices)
    }

    private fun response() = CheckinResponse.Builder()
        .androidId(NEW_ID)
        .securityToken(NEW_TOKEN)
        .digest("1-new")
        .timeMs(1_800_000_000_000L)
        .versionInfo("version")
        .deviceDataVersionInfo("device-version")
        .setting(listOf(CheckinResponse.GservicesSetting.Builder()
            .name("key".encodeUtf8()).value_("value".encodeUtf8()).build()))
        .build()

    @Test fun aResponseForTheStoredIdentityIsSavedAndApplied() {
        val info = CheckinManager.handleResponse(context, response(), 0L)
        assertNotNull(info)
        assertEquals(NEW_ID, LastCheckinInfo.read(context).androidId)
        assertEquals(listOf("key"), gservices.updates)
    }

    @Test fun aStaleResponseIsDroppedWithItsSettings() {
        LastCheckinInfo(1L, STORED_ID, STORED_TOKEN, "1-restored", "v", "d").write(context)
        assertNull(CheckinManager.handleResponse(context, response(), 0L))
        val stored = LastCheckinInfo.read(context)
        assertEquals(STORED_ID, stored.androidId)
        assertEquals(STORED_TOKEN, stored.securityToken)
        assertEquals("1-restored", stored.digest)
        assertTrue(gservices.updates.isEmpty())
    }

    @Test fun anUnsavedResponseIsAFailedCheckinAndAppliesNoSettings() {
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val real = super.getSharedPreferences(name, mode)
                if (name != "checkin") return real
                return object : SharedPreferences by real {
                    // As SharedPreferencesImpl does: the failed commit is already in memory.
                    override fun edit(): SharedPreferences.Editor {
                        val editor = real.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                editor.commit()
                                return false
                            }
                        }
                    }
                }
            }
        }
        val settings = SettingsProvider().also { it.attachInfo(failing, ProviderInfo().apply { authority = SETTINGS }) }
        ShadowContentResolver.registerProviderInternal(SETTINGS, settings)

        val failure = assertThrows(IOException::class.java) { CheckinManager.handleResponse(context, response(), 0L) }
        assertFalse(failure.message!!.contains(NEW_TOKEN.toString()))
        assertFalse(failure.message!!.contains(NEW_ID.toString()))
        assertTrue(gservices.updates.isEmpty())
        assertEquals(0L, LastCheckinInfo.read(context).androidId)
        assertEquals(0L, LastCheckinInfo.read(context).securityToken)
    }
}
