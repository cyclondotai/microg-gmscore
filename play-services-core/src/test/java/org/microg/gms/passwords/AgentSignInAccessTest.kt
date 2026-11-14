/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class AgentSignInAccessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val vault = "google:${UUID.randomUUID()}"

    @Test fun lockedBackgroundDeliveryCannotConvertAskNeverOrFreshAuthenticationIntoAnAlwaysGrant() {
        val access = AgentSignInAccess(context)
        for (level in AgentSignInAccess.Level.values()) for (once in listOf(false, true)) for (authentication in listOf(false, true)) {
            val tag = "locked-$level-$once-$authentication"
            val initial = access.get(vault, tag)
            access.setOwnerPolicy(vault, tag, initial.incarnation, level, authentication)
            val state = access.get(vault, tag)
            var delivered = false
            val action = {
                access.redeem(vault, tag, state.incarnation, once, deviceAuthenticated = authentication) {
                    if (!AgentSignInLockPolicy.deliveryAllowed(true, true, once, authentication)) throw SecurityException()
                    delivered = true
                }
            }
            if (level == AgentSignInAccess.Level.ALWAYS && !once && !authentication) action()
            else assertThrows(SecurityException::class.java) { action() }
            assertEquals(level == AgentSignInAccess.Level.ALWAYS && !once && !authentication, delivered)
        }
    }

    /** CYC-2136: a login nobody chose for is Always, so Core fills it without its sheet. Never still refuses it. */
    @Test fun aNewLoginIsAlwaysAndNeverRefusesIt() {
        val access = AgentSignInAccess(context) { 1234 }
        val state = access.get(vault, "login")
        assertEquals(AgentSignInAccess.Level.ALWAYS, state.level)
        assertNull(state.lastUsed)
        var deliveries = 0
        access.redeem(vault, "login", state.incarnation, false) { deliveries++ }
        assertEquals(1, deliveries)
        assertEquals(1234L, AgentSignInAccess(context).get(vault, "login").lastUsed)
        access.setCurrent(vault, "login", state.incarnation, AgentSignInAccess.Level.NEVER)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", state.incarnation, false) { fail() } }
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", state.incarnation, true) { fail() } }
    }

    private val file get() = java.io.File(context.noBackupFilesDir, "agent-signin-access-v1.json")

    /**
     * Ask was the default until CYC-2136 and was stored for every login listed, so a file from then has its Ask entries
     * read as Always, once. Never and a login with Require device authentication keep what they had, and an Ask the
     * owner chooses afterwards stays Ask.
     */
    @Test fun aFileFromBeforeHasItsAskReadAsAlwaysOnceAndALaterAskStays() {
        try {
            val before = AgentSignInAccess(context)
            val plain = before.get(vault, "plain")
            before.setCurrent(vault, "plain", plain.incarnation, AgentSignInAccess.Level.ASK)
            val never = before.get(vault, "never")
            before.setCurrent(vault, "never", never.incarnation, AgentSignInAccess.Level.NEVER)
            val first = before.get(vault, "strict")
            before.setOwnerPolicy(vault, "strict", first.incarnation, AgentSignInAccess.Level.ASK, true)
            val strict = before.get(vault, "strict")
            // The same file as the version before this one wrote it: without the mark.
            val marked = file.readText()
            assertTrue(marked.contains("\"defaultAlways\":true"))
            file.writeText(org.json.JSONObject(marked).apply { remove("defaultAlways") }.toString())

            val access = AgentSignInAccess(context)
            val read = access.get(vault, "plain")
            assertEquals(AgentSignInAccess.Level.ALWAYS, read.level)
            assertEquals(plain.incarnation, read.incarnation)
            assertEquals(AgentSignInAccess.Level.NEVER, access.get(vault, "never").level)
            val stillStrict = access.get(vault, "strict")
            assertEquals(AgentSignInAccess.Level.ASK, stillStrict.level)
            assertTrue(stillStrict.requireDeviceAuthentication)
            assertEquals(strict.incarnation, stillStrict.incarnation)
            assertThrows(SecurityException::class.java) { access.redeem(vault, "strict", strict.incarnation, true) { fail() } }
            var delivered = false
            access.redeem(vault, "plain", plain.incarnation, false) { delivered = true }
            assertTrue(delivered)

            // The owner's own Ask, chosen now, is kept: by this instance and by the next one.
            access.setOwnerPolicy(vault, "plain", plain.incarnation, AgentSignInAccess.Level.ASK, false)
            assertEquals(AgentSignInAccess.Level.ASK, AgentSignInAccess(context).get(vault, "plain").level)
            assertThrows(SecurityException::class.java) { AgentSignInAccess(context).redeem(vault, "plain", plain.incarnation, false) { fail() } }
        } finally { file.delete() }
    }

    /** A file that cannot be read may have held a Never or an authentication requirement: it falls back to Ask, not Always. */
    @Test fun anUnreadableFileFallsBackToAskAlsoForLoginsSeenLater() {
        try {
            val before = AgentSignInAccess(context)
            val never = before.get(vault, "never")
            before.setCurrent(vault, "never", never.incarnation, AgentSignInAccess.Level.NEVER)
            file.writeText("{ not json")
            val access = AgentSignInAccess(context)
            val state = access.get(vault, "never")
            assertEquals(AgentSignInAccess.Level.ASK, state.level)
            assertThrows(SecurityException::class.java) { access.redeem(vault, "never", state.incarnation, false) { fail() } }
            // The file is started again by that read; a login first seen afterwards is Ask too.
            val later = AgentSignInAccess(context).get(vault, "later")
            assertEquals(AgentSignInAccess.Level.ASK, later.level)
            assertThrows(SecurityException::class.java) { AgentSignInAccess(context).redeem(vault, "later", later.incarnation, false) { fail() } }
            // The owner's choice for a login is kept as usual.
            access.setOwnerPolicy(vault, "later", later.incarnation, AgentSignInAccess.Level.ALWAYS, false)
            var delivered = false
            AgentSignInAccess(context).redeem(vault, "later", later.incarnation, false) { delivered = true }
            assertTrue(delivered)
        } finally { file.delete() }
    }

    /** A file that is there but cannot be opened is not "no file yet": Android reports both as FileNotFoundException. */
    @Test fun aFileThatCannotBeOpenedFallsBackToAskAndKeepsNothingAllowed() {
        try {
            val before = AgentSignInAccess(context)
            val never = before.get(vault, "never")
            before.setCurrent(vault, "never", never.incarnation, AgentSignInAccess.Level.NEVER)
            val strict = before.get(vault, "strict")
            before.setOwnerPolicy(vault, "strict", strict.incarnation, AgentSignInAccess.Level.ALWAYS, true)
            assertTrue(file.setReadable(false))
            org.junit.Assume.assumeFalse("runs as a user who can read any file", file.canRead())
            val access = AgentSignInAccess(context)
            for (tag in listOf("never", "strict", "new")) {
                val state = access.get(vault, tag)
                assertEquals(AgentSignInAccess.Level.ASK, state.level)
                assertThrows(SecurityException::class.java) { access.redeem(vault, tag, state.incarnation, false) { fail() } }
            }
        } finally { file.setReadable(true); file.delete() }
    }

    @Test fun askRequiresOwnerOnceAndNeverOverridesOwnerOnce() {
        val access = AgentSignInAccess(context) { 1234 }
        val initial = access.get(vault, "login")
        access.setCurrent(vault, "login", initial.incarnation, AgentSignInAccess.Level.ASK)
        val state = access.get(vault, "login")
        assertEquals(AgentSignInAccess.Level.ASK, state.level)
        assertNull(state.lastUsed)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", state.incarnation, false) { fail() } }
        var deliveries = 0
        access.redeem(vault, "login", state.incarnation, true) { deliveries++ }
        assertEquals(1, deliveries)
        assertEquals(1234L, access.get(vault, "login").lastUsed)
        assertEquals(AgentSignInAccess.Level.ASK, access.get(vault, "login").level)
        access.setCurrent(vault, "login", state.incarnation, AgentSignInAccess.Level.NEVER)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", state.incarnation, true) { fail() } }
    }

    @Test fun alwaysPersistsAcrossInstancesAndRevocationIsImmediate() {
        val access = AgentSignInAccess(context)
        val state = access.get(vault, "login")
        access.setCurrent(vault, "login", state.incarnation, AgentSignInAccess.Level.ALWAYS)
        val otherProcessView = AgentSignInAccess(context)
        assertEquals(AgentSignInAccess.Level.ALWAYS, otherProcessView.get(vault, "login").level)
        otherProcessView.redeem(vault, "login", state.incarnation, false) { }
        access.setCurrent(vault, "login", state.incarnation, AgentSignInAccess.Level.ASK)
        assertThrows(SecurityException::class.java) { otherProcessView.redeem(vault, "login", state.incarnation, false) { fail() } }
    }

    @Test fun deletingAndRecreatingSameTagDoesNotInheritPermissionOrOldApproval() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.retain(vault, emptySet())
        val replacement = access.get(vault, "login")
        assertNotEquals(original.incarnation, replacement.incarnation)
        assertEquals(AgentSignInAccess.Level.ALWAYS, replacement.level)
        assertNull(replacement.lastUsed)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation, true) { fail() } }
        assertThrows(SecurityException::class.java) { access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS) }
    }

    @Test fun vaultsAndOtherEntriesRemainIndependent() {
        val access = AgentSignInAccess(context)
        val other = "$vault-other"
        val a = access.get(vault, "a")
        val b = access.get(vault, "b")
        val c = access.get(other, "a")
        access.setCurrent(vault, "a", a.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.setCurrent(vault, "b", b.incarnation, AgentSignInAccess.Level.NEVER)
        access.setCurrent(other, "a", c.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.retain(vault, setOf("b"))
        // The dropped login starts again at the default, which is Always too: its new incarnation shows it was dropped.
        assertNotEquals(a.incarnation, access.get(vault, "a").incarnation)
        assertEquals(AgentSignInAccess.Level.NEVER, access.get(vault, "b").level)
        assertEquals(b.incarnation, access.get(vault, "b").incarnation)
        assertEquals(AgentSignInAccess.Level.ALWAYS, access.get(other, "a").level)
        assertEquals(c.incarnation, access.get(other, "a").incarnation)
    }
    private fun snapshot(parent: String, resource: String, payload: String = "encrypted-original") = VaultState(
        metadata = Proto.field(1, parent),
        records = arrayListOf(Proto.field(1, resource) + Proto.field(2, "login") + Proto.field(6, payload))
    )

    @Test fun singleSaveVaultReplacementRevokesEvenWhenLoginTagAndResourceNameAreReused() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS)
        val before = snapshot("users/me/birthdays/old", "passwords/same")
        val replacement = snapshot("users/me/birthdays/new", "passwords/same", "encrypted-replacement")
        access.onVaultSave(vault, before, replacement)
        val current = access.get(vault, "login")
        assertEquals(AgentSignInAccess.Level.ALWAYS, current.level)
        assertNotEquals(original.incarnation, current.incarnation)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation, true) { fail() } }
    }

    @Test fun singleSaveRemoteDeleteAndRecreateRotatesSameTagAndPreservesUnchangedEntries() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        val other = access.get(vault, "other")
        access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.setCurrent(vault, "other", other.incarnation, AgentSignInAccess.Level.NEVER)
        val before = snapshot("parent", "parent/passwords/old")
        val replacement = snapshot("parent", "parent/passwords/new", "encrypted-replacement")
        val unchanged = Proto.field(1, "parent/passwords/other") + Proto.field(2, "other")
        before.records.add(unchanged); replacement.records.add(unchanged)
        access.onVaultSave(vault, before, replacement)
        assertEquals(AgentSignInAccess.Level.ALWAYS, access.get(vault, "login").level)
        assertNotEquals(original.incarnation, access.get(vault, "login").incarnation)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation, true) { fail() } }
        assertEquals(AgentSignInAccess.Level.NEVER, access.get(vault, "other").level)
        assertEquals(other.incarnation, access.get(vault, "other").incarnation)
    }

    @Test fun ordinaryPasswordEditPreservesExistingResourcePermission() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.onVaultSave(vault, snapshot("parent", "parent/passwords/same"),
            snapshot("parent", "parent/passwords/same", "encrypted-password-edit"))
        assertEquals(AgentSignInAccess.Level.ALWAYS, access.get(vault, "login").level)
        assertEquals(original.incarnation, access.get(vault, "login").incarnation)
        access.redeem(vault, "login", original.incarnation, false) { }
    }

    @Test fun enablingDeviceAuthenticationRevokesAlwaysAndRequiresNativeAuthentication() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.setOwnerPolicy(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS, true)
        val strict = AgentSignInAccess(context).get(vault, "login")
        assertTrue(strict.requireDeviceAuthentication)
        assertEquals(AgentSignInAccess.Level.ASK, strict.level)
        assertNotEquals(original.incarnation, strict.incarnation)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", original.incarnation, false) { fail() } }
        assertThrows(SecurityException::class.java) { access.setCurrent(vault, "login", strict.incarnation, AgentSignInAccess.Level.ALWAYS) }
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", strict.incarnation, true) { fail() } }
        access.redeem(vault, "login", strict.incarnation, true, deviceAuthenticated = true) { }
        assertEquals(AgentSignInAccess.Level.ASK, access.get(vault, "login").level)
    }

    @Test fun disablingDeviceAuthenticationInvalidatesPendingNativeAuthAndDoesNotRestoreAlways() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setCurrent(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS)
        access.setOwnerPolicy(vault, "login", original.incarnation, AgentSignInAccess.Level.ALWAYS, true)
        val strict = access.get(vault, "login")
        access.setOwnerPolicy(vault, "login", strict.incarnation, strict.level, false)
        val normal = access.get(vault, "login")
        assertFalse(normal.requireDeviceAuthentication)
        assertEquals(AgentSignInAccess.Level.ASK, normal.level)
        assertNotEquals(strict.incarnation, normal.incarnation)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", strict.incarnation, true, deviceAuthenticated = true) { fail() } }
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", normal.incarnation, false) { fail() } }
    }

    @Test fun neverRemainsBlockedAfterNativeAuthenticationAndPolicyEdits() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setOwnerPolicy(vault, "login", original.incarnation, AgentSignInAccess.Level.NEVER, true)
        val strict = access.get(vault, "login")
        assertTrue(strict.requireDeviceAuthentication)
        assertEquals(AgentSignInAccess.Level.NEVER, strict.level)
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", strict.incarnation, true, deviceAuthenticated = true) { fail() } }
    }

    @Test fun replacingStrictLoginInvalidatesAuthenticatedUseAndResetsPolicyForNewLogin() {
        val access = AgentSignInAccess(context)
        val original = access.get(vault, "login")
        access.setOwnerPolicy(vault, "login", original.incarnation, AgentSignInAccess.Level.ASK, true)
        val strict = access.get(vault, "login")
        access.onVaultSave(vault, snapshot("parent", "parent/passwords/old"), snapshot("parent", "parent/passwords/new"))
        assertThrows(SecurityException::class.java) { access.redeem(vault, "login", strict.incarnation, true, deviceAuthenticated = true) { fail() } }
        val replacement = access.get(vault, "login")
        assertFalse(replacement.requireDeviceAuthentication)
        assertEquals(AgentSignInAccess.Level.ALWAYS, replacement.level)
        assertNotEquals(strict.incarnation, replacement.incarnation)
    }

}
