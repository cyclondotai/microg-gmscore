/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.phenotype

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.phenotype.Configurations
import com.google.android.gms.phenotype.Flag
import com.google.android.gms.phenotype.internal.IPhenotypeCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.w3c.dom.Element
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GboardPhenotypeFlagsTest {
    private val gboardPackage = "com.google.android.inputmethod.latin"
    private val gboardNamespace = "com.google.android.inputmethod.latin#com.google.android.inputmethod.latin"
    private val updateAction = "com.google.android.gms.phenotype.UPDATE"
    private val extraPackageName = "com.google.android.gms.phenotype.PACKAGE_NAME"
    private val extraUrgent = "com.google.android.gms.phenotype.URGENT"

    private val expectedTrueFlags = listOf(
        "enable_device_intelligence",
        "enable_voice_in_chinese",
        "enable_voice_in_japanese",
        "enable_voice_in_korean",
        "enable_voice_in_handwriting",
    )

    private fun configurationCallback(read: (Configurations) -> Unit): IPhenotypeCallbacks =
        IPhenotypeCallbacks.Stub.asInterface(object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.google.android.gms.phenotype.internal.IPhenotypeCallbacks")
                assertEquals(4, code)
                assertEquals(IBinder.FLAG_ONEWAY, flags)
                assertEquals(1, data.readInt())
                assertEquals(0, Status.CREATOR.createFromParcel(data).statusCode)
                assertEquals(1, data.readInt())
                read(Configurations.CREATOR.createFromParcel(data))
                assertEquals(0, data.dataAvail())
                return true
            }
        })

    private fun assertGboardFlags(configs: Configurations) {
        assertEquals(false, configs.field5)
        val flags = configs.field4.flatMap { it.flags.toList() }.associateBy { it.name }
        for (name in expectedTrueFlags) {
            val flag = requireNotNull(flags[name]) { "Missing Gboard Phenotype flag $name" }
            assertEquals(Flag.DATA_TYPE_BOOL, flag.dataType)
            assertTrue("Expected $name to be true", flag.bool)
        }
    }

    private fun assertValidUpdateIntent(sent: Intent) {
        assertEquals(updateAction, sent.action)
        assertEquals(gboardPackage, sent.`package`)
        assertEquals(gboardNamespace, sent.getStringExtra(extraPackageName))
        assertTrue(sent.getBooleanExtra(extraUrgent, false))
    }

    @Test
    fun gboardSnapshotReturnsDeviceIntelligenceForInitialAndRepeatTokenFetches() {
        val service = PhenotypeServiceImpl(gboardPackage)
        for (priorToken in listOf(null, "unknown")) {
            var captured: Configurations? = null
            service.getConfigurationSnapshotWithToken(
                configurationCallback { captured = it },
                gboardNamespace,
                "",
                priorToken,
            )
            assertGboardFlags(requireNotNull(captured) { "No snapshot for token=$priorToken" })
        }
    }

    @Test
    fun registerSyncReturnsGboardFlagsAndEmptyForUnknownNamespace() {
        val service = PhenotypeServiceImpl(gboardPackage)
        var gboardCaptured: Configurations? = null
        service.registerSync(
            configurationCallback { gboardCaptured = it },
            gboardNamespace,
            1,
            arrayOf("LATIN_IME"),
            ByteArray(0),
            "",
            null,
        )
        assertGboardFlags(requireNotNull(gboardCaptured))

        var unknownCaptured: Configurations? = null
        service.registerSync(
            configurationCallback { unknownCaptured = it },
            "com.example.unknown#com.example.unknown",
            1,
            emptyArray(),
            ByteArray(0),
            "",
            null,
        )
        assertEquals(0, requireNotNull(unknownCaptured).field4.size)
    }

    @Test
    fun setRuntimePropertiesCompletesCallbackWithSuccess() {
        val service = PhenotypeServiceImpl(gboardPackage)
        var statusCode: Int? = null
        service.setRuntimeProperties(
            object : IStatusCallback.Stub() {
                override fun onResult(status: Status) {
                    statusCode = status.statusCode
                }
            },
            gboardNamespace,
            ByteArray(0),
        )
        assertEquals(0, statusCode)
    }

    @Test
    fun packageReplacedAndBootCompletedSendImmediateAndOneBoundedFollowUpBroadcast() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app)

        for (action in listOf(Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED)) {
            shadowApp.clearBroadcastIntents()

            PhenotypeUpdateReceiver().onReceive(app, Intent(action))

            assertEquals("Expected immediate broadcast for $action", 1, shadowApp.broadcastIntents.size)
            assertValidUpdateIntent(shadowApp.broadcastIntents[0])

            ShadowLooper.idleMainLooper(1_500, TimeUnit.MILLISECONDS)
            assertEquals("Expected one follow-up broadcast for $action", 2, shadowApp.broadcastIntents.size)
            assertValidUpdateIntent(shadowApp.broadcastIntents[1])

            ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
            assertEquals("Expected no extra broadcasts after follow-up for $action", 2, shadowApp.broadcastIntents.size)
        }
    }

    @Test
    fun unrelatedOrNullIntentDoesNotBroadcastPhenotypeUpdate() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app)
        shadowApp.clearBroadcastIntents()

        PhenotypeUpdateReceiver().onReceive(app, Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED))
        PhenotypeUpdateReceiver().onReceive(app, null)
        ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)

        assertTrue(shadowApp.broadcastIntents.isEmpty())
    }

    @Test
    fun manifestRegistersNonExportedPhenotypeUpdateReceiverForUpgradeAndBoot() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("play-services-core/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile } ?: error("Could not locate play-services-core AndroidManifest.xml")

        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val receivers = doc.getElementsByTagName("receiver")
        var matched: Element? = null
        for (i in 0 until receivers.length) {
            val el = receivers.item(i) as Element
            if (el.getAttributeNS(androidNs, "name") == "org.microg.gms.phenotype.PhenotypeUpdateReceiver") {
                matched = el
                break
            }
        }
        val receiver = requireNotNull(matched) { "PhenotypeUpdateReceiver missing from AndroidManifest.xml" }
        assertEquals("false", receiver.getAttributeNS(androidNs, "exported"))

        val actionNodes = receiver.getElementsByTagName("action")
        val actions = (0 until actionNodes.length)
            .map { (actionNodes.item(it) as Element).getAttributeNS(androidNs, "name") }
            .toSet()
        assertEquals(
            setOf("android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.BOOT_COMPLETED"),
            actions,
        )
    }
}
