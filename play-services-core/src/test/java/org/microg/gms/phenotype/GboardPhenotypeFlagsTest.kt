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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class GboardPhenotypeFlagsTest {
    private fun snapshotCallback(read: (Configurations) -> Unit): IPhenotypeCallbacks =
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

    @Test
    fun gboardSnapshotEnablesDeviceIntelligenceAndVoiceFlags() {
        val service = PhenotypeServiceImpl(PhenotypeUpdateReceiver.GBOARD_PACKAGE)
        var captured: Configurations? = null
        service.getConfigurationSnapshotWithToken(
            snapshotCallback { captured = it },
            PhenotypeUpdateReceiver.GBOARD_PHENOTYPE_PACKAGE,
            "",
            null,
        )

        val configs = requireNotNull(captured)
        assertEquals(false, configs.field5)
        val flags = configs.field4.flatMap { it.flags.toList() }.associateBy { it.name }
        val expectedTrueFlags = listOf(
            "enable_device_intelligence",
            "enable_voice_in_chinese",
            "enable_voice_in_japanese",
            "enable_voice_in_korean",
            "enable_voice_in_handwriting",
        )
        for (name in expectedTrueFlags) {
            val flag = requireNotNull(flags[name]) { "Missing Gboard Phenotype flag $name" }
            assertEquals(Flag.DATA_TYPE_BOOL, flag.dataType)
            assertTrue("Expected $name to be true", flag.bool)
        }
    }

    @Test
    fun packageReplacedBroadcastsUrgentPhenotypeUpdateToGboard() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app)
        shadowApp.clearBroadcastIntents()

        PhenotypeUpdateReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val broadcasts = shadowApp.broadcastIntents
        assertEquals(1, broadcasts.size)
        val sent = broadcasts.single()
        assertEquals(PhenotypeUpdateReceiver.ACTION_PHENOTYPE_UPDATE, sent.action)
        assertEquals(PhenotypeUpdateReceiver.GBOARD_PACKAGE, sent.`package`)
        assertEquals(
            PhenotypeUpdateReceiver.GBOARD_PHENOTYPE_PACKAGE,
            sent.getStringExtra(PhenotypeUpdateReceiver.EXTRA_PACKAGE_NAME),
        )
        assertTrue(sent.getBooleanExtra(PhenotypeUpdateReceiver.EXTRA_URGENT, false))
    }

    @Test
    fun unrelatedIntentDoesNotBroadcastPhenotypeUpdate() {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = shadowOf(app)
        shadowApp.clearBroadcastIntents()

        PhenotypeUpdateReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        PhenotypeUpdateReceiver().onReceive(app, null)

        assertTrue(shadowApp.broadcastIntents.isEmpty())
    }
}
