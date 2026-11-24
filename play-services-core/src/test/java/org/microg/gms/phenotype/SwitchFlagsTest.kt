/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import android.content.ContentValues
import android.content.pm.Signature
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.phenotype.Configurations
import com.google.android.gms.phenotype.Flag
import com.google.android.gms.phenotype.internal.IPhenotypeCallbacks
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SwitchFlagsTest {
    private val provider get() = Robolectric.buildContentProvider(ConfigurationProvider::class.java).create().get()
    private val namespace = "com.google.android.apps.pixelmigrate"
    private val flagName = SwitchFlags.RESTORE_TO_GOOGLE
    private val limitName = SwitchFlags.IOS_APP_MATCHING_LIMIT
    private val iosAppFilesName = SwitchFlags.IOS_APP_FILES_D2D
    private val iosAppAllowlistName = SwitchFlags.IOS_APP_ALLOWLIST
    private val filterNoAccountName = SwitchFlags.FILTER_IOS_WHATSAPP_WHEN_NO_ACCOUNT
    private val recordingTransferName = SwitchFlags.IOS_ENABLE_RECORDING_TRANSFER
    private val voiceMemosAllowlistName = SwitchFlags.IOS_VOICE_MEMOS_READER_ALLOWLIST

    private val expectedRows = linkedMapOf(
        SwitchFlags.RESTORE_TO_GOOGLE to "true",
        SwitchFlags.IOS_APP_MATCHING_LIMIT to "1000",
        SwitchFlags.IOS_APP_FILES_D2D to "true",
        SwitchFlags.IOS_APP_ALLOWLIST to SwitchFlags.IOS_APP_ALLOWLIST_BASE64,
        SwitchFlags.FILTER_IOS_WHATSAPP_WHEN_NO_ACCOUNT to "false",
        SwitchFlags.IOS_ENABLE_RECORDING_TRANSFER to "true",
        SwitchFlags.IOS_VOICE_MEMOS_READER_ALLOWLIST to SwitchFlags.IOS_VOICE_MEMOS_READER_ALLOWLIST_BASE64,
        SwitchFlags.BACKUP_SHOULD_DECRYPT_IOS_BACKUP to "true",
        SwitchFlags.FASTER_INDEXING_SKIP_FULL_BACKUP_FOR_ENCRYPTED to "true",
        SwitchFlags.FASTER_INDEXING_SUPPORT_APP_DATA_FOR_ENCRYPTED to "true",
        SwitchFlags.FASTER_INDEXING_EXCLUDE_LIVE_PHOTO to "true",
        SwitchFlags.FASTER_INDEXING_DISABLE_ON_MDM to "true",
        SwitchFlags.TRANSFER_WIFI_WITH_PASSWORD to "false",
        SwitchFlags.IOS_ENABLE_SCREENSHOT_CABLE_D2D to "true",
        SwitchFlags.IOS_ENABLE_SCREENSHOT_WIFI_D2D to "true",
        SwitchFlags.SKIP_QUERY_BACKUP_SIZE_IOS_16_1 to "true",
        SwitchFlags.COMPATIBLE_DOCUMENTS_BATCH_SIZE to "150",
        SwitchFlags.SETUP_DOCUMENTS_BATCH_SIZE to "150",
        SwitchFlags.ENABLE_HANDSHAKE_TIMEOUT_FIX to "true",
        SwitchFlags.LIMIT_CABLE_CONNECTION_FOR_FLAKY_CABLE to "true",
        SwitchFlags.ADD_CACHED_PACKAGES_TO_TRANSFERRED to "true",
        SwitchFlags.ENABLE_DUPLEX_TRANSFER_ANDROID_D2D to "true",
        SwitchFlags.FILTER_DUPLICATE_MEDIA_IN_INDEXING to "true",
        SwitchFlags.IOS_USE_QRCODES_LIBRARY to "true",
        SwitchFlags.RESTORE_SYNCED to "true",
        SwitchFlags.TREAT_APPS_AS_NOT_COPIED_WITHOUT_GOOGLE_ACCOUNT to "false",
        SwitchFlags.IOS_ENABLE_DOWNLOADS_CABLE_D2D to "true",
        SwitchFlags.BACKUP_ENABLE_DOCUMENTS_FLAVOR_D2D to "true",
    )

    private val uri get() = Uri.parse("content://com.google.android.gms.phenotype/$namespace")

    private fun rows(provider: ConfigurationProvider, uri: Uri = this.uri): Map<String, String> =
        provider.query(uri, null, null, null, null)!!.use { cursor ->
            val result = linkedMapOf<String, String>()
            while (cursor.moveToNext()) result[cursor.getString(0)] = cursor.getString(1)
            result
        }

    private fun callback(code: Int, read: (Parcel) -> Unit): IPhenotypeCallbacks =
        IPhenotypeCallbacks.Stub.asInterface(object : Binder() {
            override fun onTransact(transaction: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.google.android.gms.phenotype.internal.IPhenotypeCallbacks")
                assertEquals(code, transaction)
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(1, data.readInt())
                assertEquals(0, Status.CREATOR.createFromParcel(data).statusCode)
                read(data)
                assertEquals(0, data.dataAvail())
                return true
            }
        })

    private fun readVarint(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var pos = offset
        while (true) {
            val b = bytes[pos++].toInt() and 0xff
            result = result or ((b and 0x7f) shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
        }
    }

    private fun parseFields(bytes: ByteArray): List<Pair<Int, Any>> {
        val fields = mutableListOf<Pair<Int, Any>>()
        var i = 0
        while (i < bytes.size) {
            val (tag, nextPos) = readVarint(bytes, i)
            i = nextPos
            val fieldNum = tag ushr 3
            when (tag and 0x7) {
                0 -> {
                    val (v, afterVal) = readVarint(bytes, i)
                    fields += fieldNum to v
                    i = afterVal
                }
                2 -> {
                    val (len, afterLen) = readVarint(bytes, i)
                    fields += fieldNum to bytes.copyOfRange(afterLen, afterLen + len)
                    i = afterLen + len
                }
                else -> fail("Unexpected wire type for tag $tag")
            }
        }
        return fields
    }

    @Test fun directProviderReadIncludesAllBaselineAllowlistsAndExcludesDisabledFlags() {
        val actual = rows(provider)
        assertEquals(expectedRows, actual)

        // Forbidden / intentionally disabled flags must not be enabled.
        for (forbidden in listOf(
            "Ios__enable_clock_caller_identity_intent_extra",
            "Ios__enable_cable_edited_photos",
            "Ios__enable_live_photo_cable_d2d",
            "Ios__enable_live_photo_wifi_d2d",
            "Ios__enable_burst_photo_cable_d2d",
            "Ios__enable_burst_photo_wifi_d2d",
            "Ios__backup_create_photo_folders",
            "Ios__enable_unified_contact_cable_d2d",
            "Ios__support_additional_message_data",
            "Ios__migrate_url_preview_messages",
            "Eu10__backup_ios_restore_message_attachments_from_encrypted_device",
        )) {
            assertFalse("$forbidden must not be enabled", actual[forbidden] == "true")
        }

        // Decode outer imf { repeated ime entries(1) }
        val decodedAllowlist = android.util.Base64.decode(actual.getValue(iosAppAllowlistName), 3)
        assertArrayEquals(Base64.getDecoder().decode(SwitchFlags.IOS_APP_ALLOWLIST_BASE64), decodedAllowlist)
        val entries = parseFields(decodedAllowlist).map { (fieldNum, raw) ->
            assertEquals(1, fieldNum)
            parseFields(raw as ByteArray).associate { (num, value) ->
                num to if (value is ByteArray) String(value, Charsets.UTF_8) else value
            }
        }
        assertEquals(2, entries.size)
        assertEquals(
            mapOf(
                1 to "com.whatsapp",
                2 to "net.whatsapp.WhatsApp",
                3 to "group.net.whatsapp.WhatsApp.shared",
                4 to 9,
            ),
            entries[0],
        )
        assertEquals(
            mapOf(
                1 to "ai.cyclon.migration",
                2 to "com.apple.VoiceMemos",
                3 to "group.com.apple.VoiceMemos.shared",
                4 to 13,
            ),
            entries[1],
        )
    }

    @Test fun voiceMemosReaderAllowlistDecodesBothCyclonPlatformDerHexCertificates() {
        val actual = rows(provider)
        val decoded = android.util.Base64.decode(actual.getValue(voiceMemosAllowlistName), 3)
        assertArrayEquals(Base64.getDecoder().decode(SwitchFlags.IOS_VOICE_MEMOS_READER_ALLOWLIST_BASE64), decoded)

        // Decode ioo { repeated ion entries(1) { name(1), repeated signatures(2) } }
        val outer = parseFields(decoded)
        assertEquals(1, outer.size)
        assertEquals(1, outer[0].first)
        val ionFields = parseFields(outer[0].second as ByteArray)
        val names = ionFields.filter { it.first == 1 }.map { String(it.second as ByteArray, Charsets.UTF_8) }
        val signatures = ionFields.filter { it.first == 2 }.map { String(it.second as ByteArray, Charsets.UTF_8) }

        assertEquals(listOf("ai.cyclon.migration"), names)
        assertEquals(
            listOf(
                SwitchFlags.CYCLON_RELEASE_PLATFORM_CERT_DER_HEX,
                SwitchFlags.CYCLON_DEV_PLATFORM_CERT_DER_HEX,
            ),
            signatures,
        )
        assertEquals(2, signatures.distinct().size)

        // Verify both signatures are valid lowercase DER hex accepted by android.content.pm.Signature(String)
        // (matching Switch AppDataReaderService.java:136) and parse as Cyclon platform X.509 certificates.
        val certFactory = CertificateFactory.getInstance("X.509")
        val subjects = signatures.map { hex ->
            assertTrue("Expected lowercase DER hex", hex.matches(Regex("[0-9a-f]+")))
            assertTrue("Expected full DER X.509 cert, not SHA-256 hash", hex.length > 1000)
            val sig = Signature(hex)
            val cert = certFactory.generateCertificate(ByteArrayInputStream(sig.toByteArray())) as X509Certificate
            cert.subjectX500Principal.name
        }
        assertTrue(subjects[0].contains("Cyclon platform"))
        assertTrue(subjects[1].contains("Cyclon dev"))
    }

    @Test fun foreignAndMalformedNamespacesDoNotReceiveContactFlags() {
        val provider = provider
        listOf("foreign.package", "$namespace#com.google.android.apps.restore", "$namespace.other").forEach {
            assertNull(SwitchFlags.rows(it))
            assertNull(SwitchFlags.flags(it))
            assertTrue(rows(provider, Uri.parse("content://com.google.android.gms.phenotype/$it")).isEmpty())
        }
        listOf("$uri/extra", "$uri?flag=$flagName", "$uri#fragment",
            "content://foreign.authority/$namespace").forEach {
            assertTrue(rows(provider, Uri.parse(it)).isEmpty())
        }
    }

    @Test fun snapshotAndCommittedServiceReadsAgreeWithTheProviderOnTypedValues() {
        val service = PhenotypeServiceImpl("com.google.android.apps.restore")
        val expected = rows(provider)
        val expectedAllowlist = Base64.getDecoder().decode(SwitchFlags.IOS_APP_ALLOWLIST_BASE64)
        val expectedVoiceMemos = Base64.getDecoder().decode(SwitchFlags.IOS_VOICE_MEMOS_READER_ALLOWLIST_BASE64)
        listOf(false, true).forEach { committed ->
            var actual: Configurations? = null
            val callback = callback(if (committed) 10 else 4) { data ->
                assertEquals(1, data.readInt())
                actual = Configurations.CREATOR.createFromParcel(data)
            }
            if (committed) service.getCommitedConfiguration(callback, namespace)
            else service.getConfigurationSnapshot(callback, namespace, null)
            val configuration = requireNotNull(actual).field4.single()
            assertEquals(0, configuration.id)
            assertTrue(configuration.removeNames.isEmpty())
            val flags = configuration.flags.associateBy { it.name }
            assertEquals(expected.keys, flags.keys)
            flags.values.forEach { assertEquals(0, it.flagType) }
            assertEquals(Flag.DATA_TYPE_BOOL, flags.getValue(flagName).dataType)
            assertTrue(flags.getValue(flagName).bool)
            assertEquals(Flag.DATA_TYPE_LONG, flags.getValue(limitName).dataType)
            assertEquals(1000L, flags.getValue(limitName).long)
            assertEquals(Flag.DATA_TYPE_BOOL, flags.getValue(iosAppFilesName).dataType)
            assertTrue(flags.getValue(iosAppFilesName).bool)
            assertEquals(Flag.DATA_TYPE_BYTES, flags.getValue(iosAppAllowlistName).dataType)
            assertArrayEquals(expectedAllowlist, flags.getValue(iosAppAllowlistName).bytes)
            assertEquals(Flag.DATA_TYPE_BOOL, flags.getValue(filterNoAccountName).dataType)
            assertFalse(flags.getValue(filterNoAccountName).bool)
            assertEquals(Flag.DATA_TYPE_BOOL, flags.getValue(recordingTransferName).dataType)
            assertTrue(flags.getValue(recordingTransferName).bool)
            assertEquals(Flag.DATA_TYPE_BYTES, flags.getValue(voiceMemosAllowlistName).dataType)
            assertArrayEquals(expectedVoiceMemos, flags.getValue(voiceMemosAllowlistName).bytes)
            assertEquals(Flag.DATA_TYPE_LONG, flags.getValue(SwitchFlags.COMPATIBLE_DOCUMENTS_BATCH_SIZE).dataType)
            assertEquals(150L, flags.getValue(SwitchFlags.COMPATIBLE_DOCUMENTS_BATCH_SIZE).long)
            assertEquals(Flag.DATA_TYPE_LONG, flags.getValue(SwitchFlags.SETUP_DOCUMENTS_BATCH_SIZE).dataType)
            assertEquals(150L, flags.getValue(SwitchFlags.SETUP_DOCUMENTS_BATCH_SIZE).long)
            assertFalse(flags.getValue(SwitchFlags.TRANSFER_WIFI_WITH_PASSWORD).bool)
            assertFalse(flags.getValue(SwitchFlags.TREAT_APPS_AS_NOT_COPIED_WITHOUT_GOOGLE_ACCOUNT).bool)
            assertTrue(flags.getValue(SwitchFlags.IOS_ENABLE_DOWNLOADS_CABLE_D2D).bool)
            assertTrue(flags.getValue(SwitchFlags.BACKUP_ENABLE_DOCUMENTS_FLAVOR_D2D).bool)
        }
    }

    @Test fun individualFlagReadMatchesOnlyTheSwitchNamespaceAndFlags() {
        val service = PhenotypeServiceImpl("com.google.android.apps.restore")
        listOf(
            Triple(namespace, flagName, true),
            Triple(namespace, "other_flag", false),
            Triple("foreign.package", flagName, false),
        ).forEach { (requestedNamespace, requestedFlag, present) ->
            var called = false
            service.getFlag(callback(9) { data ->
                called = true
                if (present) {
                    assertEquals(1, data.readInt())
                    val flag = Flag.CREATOR.createFromParcel(data)
                    assertEquals(flagName, flag.name)
                    assertEquals(Flag.DATA_TYPE_BOOL, flag.dataType)
                    assertTrue(flag.bool)
                } else assertEquals(0, data.readInt())
            }, requestedNamespace, requestedFlag, Flag.DATA_TYPE_BOOL)
            assertTrue(called)
        }
    }

    @Test fun destinationConfigurationCannotBeWrittenThroughTheProvider() {
        val provider = provider
        val values = ContentValues().apply { put("name", flagName); put("value", false) }
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri, values) }
        assertThrows(IllegalArgumentException::class.java) { provider.update(uri, values, null, null) }
        assertThrows(IllegalArgumentException::class.java) { provider.delete(uri, null, null) }
        assertNull(provider.call("setFlagOverrides", namespace, null))
        assertEquals(expectedRows, rows(provider))
    }

    @Test fun individualFlagReadReturnsTheRaisedAppMatchingLimitAndAllowlists() {
        val service = PhenotypeServiceImpl("com.google.android.apps.restore")
        var limitCalled = false
        service.getFlag(callback(9) { data ->
            limitCalled = true
            assertEquals(1, data.readInt())
            val flag = Flag.CREATOR.createFromParcel(data)
            assertEquals(limitName, flag.name)
            assertEquals(Flag.DATA_TYPE_LONG, flag.dataType)
            assertEquals(1000L, flag.long)
        }, namespace, limitName, Flag.DATA_TYPE_LONG)
        assertTrue(limitCalled)

        var allowlistCalled = false
        service.getFlag(callback(9) { data ->
            allowlistCalled = true
            assertEquals(1, data.readInt())
            val flag = Flag.CREATOR.createFromParcel(data)
            assertEquals(iosAppAllowlistName, flag.name)
            assertEquals(Flag.DATA_TYPE_BYTES, flag.dataType)
            assertArrayEquals(Base64.getDecoder().decode(SwitchFlags.IOS_APP_ALLOWLIST_BASE64), flag.bytes)
        }, namespace, iosAppAllowlistName, Flag.DATA_TYPE_BYTES)
        assertTrue(allowlistCalled)

        var voiceMemosCalled = false
        service.getFlag(callback(9) { data ->
            voiceMemosCalled = true
            assertEquals(1, data.readInt())
            val flag = Flag.CREATOR.createFromParcel(data)
            assertEquals(voiceMemosAllowlistName, flag.name)
            assertEquals(Flag.DATA_TYPE_BYTES, flag.dataType)
            assertArrayEquals(Base64.getDecoder().decode(SwitchFlags.IOS_VOICE_MEMOS_READER_ALLOWLIST_BASE64), flag.bytes)
        }, namespace, voiceMemosAllowlistName, Flag.DATA_TYPE_BYTES)
        assertTrue(voiceMemosCalled)
    }
}
