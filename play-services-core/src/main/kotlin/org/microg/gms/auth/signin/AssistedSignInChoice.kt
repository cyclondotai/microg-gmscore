/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.auth.signin

/**
 * What the one-tap sheet does with the accounts it checked: sign in with one account, or show the chooser.
 * Each status pair is (account name, auth status): true = the app is already authorized for that account,
 * false = not authorized yet, null = the check failed.
 */
internal sealed interface AssistedSignInChoice {
    data class Single(val accountName: String, val permitted: Boolean) : AssistedSignInChoice
    data class Chooser(val accounts: List<Pair<String, Boolean?>>) : AssistedSignInChoice
}

/**
 * With [filterByAuthorizedAccounts] the app asks only for accounts it was authorized with before, as in
 * Play services' `GoogleIdTokenRequestOptions.filterByAuthorizedAccounts`: exactly one such account is used
 * directly (auto-select applies later); several are offered in the chooser, limited to them. Otherwise, or
 * when none is authorized, the chooser lists every account.
 */
internal fun chooseAssistedSignIn(
    statuses: List<Pair<String, Boolean?>>,
    filterByAuthorizedAccounts: Boolean,
): AssistedSignInChoice {
    val checked = statuses.filter { it.second != null }
    if (checked.size == 1) return AssistedSignInChoice.Single(checked[0].first, checked[0].second!!)
    if (filterByAuthorizedAccounts) {
        val authorized = statuses.filter { it.second == true }
        if (authorized.size == 1) return AssistedSignInChoice.Single(authorized[0].first, true)
        if (authorized.size > 1) return AssistedSignInChoice.Chooser(authorized)
    }
    return AssistedSignInChoice.Chooser(statuses)
}
