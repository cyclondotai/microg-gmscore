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
 * Whether the phone's assistant may sign in with a saved login (CYC-779). Local to this phone and never synced: a
 * login's access is the owner's choice for this device's assistant, not account data.
 *
 * Always is the default since CYC-2136 (2026-11-14): Cyclon signs in with a saved login without Core's sheet unless the
 * owner chose Ask or Never for it. Until then Ask was the default and was stored for every login that was ever listed,
 * so the file cannot tell it from an owner's own choice: a file from before ([DEFAULT_ALWAYS] not set) has its Ask
 * entries read as Always, once. A login with "Require device authentication" stays Ask, as that setting demands.
 *
 * Keys are hashes of (vault, entry tag), so the file names no site or username. Values are not secrets; only this app
 * writes them. No file yet means every login is at the default. A file that is there but cannot be read may have held
 * a Never or an authentication requirement, so it falls back to Ask, not to Always: the file started in its place is
 * marked ([UNREADABLE]) and a login without an entry in it is Ask until the owner chooses for it.
 */
class AgentSignInAccess(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    enum class Level(val wire: String) {
        ASK("ask"), ALWAYS("always"), NEVER("never");
        companion object { fun of(wire: String?): Level? = values().firstOrNull { it.wire == wire } }
    }
    class State(val level: Level, val lastUsed: Long?, val incarnation: String, val requireDeviceAuthentication: Boolean = false)

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
        val entry = all.optJSONObject(key)
            ?: JSONObject().put("level", (if (all.optBoolean(UNREADABLE)) Level.ASK else Level.ALWAYS).wire)
        if (!entry.has("incarnation")) {
            entry.put("incarnation", UUID.randomUUID().toString())
            entry.put("store", hash(if (vault == "local") "local-passwords-v1" else vault))
            all.put(key, entry); write(all)
        }
        state(entry)
    }

    /** Called while the vault's entry lock is held. Revocations serialize with the private handover. */
    internal fun <T> redeem(vault: String, tag: String, incarnation: String, once: Boolean, deviceAuthenticated: Boolean = false, action: () -> T): T = locked {
        val all = read()
        val entry = all.optJSONObject(key(vault, tag)) ?: throw SecurityException()
        val state = state(entry)
        if (state.incarnation != incarnation || state.level == Level.NEVER || state.level != Level.ALWAYS && !once ||
            state.requireDeviceAuthentication && !deviceAuthenticated) throw SecurityException()
        val result = action()
        entry.put("lastUsed", now())
        write(all)
        result
    }

    private fun state(entry: JSONObject): State {
        val required = entry.optBoolean("requireDeviceAuthentication", false)
        val level = Level.of(entry.optString("level")) ?: Level.ASK
        return State(if (required && level == Level.ALWAYS) Level.ASK else level,
            entry.optLong("lastUsed", 0).takeIf { it > 0 }, entry.optString("incarnation"), required)
    }

    fun setCurrent(vault: String, tag: String, incarnation: String, level: Level) = locked {
        val all = read()
        val entry = all.optJSONObject(key(vault, tag)) ?: throw SecurityException()
        val current = state(entry)
        if (current.incarnation != incarnation || current.requireDeviceAuthentication && level == Level.ALWAYS) throw SecurityException()
        entry.put("level", level.wire)
        write(all)
    }

    /** Owner settings only. Core's set_access cannot remove the authentication requirement. */
    fun setOwnerPolicy(vault: String, tag: String, incarnation: String, level: Level, requireDeviceAuthentication: Boolean) = locked {
        val all = read()
        val entry = all.optJSONObject(key(vault, tag)) ?: throw SecurityException()
        val current = state(entry)
        if (current.incarnation != incarnation) throw SecurityException()
        if (current.requireDeviceAuthentication != requireDeviceAuthentication) {
            // Invalidate prompts and pending authentication in both directions. An old ref
            // must never become a more permissive capability after settings change.
            entry.put("incarnation", UUID.randomUUID().toString())
        }
        entry.put("requireDeviceAuthentication", requireDeviceAuthentication)
        entry.put("level", if (requireDeviceAuthentication && level == Level.ALWAYS) Level.ASK.wire else level.wire)
        write(all)
    }

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

    /** Drop choices as soon as an entry leaves the visible vault, including sync tombstones. */
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
        val unreadable = { JSONObject().put(DEFAULT_ALWAYS, true).put(UNREADABLE, true) }
        // "No file yet" only when nothing is stored: Android also throws FileNotFoundException for a file that is there
        // but cannot be opened (permission denied), and that one may hold a Never. The .bak is AtomicFile's older
        // way to keep the committed copy during a write.
        val stored = File(directory, FILE).exists() || File(directory, "$FILE.bak").exists()
        val bytes = try { file.readFully() }
            catch (_: Exception) { return if (stored) unreadable() else JSONObject().put(DEFAULT_ALWAYS, true) }
        val all = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { return unreadable() }
        if (!all.optBoolean(DEFAULT_ALWAYS)) {
            // A file from when Ask was the default. Whoever writes next stores this with its change.
            all.keys().asSequence().toList().forEach { key ->
                val entry = all.optJSONObject(key) ?: return@forEach
                if (entry.optString("level") == Level.ASK.wire && !entry.optBoolean("requireDeviceAuthentication", false))
                    entry.put("level", Level.ALWAYS.wire)
            }
            all.put(DEFAULT_ALWAYS, true)
        }
        return all
    }

    private fun write(value: JSONObject) {
        val out = file.startWrite()
        try { out.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(out) }
        catch (failure: Exception) { file.failWrite(out); throw failure }
    }

    companion object {
        private val LOCK = Any()
        private const val FILE = "agent-signin-access-v1.json"
        // Two marks of the file itself. Neither can be a login's key: those are hashes with a colon.
        /** Written by this version: Always is the default, so an Ask in the file is the owner's own choice. */
        private const val DEFAULT_ALWAYS = "defaultAlways"
        /** The file was started after one that could not be read: a login without an entry in it is Ask. */
        private const val UNREADABLE = "previousUnreadable"
        /** The vault key used in [get]/[set]: "local" for the on-device vault, "google:<user id>" for an account's. */
        fun vaultKey(googleUserId: String?) = if (googleUserId == null) "local" else "google:$googleUserId"
        private fun vaultPrefix(vault: String) = hash("vault:$vault").take(16) + ":"
        private fun key(vault: String, tag: String) = vaultPrefix(vault) + hash("entry:$vault|$tag")
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
