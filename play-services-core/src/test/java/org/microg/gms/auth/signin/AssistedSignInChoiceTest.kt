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
        assertEquals(Single(b, true), chooseAssistedSignIn(listOf(a to false, b to true), true))
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
        assertEquals(Single(b, false), chooseAssistedSignIn(listOf(a to null, b to false), true))
        assertEquals(Single(a, true), chooseAssistedSignIn(listOf(a to true), false))
    }

    @Test fun failedChecksDoNotCountAsAuthorized() {
        val statuses = listOf(a to null, b to null, c to null)
        assertEquals(Chooser(statuses), chooseAssistedSignIn(statuses, true))
    }
}
