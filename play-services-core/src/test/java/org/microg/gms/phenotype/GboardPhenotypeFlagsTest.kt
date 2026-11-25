/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.phenotype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.phenotype.Configurations
import com.google.android.gms.phenotype.Flag
import com.google.android.gms.phenotype.internal.IPhenotypeCallbacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val serviceDescriptor = "com.google.android.gms.phenotype.internal.IPhenotypeService"
    private val callbacksDescriptor = "com.google.android.gms.phenotype.internal.IPhenotypeCallbacks"
    private val gboardPackage = "com.google.android.inputmethod.latin"
    private val gboardNamespace = "com.google.android.inputmethod.latin#com.google.android.inputmethod.latin"
    private val updateAction = "com.google.android.gms.phenotype.UPDATE"
    private val extraPackageName = "com.google.android.gms.phenotype.PACKAGE_NAME"
    private val extraUrgent = "com.google.android.gms.phenotype.URGENT"

    private val txGetConfigurationSnapshotWithToken = IBinder.FIRST_CALL_TRANSACTION + 10 // tx 11
    private val txRegisterSync = IBinder.FIRST_CALL_TRANSACTION + 12 // tx 13
    private val txSetRuntimeProperties = IBinder.FIRST_CALL_TRANSACTION + 24 // tx 25

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
                data.enforceInterface(callbacksDescriptor)
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

    private fun transactSync(binder: IBinder, code: Int, writeArgs: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(serviceDescriptor)
            writeArgs(data)
            assertTrue(binder.transact(code, data, reply, 0))
            assertTrue("Expected synchronous Binder exception header for tx $code", reply.dataSize() > 0)
            reply.setDataPosition(0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

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
        val binder = PhenotypeServiceImpl(gboardPackage).asBinder()
        for (priorToken in listOf(null, "unknown")) {
            var captured: Configurations? = null
            val callback = configurationCallback { captured = it }
            transactSync(binder, txGetConfigurationSnapshotWithToken) { data ->
                data.writeStrongInterface(callback)
                data.writeString(gboardNamespace)
                data.writeString("")
                data.writeString(priorToken)
            }
            assertGboardFlags(requireNotNull(captured) { "No snapshot for token=$priorToken" })
        }
    }

    @Test
    fun registerSyncReturnsGboardFlagsAndWritesSynchronousReplyHeader() {
        val binder = PhenotypeServiceImpl(gboardPackage).asBinder()

        var gboardCaptured: Configurations? = null
        val gboardCallback = configurationCallback { gboardCaptured = it }
        transactSync(binder, txRegisterSync) { data ->
            data.writeStrongInterface(gboardCallback)
            data.writeString(gboardNamespace)
            data.writeInt(1)
            data.writeStringArray(arrayOf("LATIN_IME"))
            data.writeByteArray(ByteArray(0))
            data.writeString("")
            data.writeString(null)
        }
        assertGboardFlags(requireNotNull(gboardCaptured))

        var unknownCaptured: Configurations? = null
        val unknownCallback = configurationCallback { unknownCaptured = it }
        transactSync(binder, txRegisterSync) { data ->
            data.writeStrongInterface(unknownCallback)
            data.writeString("com.example.unknown#com.example.unknown")
            data.writeInt(1)
            data.writeStringArray(emptyArray())
            data.writeByteArray(ByteArray(0))
            data.writeString("")
            data.writeString(null)
        }
        assertEquals(0, requireNotNull(unknownCaptured).field4.size)

        // Oneway callers must not receive a reply parcel (avoids warnOnTransactionIssues noise
        // and keeps compatibility with async Binder stubs).
        var onewayCaptured: Configurations? = null
        val onewayCallback = configurationCallback { onewayCaptured = it }
        val onewayData = Parcel.obtain()
        val onewayReply = Parcel.obtain()
        try {
            onewayData.writeInterfaceToken(serviceDescriptor)
            onewayData.writeStrongInterface(onewayCallback)
            onewayData.writeString(gboardNamespace)
            onewayData.writeInt(1)
            onewayData.writeStringArray(arrayOf("LATIN_IME"))
            onewayData.writeByteArray(ByteArray(0))
            onewayData.writeString("")
            onewayData.writeString(null)
            assertTrue(binder.transact(txRegisterSync, onewayData, onewayReply, IBinder.FLAG_ONEWAY))
            assertEquals("Expected empty reply for FLAG_ONEWAY call", 0, onewayReply.dataSize())
        } finally {
            onewayData.recycle()
            onewayReply.recycle()
        }
        assertGboardFlags(requireNotNull(onewayCaptured))
    }

    @Test
    fun setRuntimePropertiesCompletesCallbackWithSuccessAndSynchronousReplyHeader() {
        val binder = PhenotypeServiceImpl(gboardPackage).asBinder()
        var statusCode: Int? = null
        val callback = object : IStatusCallback.Stub() {
            override fun onResult(status: Status) {
                statusCode = status.statusCode
            }
        }
        transactSync(binder, txSetRuntimeProperties) { data ->
            data.writeStrongInterface(callback)
            data.writeString(gboardNamespace)
            data.writeByteArray(ByteArray(0))
        }
        assertEquals(0, statusCode)
    }

    @Test
    fun packageReplacedDelaysSingleFollowUpAndFinishesPendingResult() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app)
        val receiver = PhenotypeUpdateReceiver()
        val filter = IntentFilter(Intent.ACTION_MY_PACKAGE_REPLACED)
        app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        try {
            shadowApp.clearBroadcastIntents()
            var orderedComplete = false
            app.sendOrderedBroadcast(
                Intent(Intent.ACTION_MY_PACKAGE_REPLACED),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        orderedComplete = true
                    }
                },
                null,
                0,
                null,
                null,
            )
            ShadowLooper.idleMainLooper()

            val updatesNow = shadowApp.broadcastIntents.filter { it.action == updateAction }
            assertEquals("Expected immediate broadcast", 1, updatesNow.size)
            assertValidUpdateIntent(updatesNow[0])
            assertFalse("Expected goAsync() to hold PendingResult before follow-up", orderedComplete)

            ShadowLooper.idleMainLooper(1_499, TimeUnit.MILLISECONDS)
            assertEquals(
                "Follow-up must not fire before 1500 ms",
                1,
                shadowApp.broadcastIntents.count { it.action == updateAction },
            )
            assertFalse("PendingResult must still be open at 1499 ms", orderedComplete)

            ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
            val updatesAfter = shadowApp.broadcastIntents.filter { it.action == updateAction }
            assertEquals("Expected one follow-up broadcast at 1500 ms", 2, updatesAfter.size)
            assertValidUpdateIntent(updatesAfter[1])
            assertTrue("Expected PendingResult.finish() after follow-up", orderedComplete)

            ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
            assertEquals(
                "Expected no extra broadcasts after follow-up",
                2,
                shadowApp.broadcastIntents.count { it.action == updateAction },
            )
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun bootCompletedSendsSingleBroadcastWithoutHoldingOrderedBroadcast() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app)
        val receiver = PhenotypeUpdateReceiver()
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED), Context.RECEIVER_NOT_EXPORTED)
        try {
            shadowApp.clearBroadcastIntents()
            var orderedComplete = false
            app.sendOrderedBroadcast(
                Intent(Intent.ACTION_BOOT_COMPLETED),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        orderedComplete = true
                    }
                },
                null,
                0,
                null,
                null,
            )
            ShadowLooper.idleMainLooper()

            val updates = shadowApp.broadcastIntents.filter { it.action == updateAction }
            assertEquals("Expected one immediate broadcast on boot", 1, updates.size)
            assertValidUpdateIntent(updates[0])
            assertTrue("BOOT_COMPLETED must not hold ordered broadcast with goAsync()", orderedComplete)

            ShadowLooper.idleMainLooper(10, TimeUnit.SECONDS)
            assertEquals(
                "Expected no follow-up broadcast on BOOT_COMPLETED",
                1,
                shadowApp.broadcastIntents.count { it.action == updateAction },
            )
        } finally {
            app.unregisterReceiver(receiver)
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
