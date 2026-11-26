/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.credential.manager

import org.junit.Assert.*
import org.junit.Test

/** Sign-ins for Cyclon (CYC-1594, CYC-3345): what the page says in each state. */
class SignInsTest {
    private val none = SignIns.Setup(account = false, autofill = false, passwords = false)
    private val login = SignIns.Login("a", "Amazon", "owner@example.com", null, null)

    @Test fun setupShowsOnlyWhileAStepIsUndone() {
        assertFalse(none.any)
        assertTrue(none.copy(account = true).any)
        assertTrue(none.copy(autofill = true).any)
        assertTrue(none.copy(passwords = true).any)
        assertTrue(none.copy(sync = true).any)
    }

    @Test fun aLoginSaysItsUsernameAndLastUse() {
        assertEquals("owner@example.com · used Oct 26, 2026", SignIns.details("owner@example.com", "used Oct 26, 2026"))
        assertEquals("owner", SignIns.details("owner", null))
        assertEquals("used Oct 26, 2026", SignIns.details("", "used Oct 26, 2026"))
        assertNull(SignIns.details("", null))
    }

    @Test fun noLoginsIsSaidOnceAndNotWhileReading() {
        val empty = SignIns.Logins.Shown(emptyList())
        assertTrue(SignIns.saysNone(none, empty, busy = false))
        assertFalse("the setup step already says so", SignIns.saysNone(none.copy(passwords = true), empty, busy = false))
        // Sync off: the account's passwords are not here yet, so "none" would be wrong.
        assertFalse(SignIns.saysNone(none.copy(sync = true), empty, busy = false))
        assertFalse(SignIns.saysNone(none, empty, busy = true))
        assertFalse(SignIns.saysNone(none, SignIns.Logins.Shown(listOf(login)), busy = false))
        assertFalse(SignIns.saysNone(none, SignIns.Logins.Locked, busy = false))
    }

    @Test fun theLoginsSectionIsNeverAnEmptyHeading() {
        val empty = SignIns.Logins.Shown(emptyList())
        assertFalse(SignIns.showsLogins(none.copy(passwords = true), empty, busy = false))
        assertTrue(SignIns.showsLogins(none, empty, busy = false))
        assertTrue(SignIns.showsLogins(none, SignIns.Logins.Locked, busy = false))
        assertTrue(SignIns.showsLogins(none, SignIns.Logins.Choosing, busy = false))
        assertTrue(SignIns.showsLogins(none.copy(passwords = true), SignIns.Logins.Shown(listOf(login)), busy = false))
    }
}
