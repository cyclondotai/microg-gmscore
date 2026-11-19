/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.signin

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistedAccountChoiceTest {
    private val first = "a.first@example.org"
    private val second = "b.second@example.org"
    private val third = "c.third@example.org"

    /** 2026-11-18: the first account never authorized the app, the second did; the app asked for authorized accounts. */
    @Test fun authorizedAccountIsTakenNotTheFirstOfTheList() {
        assertEquals(AssistedAccountChoice.Single(second, true),
            chooseAssistedAccount(listOf(first to false, second to true), filterByAuthorizedAccounts = true))
    }

    @Test fun severalAuthorizedAccountsAreOfferedNeverOneTakenUnasked() {
        assertEquals(AssistedAccountChoice.Choose(listOf(first to true, third to true)),
            chooseAssistedAccount(listOf(first to true, second to false, third to true), filterByAuthorizedAccounts = true))
    }

    @Test fun noAuthorizedAccountOffersThemAll() {
        val statuses = listOf(first to false, second to false)
        assertEquals(AssistedAccountChoice.Choose(statuses), chooseAssistedAccount(statuses, filterByAuthorizedAccounts = true))
    }

    @Test fun appThatDoesNotFilterGetsTheChooserEvenWithOneAuthorized() {
        val statuses = listOf(first to false, second to true)
        assertEquals(AssistedAccountChoice.Choose(statuses), chooseAssistedAccount(statuses, filterByAuthorizedAccounts = false))
    }

    @Test fun onlyAccountThatCouldBeAskedStandsAloneWithItsOwnAnswer() {
        assertEquals(AssistedAccountChoice.Single(second, false),
            chooseAssistedAccount(listOf(first to null, second to false), filterByAuthorizedAccounts = true))
        assertEquals(AssistedAccountChoice.Single(first, true),
            chooseAssistedAccount(listOf(first to true), filterByAuthorizedAccounts = false))
    }

    @Test fun anAccountThatCouldNotBeAskedIsNeverTakenForAuthorized() {
        assertEquals(AssistedAccountChoice.Single(third, true),
            chooseAssistedAccount(listOf(first to null, second to false, third to true), filterByAuthorizedAccounts = true))
    }
}
