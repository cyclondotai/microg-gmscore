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
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

class HybridTransportHandlerTest {
    private val p256Params by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        (kpg.generateKeyPair().public as ECPublicKey).params
    }

    private fun generateP256PublicKey(): ECPublicKey {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair().public as ECPublicKey
    }

    private fun p256KeyWithXStartingAt(startX: BigInteger): ECPublicKey {
        val p = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val b = p256Params.curve.b
        val exp = p.add(BigInteger.ONE).divide(BigInteger.valueOf(4))
        var x = startX
        while (true) {
            val y2 = x.modPow(BigInteger.valueOf(3), p).subtract(x.multiply(BigInteger.valueOf(3))).add(b).mod(p)
            val y = y2.modPow(exp, p)
            if (y.modPow(BigInteger.valueOf(2), p) == y2) {
                return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Params)) as ECPublicKey
            }
            x = x.add(BigInteger.ONE)
        }
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

    @Test fun qrPayloadPadsShortAndTrimsLeadingZeroP256XCoordinates() {
        val secret = ByteArray(16) { (it + 1).toByte() }
        // Short X coordinate (leading zero byte => toByteArray().size < 32).
        val shortXKey = p256KeyWithXStartingAt(BigInteger.ONE)
        assertTrue(shortXKey.w.affineX.toByteArray().size < 32)
        val parsedShort = QrCodeData.parse(QrCodeData.buildQrCborPayload(shortXKey, secret))
        assertNotNull(parsedShort)
        assertEquals(shortXKey.w, parsedShort!!.peerPublicKey.w)

        // High-bit X coordinate (toByteArray().size == 33 with leading 0x00 sign byte).
        val highBitXKey = p256KeyWithXStartingAt(BigInteger.ONE.shiftLeft(255))
        assertEquals(33, highBitXKey.w.affineX.toByteArray().size)
        val parsedHigh = QrCodeData.parse(QrCodeData.buildQrCborPayload(highBitXKey, secret))
        assertNotNull(parsedHigh)
        assertEquals(highBitXKey.w, parsedHigh!!.peerPublicKey.w)
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
