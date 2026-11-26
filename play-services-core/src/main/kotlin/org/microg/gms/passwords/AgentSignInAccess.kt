/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.microg.gms.common.ProcessFileLock
import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.security.MessageDigest

/**
 * Per-login lifetime incarnation and last-used record for Cyclon's agent sign-in path (CYC-779, CYC-3345).
 *
 * Since CYC-3345 (Thomas, 2026-11-26), Ask, Never and Require device authentication are removed from the agent sign-in
 * path and the Passwords app: every matching saved login is treated as Always, including entries persisted with Ask,
 * Never or requireDeviceAuthentication by earlier builds. Incarnations stay so a deleted or replaced login cannot be
 * redeemed through an old opaque reference.
 *
 * Keys are hashes of (vault, entry tag), so the file names no site or username. Values are not secrets; only this app
 * writes them.
 */
class AgentSignInAccess(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    class State(val lastUsed: Long?, val incarnation: String)

    private val directory = context.applicationContext.noBackupFilesDir
    private val file = AtomicFile(File(directory, FILE))
    private fun <T> locked(action: () -> T): T = synchronized(LOCK) {
        directory.mkdirs()
        RandomAccessFile(File(directory, "agent-signin-access-v1.lock"), "rw").use { lock ->
            ProcessFileLock.lock(lock.channel).use { action() }
        }
    }

    fun get(vault: String, tag: String): State = locked {
        val all = read()
        val key = key(vault, tag)
        val entry = all.optJSONObject(key) ?: JSONObject()
        if (entry.optString("incarnation").isEmpty()) {
            entry.put("incarnation", UUID.randomUUID().toString())
            entry.put("store", hash(if (vault == "local") "local-passwords-v1" else vault))
            all.put(key, entry); write(all)
        }
        state(entry)
    }

    /** Called while the vault's entry lock is held. Incarnation checks serialize with the private handover. */
    internal fun <T> redeem(vault: String, tag: String, incarnation: String, action: () -> T): T = locked {
        val all = read()
        val entry = all.optJSONObject(key(vault, tag)) ?: throw SecurityException()
        val state = state(entry)
        if (state.incarnation != incarnation) throw SecurityException()
        val result = action()
        entry.put("lastUsed", now())
        write(all)
        result
    }

    private fun state(entry: JSONObject): State =
        State(entry.optLong("lastUsed", 0).takeIf { it > 0 }, entry.optString("incarnation"))

    /**
     * Called before the encrypted vault commit while its lock is held. Tags identify a login's
     * form, not its lifetime: a new vault or a remote delete/recreate can reuse the same tag.
     * Compare the persisted and proposed identities, even when replacement happens in one save.
     */
    internal fun onVaultSave(vault: String, before: VaultState, after: VaultState) = locked {
        fun parent(state: VaultState) = state.metadata?.let { Proto.decode(it).string(1) }
        if (parent(before) != parent(after)) {
            retainUnlocked(vaultPrefix(vault), emptySet())
        } else {
            fun committed(state: VaultState) = state.records.associate { raw ->
                val entity = Proto.decode(raw)
                entity.string(2) to entity.optionalString(1)
            }
            fun visible(state: VaultState): Map<String, String?> {
                val entries = committed(state).toMutableMap()
                state.mutations.forEach { mutation ->
                    if (mutation.kind == MutationKind.DELETE) entries.remove(mutation.tag)
                    else if (mutation.phase != MutationPhase.CONFLICT) mutation.desired?.let { desired ->
                        entries[mutation.tag] = Proto.decode(desired).optionalString(1)
                    }
                }
                return entries
            }
            val oldCommitted = committed(before)
            val newCommitted = committed(after)
            val oldVisible = visible(before)
            val newVisible = visible(after)
            val replaced = oldCommitted.keys.filter { it !in newCommitted || oldCommitted[it] != newCommitted[it] }.toSet() +
                oldVisible.keys.filter { it !in newVisible || oldVisible[it] != newVisible[it] }
            val keep = newVisible.keys.filter { it !in replaced }.map { key(vault, it) }.toSet()
            retainUnlocked(vaultPrefix(vault), keep)
        }
    }

    /** Drop records as soon as an entry leaves the visible vault, including sync tombstones. */
    fun retain(vault: String, tags: Set<String>) = locked {
        retainUnlocked(vaultPrefix(vault), tags.map { key(vault, it) }.toSet())
    }

    internal fun retainHashedVault(storeHash: String) = locked {
        // AndroidVaultStore hashes its identity; local vault uses a separate identity string.
        val vaultHash = if (storeHash == hash("local-passwords-v1")) hash("vault:local") else null
        // Google store identity is exactly the AgentSignInAccess vault key.
        // Hashed store cleanup is handled by the recorded store hash in each entry.
        val all = read()
        val stale = all.keys().asSequence().filter { key ->
            all.optJSONObject(key)?.optString("store") == storeHash || vaultHash != null && key.startsWith(vaultHash.take(16) + ":")
        }.toList()
        if (stale.isNotEmpty()) { stale.forEach(all::remove); write(all) }
    }

    private fun retainUnlocked(prefix: String, keep: Set<String>) {
        val all = read()
        val stale = all.keys().asSequence().filter { it.startsWith(prefix) && it !in keep }.toList()
        if (stale.isNotEmpty()) { stale.forEach(all::remove); write(all) }
    }

    private fun read(): JSONObject {
        val bytes = try { file.readFully() } catch (_: Exception) { return JSONObject() }
        return runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { JSONObject() }
    }

    private fun write(value: JSONObject) {
        val out = file.startWrite()
        try { out.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (failure: Exception) { file.failWrite(out); throw failure }
    }

    companion object {
        private val LOCK = Any()
        private const val FILE = "agent-signin-access-v1.json"
        /** The vault key used in [get]/[redeem]: "local" for the on-device vault, "google:<user id>" for an account's. */
        fun vaultKey(googleUserId: String?) = if (googleUserId == null) "local" else "google:$googleUserId"
        private fun vaultPrefix(vault: String) = hash("vault:$vault").take(16) + ":"
        private fun key(vault: String, tag: String) = vaultPrefix(vault) + hash("entry:$vault|$tag")
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
