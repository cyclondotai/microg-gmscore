/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.security.MessageDigest
import java.util.UUID

/** These actions are owner decisions, never background retry policy. */
object VaultRecovery {
    /** Verified domain reset invalidates cached recovery authority without erasing encrypted owner data. */
    fun clearTrustedKeys(session: VaultSession, expectedParent: String?, authorize: () -> Unit = {}) {
        expectedParent?.let(PasswordApi::checkedParent)
        authorize()
        val state = session.load()
        if (state.metadata == null && state.trustedVaultKeys.isEmpty() && state.versionedTrustedVaultKeys == null) return
        val metadata = state.metadata ?: throw VaultLockedException()
        if (expectedParent != null && (Proto.decode(metadata).string(1) != expectedParent || state.pendingMetadata?.let {
                Proto.decode(it).string(1) != expectedParent
            } == true)) throw VaultResetException()
        authorize()
        state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
        state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
        session.save(state)
    }

    fun acceptTrustedKeys(session: VaultSession, keys: List<ByteArray>) {
        if (keys.isEmpty() || keys.size > 100 || keys.any { it.size !in 16..4096 }) throw ProtocolException()
        val state = session.load()
        val metadata = trustedMetadata(state)
        VaultKeys.unlock(metadata, trustedVaultKeys = keys).destroy()
        state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
        state.trustedVaultKeys.addAll(keys.map { it.copyOf() })
        // This legacy API establishes unlock material only, without asserting any epoch.
        state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
        session.save(state)
    }
    fun trustedMetadata(state: VaultState): ByteArray {
        val metadata = state.pendingMetadata ?: state.metadata ?: throw VaultLockedException()
        if (state.pendingMetadata != null && state.metadata != null && Proto.decode(metadata).string(1) != Proto.decode(state.metadata!!).string(1)) throw VaultResetException()
        PasswordApi.checkedParent(Proto.decode(metadata).string(1))
        if (Proto.decode(Proto.decode(metadata).one(3)).integer(2) != 5L) throw VaultLockedException()
        return metadata
    }
    /** Explicit owner recovery; the epoch/material pairs are retained only after real keybag verification. */
    fun acceptVersionedTrustedKeys(session: VaultSession, keys: List<VersionedTrustedVaultKey>, expectedParent: String,
        authorize: () -> Unit = {}) {
        val recovered = VersionedTrustedVaultKeys(PasswordApi.checkedParent(expectedParent), keys)
        val raw = ArrayList<ByteArray>()
        var retained = false
        try {
            keys.forEach { raw.add(it.material) }
            authorize()
            val state = session.load()
            val metadata = trustedMetadata(state)
            if (Proto.decode(metadata).string(1) != expectedParent) throw VaultResetException()
            state.versionedTrustedVaultKeys?.let { previous ->
                if (previous.parent != expectedParent) throw VaultResetException()
                val old = previous.keys
                try {
                    for (key in old) {
                        val next = keys.singleOrNull { it.epoch == key.epoch } ?: continue
                        val first = key.material; val second = next.material
                        try { if (!MessageDigest.isEqual(first, second)) throw ProtocolException() }
                        finally { first.fill(0); second.fill(0) }
                    }
                } finally { old.forEach { it.destroy() } }
            }
            VaultKeys.unlock(metadata, trustedVaultKeys = raw).destroy()
            // A WebView success, key parsing, or a previously valid owner grant alone
            // cannot authorize a late write after owner/account revocation.
            authorize()
            state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
            state.trustedVaultKeys.addAll(raw.map { it.copyOf() })
            state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = recovered
            session.save(state)
            retained = true
        } finally { raw.forEach { it.fill(0) }; if (!retained) recovered.destroy() }
    }
    fun drop(session: VaultSession, operationId: String) {
        val state = session.load()
        val operation = state.mutations.singleOrNull { it.id == operationId } ?: throw ConflictException()
        state.mutations.remove(operation)
        if (state.records.none { Proto.decode(it).string(2) == operation.tag }) state.chromeLocalData.remove(operation.tag)
        session.save(state)
    }

    /** Call only after a complete refresh and explicit acknowledgement of an uncertain effect. */
    fun retry(session: VaultSession, operationId: String, keys: VaultKeys, acknowledgeUncertain: Boolean): String? {
        val state = session.load()
        if (state.pendingMetadata != null || state.lastSyncMillis <= 0) throw VaultLockedException()
        val operation = state.mutations.singleOrNull { it.id == operationId } ?: throw ConflictException()
        if (operation.phase in setOf(MutationPhase.LAUNCHED, MutationPhase.INDETERMINATE) && !acknowledgeUncertain)
            throw PendingMutationException()
        val remote = state.records.singleOrNull { Proto.decode(it).string(2) == operation.tag }
        val base = remote?.let(Proto::decode)
        val kind = when {
            operation.kind == MutationKind.DELETE -> MutationKind.DELETE
            remote == null -> MutationKind.CREATE
            else -> MutationKind.UPDATE
        }
        val desired = if (kind == MutationKind.DELETE) null else PasswordResourceCodec.encrypt(
            PasswordResourceCodec.read(operation.desired ?: throw ProtocolException(), keys), keys, remote)
        val replacement = if (kind == MutationKind.DELETE && remote == null) null else Mutation(
            UUID.randomUUID().toString(), kind, operation.tag, base?.string(1), base?.optional(5), desired)
        state.mutations.remove(operation)
        replacement?.let(state.mutations::add)
        session.save(state)
        return replacement?.id
    }

    /**
     * Validates explicit passphrase key material ([exported]) against cached Nigori metadata, or synchronously fetches
     * fresh metadata when local metadata is missing (cold vault) or stale (passphrase change/reset). Never overwrites
     * [VaultState.metadata] before an authenticated snapshot sync; fresh metadata is stored in [VaultState.pendingMetadata].
     */
    fun acceptCustomKey(
        store: VaultStore,
        exported: ByteArray,
        expectedVaultParent: String? = null,
        authorize: () -> Unit = {},
        fetchMetadata: () -> ByteArray
    ) {
        val unlockedLocally = store.locked { session ->
            authorize()
            val state = session.load()
            val cached = state.pendingMetadata ?: state.metadata ?: return@locked false
            if (expectedVaultParent != null && Proto.decode(cached).string(1) != expectedVaultParent) throw VaultResetException()
            try {
                VaultKeys.unlock(cached, customKey = exported).destroy()
            } catch (_: VaultLockedException) {
                return@locked false
            }
            state.customKey = exported.copyOf()
            session.save(state)
            true
        }
        if (unlockedLocally) return
        val fetched = fetchMetadata()
        val newParent = PasswordApi.checkedParent(Proto.decode(fetched).string(1))
        if (expectedVaultParent != null && newParent != expectedVaultParent) throw VaultResetException()
        store.locked { session ->
            authorize()
            val state = session.load()
            VaultKeys.unlock(fetched, customKey = exported).destroy()
            val oldParent = state.metadata?.let { Proto.decode(it).string(1) }
            if (oldParent != null && oldParent != newParent) {
                state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
            }
            if (state.metadata == null || !state.metadata!!.contentEquals(fetched)) {
                state.pendingMetadata = fetched
            }
            state.customKey = exported.copyOf()
            session.save(state)
        }
    }

    /** True when [state] has no authenticated metadata yet, or has newer explicit-passphrase [VaultState.pendingMetadata] unlocked by [VaultState.customKey] without blocking a pending remote-reset outbox. */
    fun needsCustomKeyRefresh(state: VaultState): Boolean {
        val current = state.metadata ?: return true
        val pending = state.pendingMetadata ?: return false
        val customKey = state.customKey ?: return false
        if (current.contentEquals(pending)) return false
        if (Proto.decode(current).string(1) != Proto.decode(pending).string(1) && state.mutations.isNotEmpty()) return false
        val pendingType = Proto.decode(Proto.decode(pending).one(3)).integer(2)
        if (pendingType !in setOf(1L, 3L, 4L)) return false
        return try { VaultKeys.unlock(pending, customKey = customKey).destroy(); true } catch (_: VaultLockedException) { false }
    }

    /** Keeps no stale outbox eligible for the replacement vault. Never resets Google's vault. */
    fun acceptRemoteReset(session: VaultSession) {
        val state = session.load()
        val pending = state.pendingMetadata ?: throw ConflictException()
        val previous = state.metadata ?: throw ConflictException()
        if (Proto.decode(previous).string(1) == Proto.decode(pending).string(1)) throw ConflictException()
        state.metadata = pending; state.pendingMetadata = null
        state.records.clear(); state.mutations.clear(); state.chromeLocalData.clear()
        state.preferences.clear(); state.preferenceMutations.clear(); state.preferenceParent = null
        state.deviceRegistration = null; state.affiliationCache = null; state.directAffiliationProofs = null; state.lastSyncMillis = 0
        state.trustedVaultKeys.forEach { it.fill(0) }; state.trustedVaultKeys.clear()
        state.versionedTrustedVaultKeys?.destroy(); state.versionedTrustedVaultKeys = null
        session.save(state)
    }
}
