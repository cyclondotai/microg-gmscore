/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.transport.hybrid

import com.google.android.gms.fido.fido2.api.common.AuthenticatorSelectionCriteria
import com.google.android.gms.fido.fido2.api.common.ResidentKeyRequirement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.microg.gms.fido.core.hybrid.model.QrCodeData
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class HybridTransportHandlerTest {
    private fun generateP256PublicKey(): ECPublicKey {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair().public as ECPublicKey
    }

    @Test fun newQrSecretIs16BytesAndRoundTripsThroughCableQrPayload() {
        val secret = HybridTransportHandler.newQrSecret()
        assertEquals(16, secret.size)
        val payload = QrCodeData.buildQrCborPayload(generateP256PublicKey(), secret)
        val parsed = QrCodeData.parse(payload)
        assertNotNull(parsed)
        assertArrayEquals(secret, parsed!!.randomSeed)
        assertEquals(16, parsed.randomSeed.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun qrPayloadRejects32ByteChallengeAsSecret() {
        QrCodeData.buildQrCborPayload(generateP256PublicKey(), ByteArray(32) { it.toByte() })
    }

    @Test fun residentKeyRequirementRequiredSetsRkTrueWithoutLegacyFlag() {
        val required = AuthenticatorSelectionCriteria.Builder()
            .setResidentKeyRequirement(ResidentKeyRequirement.RESIDENT_KEY_REQUIRED)
            .build()
        assertTrue(HybridTransportHandler.requiresResidentKey(required))

        val preferred = AuthenticatorSelectionCriteria.Builder()
            .setResidentKeyRequirement(ResidentKeyRequirement.RESIDENT_KEY_PREFERRED)
            .build()
        assertFalse(HybridTransportHandler.requiresResidentKey(preferred))

        val legacyRequired = AuthenticatorSelectionCriteria.Builder()
            .setRequireResidentKey(true)
            .build()
        assertTrue(HybridTransportHandler.requiresResidentKey(legacyRequired))
    }
}
