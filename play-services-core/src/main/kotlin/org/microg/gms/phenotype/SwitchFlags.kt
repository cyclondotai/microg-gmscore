/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import com.google.android.gms.phenotype.Flag
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Local Switch configuration, not a server rollout assignment or a sync-mode change. */
object SwitchFlags {
    const val NAMESPACE = "com.google.android.apps.pixelmigrate"

    // Existing Cyclon deltas and core iOS app-files flags
    const val RESTORE_TO_GOOGLE = "D2d__enable_contacts_restore_to_google_account"
    const val IOS_APP_MATCHING_LIMIT = "backup_ios_app_matching_service_request_apps_limit"
    const val IOS_APP_MATCHING_LIMIT_VALUE = 1000L
    const val IOS_APP_FILES_D2D = "IosAppFilesD2d__enable_ios_app_files_d2d"
    const val IOS_APP_ALLOWLIST = "IosAppFilesD2d__app_allowlist"
    const val FILTER_IOS_WHATSAPP_WHEN_NO_ACCOUNT = "Ios__filter_ios_whatsapp_when_no_account"

    // Voice Memos (IOS_RECORDINGS)
    const val IOS_ENABLE_RECORDING_TRANSFER = "Ios__enable_recording_transfer"
    const val IOS_VOICE_MEMOS_READER_ALLOWLIST = "IosAppFilesD2d__voice_memos_reader_allowlist"

    // Google live production baseline flags (com.google.android.apps.pixelmigrate)
    const val BACKUP_SHOULD_DECRYPT_IOS_BACKUP = "backup_should_decrypt_ios_backup"
    const val FASTER_INDEXING_SKIP_FULL_BACKUP_FOR_ENCRYPTED =
        "FasterIndexingTime__backup_ios_skip_full_backup_during_indexing_for_encrypted_device"
    const val FASTER_INDEXING_SUPPORT_APP_DATA_FOR_ENCRYPTED =
        "FasterIndexingTime__backup_ios_support_app_data_migration_for_encrypted_device"
    const val FASTER_INDEXING_EXCLUDE_LIVE_PHOTO =
        "FasterIndexingTime__backup_ios_exclude_live_photo_for_shorten_indexing"
    const val FASTER_INDEXING_DISABLE_ON_MDM =
        "FasterIndexingTime__disable_faster_indexing_when_device_management_is_presented"
    const val TRANSFER_WIFI_WITH_PASSWORD =
        "Eu10__backup_ios_transfer_wifi_networks_with_password"
    const val IOS_ENABLE_SCREENSHOT_CABLE_D2D = "Ios__enable_screenshot_cable_d2d"
    const val IOS_ENABLE_SCREENSHOT_WIFI_D2D = "Ios__enable_screenshot_wifi_d2d"
    const val SKIP_QUERY_BACKUP_SIZE_IOS_16_1 = "Sta0123__skip_query_backup_size_for_ios_16_1"
    const val COMPATIBLE_DOCUMENTS_BATCH_SIZE = "Eu14__backup_get_compatible_documents_batch_size"
    const val SETUP_DOCUMENTS_BATCH_SIZE = "Eu14__backup_setup_documents_batch_size"
    const val DOCUMENTS_BATCH_SIZE_VALUE = 150L
    const val ENABLE_HANDSHAKE_TIMEOUT_FIX = "D2d__enable_handshake_timeout_fix"
    const val LIMIT_CABLE_CONNECTION_FOR_FLAKY_CABLE = "D2d__limit_cable_connection_for_flaky_cable"
    const val ADD_CACHED_PACKAGES_TO_TRANSFERRED = "D2d__add_cached_packages_to_successfully_transferred_list"
    const val ENABLE_DUPLEX_TRANSFER_ANDROID_D2D = "D2d__enable_duplex_transfer_feature_for_android_d2d"
    const val FILTER_DUPLICATE_MEDIA_IN_INDEXING = "RestoreAnytime__filter_duplicate_media_in_indexing"
    const val IOS_USE_QRCODES_LIBRARY = "Ios__use_qrcodes_library"
    const val RESTORE_SYNCED = "restore__synced"
    const val TREAT_APPS_AS_NOT_COPIED_WITHOUT_GOOGLE_ACCOUNT =
        "OsMigration__treat_apps_as_not_copied_without_google_account"

    // Approved non-media file transfer flags & clock bridge caller identity
    const val IOS_ENABLE_DOWNLOADS_CABLE_D2D = "Ios__enable_downloads_cable_d2d"
    const val BACKUP_ENABLE_DOCUMENTS_FLAVOR_D2D = "D2d__backup_enable_documents_flavor_in_d2d_migrate"
    const val IOS_ENABLE_CLOCK_CALLER_IDENTITY_INTENT_EXTRA = "Ios__enable_clock_caller_identity_intent_extra"

    // Cyclon platform signing certificates (DER base64 -> lowercase hex) for Voice Memos reader auth.
    // Switch 1140078 AppDataReaderService.java:136 passes ion.signatures(2) directly to
    // android.content.pm.Signature(String), which expects full DER-encoded X.509 cert hex.
    private const val CYCLON_RELEASE_PLATFORM_CERT_DER_BASE64 =
        "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" +
        "jQOy49NQzVy5kI4="

    private const val CYCLON_DEV_PLATFORM_CERT_DER_BASE64 =
        "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" +
        "AMa7S0347cyU+Tv+rV1u2ww="

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    private fun toLowerHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(out)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var v = value
        while ((v and 0x7f.inv()) != 0) {
            write((v and 0x7f) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    private fun ByteArrayOutputStream.writeFieldBytes(fieldNumber: Int, data: ByteArray) {
        writeVarint((fieldNumber shl 3) or 2)
        writeVarint(data.size)
        write(data)
    }

    private fun ByteArrayOutputStream.writeFieldString(fieldNumber: Int, value: String) =
        writeFieldBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    private fun ByteArrayOutputStream.writeFieldVarint(fieldNumber: Int, value: Int) {
        writeVarint((fieldNumber shl 3) or 0)
        writeVarint(value)
    }

    private fun encodeAppEntry(
        androidPackage: String,
        bundleId: String,
        groupContainer: String,
        flavor: Int,
    ): ByteArray = ByteArrayOutputStream().apply {
        writeFieldString(1, androidPackage)
        writeFieldString(2, bundleId)
        writeFieldString(3, groupContainer)
        writeFieldVarint(4, flavor)
    }.toByteArray()

    private fun encodeVoiceMemosReaderEntry(packageName: String, certDerHexes: List<String>): ByteArray =
        ByteArrayOutputStream().apply {
            writeFieldString(1, packageName)
            for (hex in certDerHexes) writeFieldString(2, hex)
        }.toByteArray()

    internal val CYCLON_RELEASE_PLATFORM_CERT_DER_HEX: String =
        toLowerHex(Base64.getDecoder().decode(CYCLON_RELEASE_PLATFORM_CERT_DER_BASE64))

    internal val CYCLON_DEV_PLATFORM_CERT_DER_HEX: String =
        toLowerHex(Base64.getDecoder().decode(CYCLON_DEV_PLATFORM_CERT_DER_BASE64))

    // Switch 1140078 ren.java:22,24 defaults IosAppFilesD2d__enable_ios_app_files_d2d to false
    // and IosAppFilesD2d__app_allowlist (imf proto with repeated ime entries) to empty.
    // Protobuf imf { entries(1): [
    //   ime { android_package(1)="com.whatsapp", bundle_id(2)="net.whatsapp.WhatsApp",
    //         group_container(3)="group.net.whatsapp.WhatsApp.shared", flavor(4)=IOS_WHATSAPP(9) },
    //   ime { android_package(1)="ai.cyclon.migration", bundle_id(2)="com.apple.VoiceMemos",
    //         group_container(3)="group.com.apple.VoiceMemos.shared", flavor(4)=IOS_RECORDINGS(13) }
    // ] }.
    // UsbD2dMigrateFlowActivity.aH() skips IOS_RECORDINGS and only matches ime.c for non-recording
    // entries (WhatsApp); dka.java:127 branches on bundle_id == "com.apple.VoiceMemos" and installs
    // RecordingRestore (dof.java) regardless of whether Google Recorder is installed.
    // Switch reads proto flags from ConfigurationProvider via Base64.decode(str, NO_PADDING|NO_WRAP)
    // (lla.java) and from PhenotypeService as Flag.DATA_TYPE_BYTES.
    private val IOS_APP_ALLOWLIST_BYTES: ByteArray = ByteArrayOutputStream().apply {
        writeFieldBytes(
            1,
            encodeAppEntry(
                androidPackage = "com.whatsapp",
                bundleId = "net.whatsapp.WhatsApp",
                groupContainer = "group.net.whatsapp.WhatsApp.shared",
                flavor = 9,
            ),
        )
        writeFieldBytes(
            1,
            encodeAppEntry(
                androidPackage = "ai.cyclon.migration",
                bundleId = "com.apple.VoiceMemos",
                groupContainer = "group.com.apple.VoiceMemos.shared",
                flavor = 13,
            ),
        )
    }.toByteArray()

    val IOS_APP_ALLOWLIST_BASE64: String =
        Base64.getEncoder().withoutPadding().encodeToString(IOS_APP_ALLOWLIST_BYTES)

    // Protobuf ioo { entries(1): ion { name(1)="ai.cyclon.migration",
    //   signatures(2)=[release DER hex, dev DER hex] } }.
    // RecordingRestore (dof.java:679-691) writes these entries to migration/metadata.json;
    // AppDataReaderService.java:136 verifies callers via new Signature(dikVar.c).
    private val IOS_VOICE_MEMOS_READER_ALLOWLIST_BYTES: ByteArray = ByteArrayOutputStream().apply {
        writeFieldBytes(
            1,
            encodeVoiceMemosReaderEntry(
                packageName = "ai.cyclon.migration",
                certDerHexes = listOf(
                    CYCLON_RELEASE_PLATFORM_CERT_DER_HEX,
                    CYCLON_DEV_PLATFORM_CERT_DER_HEX,
                ),
            ),
        )
    }.toByteArray()

    val IOS_VOICE_MEMOS_READER_ALLOWLIST_BASE64: String =
        Base64.getEncoder().withoutPadding().encodeToString(IOS_VOICE_MEMOS_READER_ALLOWLIST_BYTES)

    // Switch 1140078 rct.java:164 defaults contacts-to-Google off; dou.java:2469 then passes no
    // account to the iPhone writer despite ecl.java:748-787 displaying a Google account.
    //
    // Switch 1140078 eew.java:157-160 sends only the first IOS_APP_MATCHING_LIMIT iPhone
    // bundle IDs (default 200) to appmapping in a single request and never sends the rest.
    //
    // Switch 1140078 res.java:118 / dlr.java:35 hides IOS_WHATSAPP when no Google account is
    // chosen unless Ios__filter_ios_whatsapp_when_no_account is false; rfb.java:42 defaults
    // OsMigration__treat_apps_as_not_copied_without_google_account to true.
    //
    // Do NOT enable edited/live/burst photo flags, photo folders, unified contact cable,
    // Google Messages-only flags, or obsolete encrypted message attachment flag.
    // Ios__enable_clock_caller_identity_intent_extra is enabled so Switch targets Cyclon's
    // com.google.android.deskclock alarm bridge and attaches CALLER_IDENTITY for authentication.
    private fun values(namespace: String?): Map<String, Any>? =
        if (namespace == NAMESPACE) linkedMapOf(
            RESTORE_TO_GOOGLE to true,
            IOS_APP_MATCHING_LIMIT to IOS_APP_MATCHING_LIMIT_VALUE,
            IOS_APP_FILES_D2D to true,
            IOS_APP_ALLOWLIST to IOS_APP_ALLOWLIST_BYTES,
            FILTER_IOS_WHATSAPP_WHEN_NO_ACCOUNT to false,
            IOS_ENABLE_RECORDING_TRANSFER to true,
            IOS_VOICE_MEMOS_READER_ALLOWLIST to IOS_VOICE_MEMOS_READER_ALLOWLIST_BYTES,
            BACKUP_SHOULD_DECRYPT_IOS_BACKUP to true,
            FASTER_INDEXING_SKIP_FULL_BACKUP_FOR_ENCRYPTED to true,
            FASTER_INDEXING_SUPPORT_APP_DATA_FOR_ENCRYPTED to true,
            FASTER_INDEXING_EXCLUDE_LIVE_PHOTO to true,
            FASTER_INDEXING_DISABLE_ON_MDM to true,
            TRANSFER_WIFI_WITH_PASSWORD to false,
            IOS_ENABLE_SCREENSHOT_CABLE_D2D to true,
            IOS_ENABLE_SCREENSHOT_WIFI_D2D to true,
            SKIP_QUERY_BACKUP_SIZE_IOS_16_1 to true,
            COMPATIBLE_DOCUMENTS_BATCH_SIZE to DOCUMENTS_BATCH_SIZE_VALUE,
            SETUP_DOCUMENTS_BATCH_SIZE to DOCUMENTS_BATCH_SIZE_VALUE,
            ENABLE_HANDSHAKE_TIMEOUT_FIX to true,
            LIMIT_CABLE_CONNECTION_FOR_FLAKY_CABLE to true,
            ADD_CACHED_PACKAGES_TO_TRANSFERRED to true,
            ENABLE_DUPLEX_TRANSFER_ANDROID_D2D to true,
            FILTER_DUPLICATE_MEDIA_IN_INDEXING to true,
            IOS_USE_QRCODES_LIBRARY to true,
            RESTORE_SYNCED to true,
            TREAT_APPS_AS_NOT_COPIED_WITHOUT_GOOGLE_ACCOUNT to false,
            IOS_ENABLE_DOWNLOADS_CABLE_D2D to true,
            BACKUP_ENABLE_DOCUMENTS_FLAVOR_D2D to true,
            IOS_ENABLE_CLOCK_CALLER_IDENTITY_INTENT_EXTRA to true,
        ) else null

    @JvmStatic fun rows(namespace: String?): Map<String, String>? =
        values(namespace)?.mapValues { (_, value) ->
            when (value) {
                is ByteArray -> Base64.getEncoder().withoutPadding().encodeToString(value)
                else -> value.toString()
            }
        }

    @JvmStatic fun flags(namespace: String?): Array<Flag>? =
        values(namespace)?.map { (name, value) ->
            when (value) {
                is Boolean -> Flag(name, value, 0)
                is Long -> Flag(name, value, 0)
                is ByteArray -> Flag(name, value.copyOf(), 0)
                else -> throw IllegalStateException("Unsupported Switch flag type")
            }
        }?.toTypedArray()
}
