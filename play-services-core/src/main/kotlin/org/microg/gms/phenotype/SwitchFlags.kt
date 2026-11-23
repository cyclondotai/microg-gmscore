/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.phenotype

import com.google.android.gms.phenotype.Flag
import java.util.Base64

/** Local Switch configuration, not a server rollout assignment or a sync-mode change. */
object SwitchFlags {
    const val NAMESPACE = "com.google.android.apps.pixelmigrate"
    const val RESTORE_TO_GOOGLE = "D2d__enable_contacts_restore_to_google_account"
    const val IOS_APP_MATCHING_LIMIT = "backup_ios_app_matching_service_request_apps_limit"
    const val IOS_APP_MATCHING_LIMIT_VALUE = 1000L
    const val IOS_APP_FILES_D2D = "IosAppFilesD2d__enable_ios_app_files_d2d"
    const val IOS_APP_ALLOWLIST = "IosAppFilesD2d__app_allowlist"
    const val FILTER_IOS_WHATSAPP_WHEN_NO_ACCOUNT = "Ios__filter_ios_whatsapp_when_no_account"

    // Switch 1140078 ren.java:22,24 defaults IosAppFilesD2d__enable_ios_app_files_d2d to false
    // and IosAppFilesD2d__app_allowlist (imf proto with repeated ime entries) to empty, causing
    // MigrateUtil (drd.java:410) to hide WhatsApp chat transfer completely.
    // Protobuf imf { entries(1): ime { name(1)="whatsapp", bundle_id(2)="net.whatsapp.WhatsApp",
    //   group_container(3)="group.net.whatsapp.WhatsApp.shared", flavor(4)=IOS_WHATSAPP(9) } }.
    // Switch reads proto flags from ConfigurationProvider via Base64.decode(str, NO_PADDING|NO_WRAP)
    // (lla.java) and from PhenotypeService as Flag.DATA_TYPE_BYTES.
    const val IOS_WHATSAPP_ALLOWLIST_BASE64 =
        "CkcKCHdoYXRzYXBwEhVuZXQud2hhdHNhcHAuV2hhdHNBcHAaImdyb3VwLm5ldC53aGF0c2FwcC5XaGF0c0FwcC5zaGFyZWQgCQ"
    private val IOS_WHATSAPP_ALLOWLIST_BYTES: ByteArray = Base64.getDecoder().decode(IOS_WHATSAPP_ALLOWLIST_BASE64)

    // Switch 1140078 rct.java:164 defaults this off; dou.java:2469 then passes no
    // account to the iPhone writer despite ecl.java:748-787 displaying a Google account.
    // Enabling its existing path lets Switch choose and disclose the visible account.
    //
    // Switch 1140078 eew.java:157-160 sends only the first IOS_APP_MATCHING_LIMIT iPhone
    // bundle IDs (default 200) to appmapping in a single request and never sends the rest,
    // so they land in "couldn't be copied" unchecked. The server answered every ID of
    // 200..2000-ID requests on 2026-10-30 (CYC-968), so the cap is client-side only.
    //
    // Switch 1140078 res.java:118 / dlr.java:35 hides IOS_WHATSAPP when no Google account is
    // chosen unless Ios__filter_ios_whatsapp_when_no_account is false; WhatsApp stages locally
    // in Switch and needs no Google account.
    private fun values(namespace: String?): Map<String, Any>? =
        if (namespace == NAMESPACE) mapOf(
            RESTORE_TO_GOOGLE to true,
            IOS_APP_MATCHING_LIMIT to IOS_APP_MATCHING_LIMIT_VALUE,
            IOS_APP_FILES_D2D to true,
            IOS_APP_ALLOWLIST to IOS_WHATSAPP_ALLOWLIST_BYTES,
            FILTER_IOS_WHATSAPP_WHEN_NO_ACCOUNT to false,
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
