/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.signin

/**
 * What the assisted sign-in sheet does once each of the phone's accounts has been asked whether it already authorized
 * the app (true), has not (false), or could not be asked (null).
 *
 * An account is signed in without the owner choosing it only when there is no doubt which one is meant: the only
 * account that could be asked, or, for an app that asks for authorized accounts, the only authorized one. Several
 * authorized accounts are offered to choose from. Before (seen 2026-11-18 on a phone with two accounts), an app asking
 * for authorized accounts got the first account of the phone's list as soon as any account was authorized: Booking.com
 * was signed in with an account that never authorized it, three seconds after its sheet opened, and offered sign-up.
 */
sealed class AssistedAccountChoice {
    /** One account: signed in unasked when [permitted] and the app allows auto-select, else offered with its consent. */
    data class Single(val accountName: String, val permitted: Boolean) : AssistedAccountChoice()

    /** The owner chooses among [accounts], in the phone's order. */
    data class Choose(val accounts: List<Pair<String, Boolean?>>) : AssistedAccountChoice()
}

fun chooseAssistedAccount(statuses: List<Pair<String, Boolean?>>, filterByAuthorizedAccounts: Boolean): AssistedAccountChoice {
    val checked = statuses.filter { it.second != null }
    if (checked.size == 1) return AssistedAccountChoice.Single(checked[0].first, checked[0].second!!)
    val authorized = statuses.filter { it.second == true }
    if (filterByAuthorizedAccounts && authorized.isNotEmpty()) {
        return if (authorized.size == 1) AssistedAccountChoice.Single(authorized[0].first, true) else AssistedAccountChoice.Choose(authorized)
    }
    return AssistedAccountChoice.Choose(statuses)
}
