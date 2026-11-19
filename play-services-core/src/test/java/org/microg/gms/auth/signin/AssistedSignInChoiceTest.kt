/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.signin

import org.junit.Assert.assertEquals
import org.junit.Test
import org.microg.gms.auth.signin.AssistedSignInChoice.Chooser
import org.microg.gms.auth.signin.AssistedSignInChoice.Single

class AssistedSignInChoiceTest {
    private val a = "a@example.com"
    private val b = "b@example.com"
    private val c = "c@example.com"

    @Test fun onlyAuthorizedAccountIsUsedEvenWhenNotFirst() {
        // Back from b's confirmation must not offer a: the chooser holds only b, so Back closes the sheet.
        assertEquals(Single(b, true, listOf(b to true)), chooseAssistedSignIn(listOf(a to false, b to true), true))
    }

    @Test fun severalAuthorizedAccountsShowChooserLimitedToThem() {
        assertEquals(
            Chooser(listOf(a to true, c to true)),
            chooseAssistedSignIn(listOf(a to true, b to false, c to true), true),
        )
    }

    @Test fun noAuthorizedAccountShowsChooserWithEveryAccount() {
        val statuses = listOf(a to false, b to false)
        assertEquals(Chooser(statuses), chooseAssistedSignIn(statuses, true))
    }

    @Test fun withoutFilterChooserListsEveryAccount() {
        val statuses = listOf(a to false, b to true)
        assertEquals(Chooser(statuses), chooseAssistedSignIn(statuses, false))
    }

    @Test fun singleCheckableAccountIsUsedAsBefore() {
        val withFailed = listOf(a to null, b to false)
        assertEquals(Single(b, false, withFailed), chooseAssistedSignIn(withFailed, true))
        assertEquals(Single(a, true, listOf(a to true)), chooseAssistedSignIn(listOf(a to true), false))
    }

    @Test fun failedChecksDoNotCountAsAuthorized() {
        val statuses = listOf(a to null, b to null, c to null)
        assertEquals(Chooser(statuses), chooseAssistedSignIn(statuses, true))
    }
}
