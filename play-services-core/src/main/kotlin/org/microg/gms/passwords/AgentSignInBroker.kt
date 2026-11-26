/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.microg.gms.cyclon.DeviceAccount
import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Only PasswordAgentFillProvider's authenticated Core caller can reach this native broker. */
internal class AgentSignInBroker(private val context: Context) {
    private val accounts = AccountManager.get(context)
    private val access = AgentSignInAccess(context)
    private class Choice(val account: Account?, val userId: String?, val tag: String, val incarnation: String) {
        val vaultKey get() = AgentSignInAccess.vaultKey(userId)
        override fun toString() = "SignInChoice[redacted]"
    }

    fun call(method: String, ref: String?, input: Bundle?): Bundle {
        requireStorageOpen()
        return when (method) {
            "candidates" -> {
                if (ref != null) throw SecurityException()
                candidates(PasswordAgentFillTarget.from(context, input ?: throw SecurityException()))
            }
            "set_access" -> {
                // Rolling-upgrade compatibility with older Core builds: validate the reference and return success
                // without storing Ask/Never (CYC-3345: every matching saved login is Always).
                val choice = choice(ref)
                withChoice(choice) { requireStorageOpen() }
                Bundle.EMPTY
            }
            "redeem" -> {
                val choice = choice(ref)
                val extras = input ?: throw SecurityException()
                val approval = extras.getString("approval")
                if (approval != null && approval !in setOf("once", "always")) throw SecurityException()
                val request = PasswordAgentFillRequest.from(context, extras)
                val vault = vault(choice)
                val before = vault.list().singleOrNull { it.id == choice.tag } ?: throw SecurityException()
                if (!PasswordAffiliationMatcher(context, vault).canFill(before.data, request.realm)) throw SecurityException()
                val realm = before.data.realm
                val username = before.data.username
                claimHandle(request.handle, request.deadline)
                PasswordAgentFillRequests.put(request)
                try { deliver(choice, request, realm, username) }
                finally { PasswordAgentFillRequests.cancel(request.handle) }
                Bundle.EMPTY
            }
            else -> throw SecurityException()
        }
    }

    private fun deliver(choice: Choice, request: PasswordAgentFillRequest, realm: String, username: String) {
        withChoice(choice) { current ->
            // The earlier affiliation proof covers exactly this entry's current realm.
            if (current.realm != realm || current.username != username || current.blocked || current.password.isEmpty()) throw SecurityException()
            access.redeem(choice.vaultKey, choice.tag, choice.incarnation) {
                requireStorageOpen()
                if (!request.valid(context)) throw SecurityException()
                request.deliver(context, current.password.toCharArray())
            }
        }
    }

    private fun candidates(target: PasswordAgentFillTarget): Bundle {
        val rows = arrayListOf<Bundle>()
        val owners: List<Account?> = listOf(null) + DeviceAccount.visible(context, accounts.getAccountsByType("com.google")).take(20)
        for (account in owners) {
            // A locked/disabled account must not prevent using a separate available vault.
            try {
                val userId = stableId(account)
                val vault = vault(account, userId)
                if (!vault.enabled) continue
                val matcher = PasswordAffiliationMatcher(context, vault)
                for (entry in vault.list()) {
                    if (rows.size >= 200) break
                    if (entry.data.username.length > 1000 || !matcher.canFill(entry.data, target.realm)) continue
                    val initial = Choice(account, userId, entry.id, "")
                    withChoice(initial, vault, checkIncarnation = false) { current ->
                        if (current.realm != entry.data.realm || current.username != entry.data.username ||
                            current.blocked || current.password.isEmpty()) return@withChoice
                        requireStorageOpen()
                        if (!target.valid(context)) throw SecurityException()
                        val state = access.get(initial.vaultKey, entry.id)
                        val choice = Choice(account, userId, entry.id, state.incarnation)
                        val ref = remember(choice)
                        rows.add(Bundle().apply {
                            putString("ref", ref); putString("username", current.username)
                            putString("account", account?.name ?: context.getString(com.google.android.gms.R.string.passwords_on_device))
                            // Wire compatibility: every saved login is Always with no device-auth prompt (CYC-3345).
                            putString("access", "always")
                            putBoolean("require_authentication", false)
                            state.lastUsed?.let { putLong("last_used", it) }
                        })
                    }
                }
            } catch (_: Exception) { /* Account disabled, removed or locked while enumerating. */ }
        }
        if (!target.valid(context)) throw SecurityException()
        requireStorageOpen()
        return Bundle().apply { putParcelableArrayList("accounts", rows); putBoolean("background_signin_v1", true) }
    }

    /**
     * CYC-2677: the lock state decides nothing here; a locked phone signs in as the unlocked one does. Before the first
     * unlock after a restart the vault's credential-encrypted storage is closed, which is the one thing that waits.
     */
    private fun requireStorageOpen() {
        val users = context.getSystemService(Context.USER_SERVICE) as? UserManager ?: throw SecurityException()
        if (!AgentSignInLockPolicy.storageOpen(users.isUserUnlocked)) throw SecurityException()
    }
    private fun stableId(account: Account?): String? {
        if (account == null) return null
        if (account !in accounts.getAccountsByType("com.google")) throw SecurityException()
        return accounts.getUserData(account, "GoogleUserId")?.takeIf { it.isNotBlank() } ?: throw SecurityException()
    }
    private fun vault(choice: Choice) = vault(choice.account, choice.userId)
    private fun vault(account: Account?, userId: String?): PasswordVault {
        if (stableId(account) != userId) throw SecurityException()
        return account?.let { PasswordRepository(context, it) } ?: DevicePasswordVault(context)
    }
    private fun <T> withChoice(choice: Choice, vault: PasswordVault = vault(choice), checkIncarnation: Boolean = true,
        action: (PasswordData) -> T): T {
        fun apply(data: PasswordData): T {
            if (stableId(choice.account) != choice.userId || !vault.enabled) throw SecurityException()
            if (checkIncarnation && access.get(choice.vaultKey, choice.tag).incarnation != choice.incarnation) throw SecurityException()
            return action(data)
        }
        return when (vault) {
            is PasswordRepository -> vault.withAgentEntry(choice.tag, ::apply)
            is DevicePasswordVault -> vault.withAgentEntry(choice.tag, ::apply)
            else -> throw SecurityException()
        }
    }

    companion object {
        private val key = ByteArray(32).also(SecureRandom()::nextBytes)
        private val choices = LinkedHashMap<String, Choice>()
        private val handles = LinkedHashMap<String, Long>()
        @Synchronized private fun remember(choice: Choice): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            val raw = Proto.field(1, choice.vaultKey) + Proto.field(2, choice.tag) + Proto.field(3, choice.incarnation)
            val ref = mac.doFinal(raw).joinToString("") { "%02x".format(it) }
            choices[ref] = choice
            while (choices.size > 1024) choices.remove(choices.keys.first())
            return ref
        }
        @Synchronized private fun choice(ref: String?): Choice = choices[ref] ?: throw SecurityException()
        @Synchronized private fun claimHandle(handle: String, deadline: Long) {
            val now = SystemClock.elapsedRealtime()
            handles.entries.removeAll { it.value <= now }
            if (handles.containsKey(handle) || handles.size >= 1024) throw SecurityException()
            handles[handle] = deadline
        }
    }
}
