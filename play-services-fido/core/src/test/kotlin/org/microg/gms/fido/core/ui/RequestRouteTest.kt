/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.fido.core.ui

import org.junit.Assert.*
import org.junit.Test
import org.microg.gms.fido.core.transport.Transport

class RequestRouteTest {
    private val everyTransport = Transport.entries.toSet()

    @Test fun hybridOnlyRequestSkipsAccountPasskeysAndInstantScreenLockAndOffersOnlyTheQrFlow() {
        val route = RequestRoute(hybridOnly = true)
        assertFalse(route.offersAccountPasskeys)
        assertFalse(route.allowsInstantTransport)
        assertEquals(setOf(Transport.HYBRID), route.offeredTransports(everyTransport))
        // Without Bluetooth nothing is offered, and the activity reports the request as not supported.
        assertEquals(emptySet<Transport>(), route.offeredTransports(everyTransport - Transport.HYBRID))
    }

    @Test fun otherRequestsKeepAccountPasskeysInstantScreenLockAndEverySupportedTransport() {
        val route = RequestRoute(hybridOnly = false)
        assertTrue(route.offersAccountPasskeys)
        assertTrue(route.allowsInstantTransport)
        assertEquals(everyTransport, route.offeredTransports(everyTransport))
        val noBluetooth = everyTransport - Transport.HYBRID - Transport.BLUETOOTH
        assertEquals(noBluetooth, route.offeredTransports(noBluetooth))
    }
}
