/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AgentSignInAccessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val vault = "google:${UUID.randomUUID()}"
    private val file get() = File(context.noBackupFilesDir, "agent-signin-access-v1.json")

    /** CYC-3345: every matching saved login redeems directly and records lastUsed without Ask/Never/device-auth gates. */
    @Test fun aNewLoginRedeemsDirectlyAndRecordsLastUsed() {
        val access = AgentSignInAccess(context) { 1234 }
        val state = access.get(vault, "login")
        assertTrue(state.incarnation.isNotEmpty())
        assertNull(state.lastUsed)
        var deliveries = 0
        access.redeem(vault, "login", state.incarnation) { deliveries++ }
        assertEquals(1, deliveries)
        val reloaded = AgentSignInAccess(context).get(vault, "login")
        assertEquals(state.incarnation, reloaded.incarnation)
        assertEquals(1234L, reloaded.lastUsed)
    }

    /**
     * CYC-3345: logins persisted by earlier builds with Ask, Never, or requireDeviceAuthentication=true are treated as
     * Always, keeping their incarnation and lastUsed while redeeming directly with no device-auth requirement.
     */
    @Test fun legacyAskNeverAndRequireAuthEntriesRedeemDirectlyAsAlways() {
        try {
            val access = AgentSignInAccess(context) { 5678 }
            val ask = access.get(vault, "ask")
            val never = access.get(vault, "never")
            val strict = access.get(vault, "strict")
            val raw = JSONObject(file.readText())
            raw.keys().asSequence().toList().forEach { key ->
                val obj = raw.optJSONObject(key) ?: return@forEach
                when (obj.optString("incarnation")) {
                    ask.incarnation -> obj.put("level", "ask")
                    never.incarnation -> {
                        obj.put("level", "never")
                        obj.put("requireDeviceAuthentication", true)
                    }
                    strict.incarnation -> {
                        obj.put("level", "ask")
                        obj.put("requireDeviceAuthentication", true)
                    }
                }
            }
            raw.put("previousUnreadable", true)
            file.writeText(raw.toString())

            val reopened = AgentSignInAccess(context) { 5678 }
            for ((tag, state) in listOf("ask" to ask, "never" to never, "strict" to strict)) {
                val read = reopened.get(vault, tag)
                assertEquals(state.incarnation, read.incarnation)
                var delivered = false
                reopened.redeem(vault, tag, state.incarnation) { delivered = true }
                assertTrue("$tag must redeem directly", delivered)
                assertEquals(5678L, AgentSignInAccess(context).get(vault, tag).lastUsed)
            }
        } finally { file.delete() }
    }

    /** CYC-3345: an unreadable or corrupt file starts fresh and allows redemption under the new incarnation. */
    @Test fun anUnreadableFileStartsFreshAndAllowsRedemption() {
        try {
            file.parentFile?.mkdirs()
            file.writeText("{ not json")
            val access = AgentSignInAccess(context) { 9999 }
            val state = access.get(vault, "login")
            assertTrue(state.incarnation.isNotEmpty())
            var delivered = false
            access.redeem(vault, "login", state.incarnation) { delivered = true }
            assertTrue(delivered)
            assertEquals(9999L, AgentSignInAccess(context).get(vault, "login").lastUsed)
        } finally { file.delete() }
    }

    @Test fun deletingAndRecreatingSameTagRotatesIncarnationAndRefusesStaleRef() {
        val access = AgentSignInAccess(context) { 1234 }
        val original = access.get(vault, "login")
        access.redeem(vault, "login", original.incarnation) { }
        access.retain(vault, emptySet())
        val replacement = access.get(vault, "login")
        assertNotEquals(original.incarnation, replacement.incarnation)
        assertNull(replacement.lastUsed)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation) { fail() } }
        var delivered = false
        access.redeem(vault, "login", replacement.incarnation) { delivered = true }
        assertTrue(delivered)
    }

    @Test fun vaultsAndOtherEntriesRemainIndependent() {
        val access = AgentSignInAccess(context)
        val other = "$vault-other"
        val a = access.get(vault, "a")
        val b = access.get(vault, "b")
        val c = access.get(other, "a")
        access.retain(vault, setOf("b"))
        assertNotEquals(a.incarnation, access.get(vault, "a").incarnation)
        assertEquals(b.incarnation, access.get(vault, "b").incarnation)
        assertEquals(c.incarnation, access.get(other, "a").incarnation)
    }

    private fun snapshot(parent: String, resource: String, payload: String = "encrypted-original") = VaultState(
        metadata = Proto.field(1, parent),
        records = arrayListOf(Proto.field(1, resource) + Proto.field(2, "login") + Proto.field(6, payload))
    )

    @Test fun singleSaveVaultReplacementRotatesIncarnationEvenWhenLoginTagAndResourceNameAreReused() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        val before = snapshot("users/me/birthdays/old", "passwords/same")
        val replacement = snapshot("users/me/birthdays/new", "passwords/same", "encrypted-replacement")
        access.onVaultSave(vault, before, replacement)
        val current = access.get(vault, "login")
        assertNotEquals(original.incarnation, current.incarnation)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation) { fail() } }
    }

    @Test fun singleSaveRemoteDeleteAndRecreateRotatesSameTagAndPreservesUnchangedEntries() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        val other = access.get(vault, "other")
        val before = snapshot("parent", "parent/passwords/old")
        val replacement = snapshot("parent", "parent/passwords/new", "encrypted-replacement")
        val unchanged = Proto.field(1, "parent/passwords/other") + Proto.field(2, "other")
        before.records.add(unchanged); replacement.records.add(unchanged)
        access.onVaultSave(vault, before, replacement)
        assertNotEquals(original.incarnation, access.get(vault, "login").incarnation)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation) { fail() } }
        assertEquals(other.incarnation, access.get(vault, "other").incarnation)
    }

    @Test fun ordinaryPasswordEditPreservesExistingResourceIncarnation() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.onVaultSave(vault, snapshot("parent", "parent/passwords/same"),
            snapshot("parent", "parent/passwords/same", "encrypted-password-edit"))
        assertEquals(original.incarnation, access.get(vault, "login").incarnation)
        access.redeem(vault, "login", original.incarnation) { }
    }
}
