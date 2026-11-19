/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

/**
 * CYC-2677 (owner decision, 2026-11-19): a locked phone refuses the agent's sign-in nothing the unlocked phone allows.
 * Who may use a login is AgentSignInAccess's question, and the target is Core's. What is left is Android's own limit.
 */
internal object AgentSignInLockPolicy {
    /** Before the first unlock after a restart the vault cannot be read. */
    fun storageOpen(userUnlocked: Boolean) = userUnlocked
}
