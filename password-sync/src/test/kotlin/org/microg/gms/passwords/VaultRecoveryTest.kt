/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VaultRecoveryTest {
    @Test fun recoveredSharedKeysMustDecryptTheActualKeybagBeforeReplacingDurableRecoveryKeys() {
        val shared = ByteArray(32) { (it + 1).toByte() }
        val key = NigoriKey.derive(Base64.getEncoder().encode(shared))
        val metadata = try { Proto.field(1, "users/me/birthdays/fixture") + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, key.export()))) + Proto.integer(2, 5)) } finally { key.destroy() }
        val session = Session(VaultState(metadata = metadata, trustedVaultKeys = mutableListOf(ByteArray(32) { 9 })))
        val original = session.load().trustedVaultKeys.single()
        assertThrows(VaultLockedException::class.java) { VaultRecovery.acceptTrustedKeys(session, listOf(ByteArray(32))) }
        assertArrayEquals(original, session.load().trustedVaultKeys.single())
        VaultRecovery.acceptTrustedKeys(session, listOf(shared))
        val expected = shared.copyOf(); shared.fill(0)
        assertArrayEquals(expected, session.load().trustedVaultKeys.single())
        VaultKeys.unlock(metadata, trustedVaultKeys = session.load().trustedVaultKeys).destroy()
    }
    private class Session(initial: VaultState) : VaultSession {
        private var bytes = initial.encode()
        override fun load() = VaultState.decode(bytes)
        override fun save(state: VaultState) { bytes = state.encode() }
    }
    private fun <T> withKeys(action: (VaultKeys) -> T): T {
        val key = NigoriKey.derive("fixture recovery key".toByteArray()); val exported = key.export()
        val metadata = Proto.field(1, "users/me/birthdays/fixture") + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, exported))) + Proto.integer(2, 4))
        val keys = VaultKeys.unlock(metadata, customKey = exported); key.destroy(); exported.fill(0)
        return try { action(keys) } finally { keys.destroy() }
    }
    private fun item() = PasswordData.create("https://fixture.invalid/", "https://fixture.invalid/", "fixture", "original", 1234L)
    private fun resource(data: PasswordData, keys: VaultKeys, version: Byte) = PasswordResourceCodec.encrypt(data, keys,
        Proto.field(1, "users/me/birthdays/fixture/passwords/one") + Proto.field(2, data.tag()) + Proto.field(5, byteArrayOf(version)))

    @Test fun retryOfAnUncertainAttemptRequiresOwnerAcknowledgementAndNewIdentity() = withKeys { keys ->
        val data = item(); val remote = resource(data.withPassword("remote fixture", 2345L), keys, 2)
        val desired = resource(data.withPassword("local fixture", 2345L).withDefaultNote("local note", 2345L), keys, 1)
        val pending = Mutation("old-attempt", MutationKind.UPDATE, data.tag(), "old-name", byteArrayOf(1), desired, MutationPhase.INDETERMINATE)
        val session = Session(VaultState(records = mutableListOf(remote), mutations = mutableListOf(pending), lastSyncMillis = 10))
        assertThrows(PendingMutationException::class.java) { VaultRecovery.retry(session, pending.id, keys, false) }
        assertEquals(pending.id, session.load().mutations.single().id)
        val replacement = VaultRecovery.retry(session, pending.id, keys, true)
        assertNotEquals(pending.id, replacement)
        val next = session.load().mutations.single()
        assertEquals(MutationPhase.QUEUED, next.phase); assertEquals(MutationKind.UPDATE, next.kind)
        assertArrayEquals(byteArrayOf(2), next.baseVersion)
        assertEquals(Proto.decode(remote).string(1), next.name)
        assertEquals("local fixture", PasswordResourceCodec.read(next.desired!!, keys).password)
        assertEquals("local note", PasswordResourceCodec.read(next.desired!!, keys).defaultNote!!.value)
    }
    @Test fun retryOfADisappearedUpdateCreatesOnlyAfterExplicitOwnerChoice() = withKeys { keys ->
        val data = item(); val desired = resource(data, keys, 1)
        val pending = Mutation("attempt", MutationKind.UPDATE, data.tag(), "old", byteArrayOf(1), desired, MutationPhase.CONFLICT)
        val session = Session(VaultState(mutations = mutableListOf(pending), lastSyncMillis = 10))
        VaultRecovery.retry(session, pending.id, keys, false)
        val next = session.load().mutations.single()
        assertEquals(MutationKind.CREATE, next.kind); assertNull(next.name); assertNull(next.baseVersion)
        assertNull(Proto.decode(next.desired!!).optional(1)); assertNull(Proto.decode(next.desired!!).optional(5))
    }
    @Test fun droppingAnUncertainAttemptDoesNotChangeTheAuthenticatedServerSnapshot() = withKeys { keys ->
        val data = item(); val remote = resource(data, keys, 2)
        val pending = Mutation("attempt", MutationKind.DELETE, data.tag(), "one", byteArrayOf(1), null, MutationPhase.INDETERMINATE)
        val session = Session(VaultState(records = mutableListOf(remote), mutations = mutableListOf(pending)))
        VaultRecovery.drop(session, pending.id)
        assertTrue(session.load().mutations.isEmpty()); assertArrayEquals(remote, session.load().records.single())
    }
    @Test fun replacementVaultRecoveryDiscardsOldOutboxesAndCachesWithoutTouchingTheServer() {
        val pending = Mutation("attempt", MutationKind.DELETE, "fixture", "one", byteArrayOf(1), null)
        val session = Session(VaultState(metadata = Proto.field(1, "users/me/birthdays/old"),
            records = mutableListOf(byteArrayOf(1)), mutations = mutableListOf(pending),
            pendingMetadata = Proto.field(1, "users/me/birthdays/new"), preferenceMutations = mutableListOf(pending),
            affiliationCache = byteArrayOf(2), directAffiliationProofs = byteArrayOf(3)))
        VaultRecovery.acceptRemoteReset(session)
        val result = session.load()
        assertEquals("users/me/birthdays/new", Proto.decode(result.metadata!!).string(1))
        assertTrue(result.records.isEmpty()); assertTrue(result.mutations.isEmpty()); assertTrue(result.preferenceMutations.isEmpty())
        assertNull(result.pendingMetadata); assertNull(result.affiliationCache); assertNull(result.directAffiliationProofs)
    }
    @Test fun retryRefusesPendingMetadataAndAnUnauthenticatedSnapshot(): Unit = withKeys { keys ->
        val pending = Mutation("attempt", MutationKind.DELETE, "fixture", "one", byteArrayOf(1), null)
        val session = Session(VaultState(mutations = mutableListOf(pending)))
        assertThrows(VaultLockedException::class.java) { VaultRecovery.retry(session, pending.id, keys, true) }
        session.save(session.load().also { it.lastSyncMillis = 10; it.pendingMetadata = byteArrayOf(1) })
        assertThrows(VaultLockedException::class.java) { VaultRecovery.retry(session, pending.id, keys, true) }
    }

    private class MemoryStore(initial: VaultState) : VaultStore {
        private val session = Session(initial)
        override fun <T> locked(action: (VaultSession) -> T): T = action(session)
        fun current(): VaultState = session.load()
    }

    private fun customPassphraseMetadata(parent: String, passphrase: String): Pair<ByteArray, ByteArray> {
        val key = NigoriKey.derive(passphrase.toByteArray())
        val exported = key.export()
        val metadata = Proto.field(1, parent) + Proto.field(3,
            Proto.field(1, key.encrypt(Proto.field(2, exported))) + Proto.integer(2, 4))
        key.destroy()
        return metadata to exported
    }

    @Test fun acceptCustomKeyRefreshesColdAndStaleMetadataIntoPendingWithoutOverwritingSnapshotMetadata() {
        val (meta1, key1) = customPassphraseMetadata("users/me/birthdays/fixture1", "passphrase-one")
        val (meta2, key2) = customPassphraseMetadata("users/me/birthdays/fixture2", "passphrase-two")
        val (_, wrongKey) = customPassphraseMetadata("users/me/birthdays/fixture2", "wrong-passphrase")
        try {
            // 1) Cold vault (no local metadata): synchronously fetches metadata into pendingMetadata, keeping metadata null so list(true) still syncs.
            val cold = MemoryStore(VaultState())
            var fetches = 0
            VaultRecovery.acceptCustomKey(cold, key1) { fetches++; meta1 }
            assertEquals(1, fetches)
            assertNull("metadata must remain null until authenticated snapshot sync", cold.current().metadata)
            assertArrayEquals(meta1, cold.current().pendingMetadata)
            assertArrayEquals(key1, cold.current().customKey)
            assertTrue(VaultRecovery.needsCustomKeyRefresh(cold.current()))

            // 2) Warm vault (cached pendingMetadata matches): validates locally with zero network calls.
            VaultRecovery.acceptCustomKey(cold, key1) { fetches++; meta1 }
            assertEquals(1, fetches)

            // 3) Stale cached metadata after passphrase rotation: fetches fresh metadata into pendingMetadata and triggers refresh.
            val rotated = MemoryStore(VaultState(metadata = meta1, customKey = key1.copyOf()))
            assertFalse(VaultRecovery.needsCustomKeyRefresh(rotated.current()))
            VaultRecovery.acceptCustomKey(rotated, key2) { fetches++; meta2 }
            assertEquals(2, fetches)
            assertArrayEquals("old snapshot metadata untouched until sync", meta1, rotated.current().metadata)
            assertArrayEquals(meta2, rotated.current().pendingMetadata)
            assertArrayEquals(key2, rotated.current().customKey)
            assertTrue(VaultRecovery.needsCustomKeyRefresh(rotated.current()))

            // 4) Wrong key: throws PassphraseRequiredException and leaves stored customKey/pendingMetadata intact.
            assertThrows(PassphraseRequiredException::class.java) {
                VaultRecovery.acceptCustomKey(rotated, wrongKey) { fetches++; meta2 }
            }
            assertEquals(3, fetches)
            assertArrayEquals(key2, rotated.current().customKey)

            // 5) Remote reset with pending local outbox: needsCustomKeyRefresh stays false so outbox is not discarded silently.
            val pendingOutbox = Mutation("m1", MutationKind.DELETE, "tag1", "name1", byteArrayOf(1), null)
            val withOutbox = VaultState(metadata = meta1, pendingMetadata = meta2, customKey = key2.copyOf(), mutations = mutableListOf(pendingOutbox))
            assertFalse(VaultRecovery.needsCustomKeyRefresh(withOutbox))
        } finally {
            key1.fill(0); key2.fill(0); wrongKey.fill(0)
        }
    }
}
