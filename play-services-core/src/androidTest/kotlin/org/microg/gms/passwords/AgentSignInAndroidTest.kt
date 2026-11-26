/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.credentials.provider.CallingAppInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.CoordinatesProvider
import androidx.test.espresso.action.GeneralClickAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Tap
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.R
import com.google.android.gms.credential.manager.EXTRA_KEY_ACCOUNT_NAME
import com.google.android.gms.credential.manager.PasswordManagerActivity
import com.google.android.gms.credential.manager.SignIns
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit local-only acceptance. Native cases use isolated real Android Keystore/SQLite storage.
 * UI case verifies the Passwords detail page opens cleanly with no assistant-access controls (CYC-3345).
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AgentSignInAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var step = "precondition"

    @Test fun nativeGrantPersistsAndRecordsLastUsed() = localOnly {
        isolatedVault { identity, vault ->
            val chrome = synthetic("first")
            try {
                vault.insertIfAbsent(chrome)
                val tag = PasswordData.decode(Proto.decode(chrome).one(1)).tag()
                val access = AgentSignInAccess(context) { 1234L }
                val initial = access.get(identity, tag)
                check(initial.lastUsed == null && initial.incarnation.isNotEmpty())
                var deliveries = 0
                access.redeem(identity, tag, initial.incarnation) { deliveries++ }
                check(deliveries == 1 && access.get(identity, tag).lastUsed == 1234L)
                val reopened = AgentSignInAccess(context)
                check(reopened.get(identity, tag).incarnation == initial.incarnation)
                reopened.redeem(identity, tag, initial.incarnation) { deliveries++ }
                check(deliveries == 2)
                val entries = DevicePasswordVault(context, identity).list()
                try { check(entries.size == 1 && entries.single().id == tag) }
                finally { entries.forEach { it.chromeBytes.fill(0) } }
            } finally { chrome.fill(0) }
        }
    }

    @Test fun nativeVaultDeleteRecreateAndClearRevokeLoginIncarnations() = localOnly {
        isolatedVault { identity, vault ->
            val first = synthetic("first")
            val other = synthetic("other")
            var edited: ByteArray? = null
            try {
                vault.insertIfAbsent(first); vault.insertIfAbsent(other)
                val originalData = PasswordData.decode(Proto.decode(first).one(1))
                val tag = originalData.tag()
                val otherTag = PasswordData.decode(Proto.decode(other).one(1)).tag()
                val access = AgentSignInAccess(context)
                val original = access.get(identity, tag)
                val untouched = access.get(identity, otherTag)
                val changed = Proto.field(1, originalData.withPassword("cyc779-public-edited-fixture", 1).encode())
                edited = changed
                vault.editIfUnchanged(first, changed)
                check(access.get(identity, tag).incarnation == original.incarnation)
                vault.deleteIfUnchanged(changed)
                vault.insertIfAbsent(first)
                val replacement = AgentSignInAccess(context).get(identity, tag)
                check(replacement.incarnation != original.incarnation && replacement.lastUsed == null)
                denied { access.redeem(identity, tag, original.incarnation) { error("Stale delivery") } }
                check(access.get(identity, otherTag).incarnation == untouched.incarnation)
                vault.store.clear()
                val afterClear = AgentSignInAccess(context).get(identity, otherTag)
                check(afterClear.incarnation != untouched.incarnation && afterClear.lastUsed == null)
                denied { access.redeem(identity, otherTag, untouched.incarnation) { error("Cleared delivery") } }
            } finally { first.fill(0); other.fill(0); edited?.fill(0) }
        }
    }

    @Test fun localProbeDetailOpensWithoutAssistantAccessControls() = localOnly {
        step = "owner-authentication-precondition"
        check(Application.getProcessName() == "com.google.android.gms:ui")
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isDeviceSecure) {
            step = "owner-credential-authentication-required-and-unavailable"
            error("Owner credential authentication is required; this test cannot supply it")
        }
        step = "device-must-already-be-unlocked"
        check(!keyguard.isKeyguardLocked && !keyguard.isDeviceLocked)
        step = "verify-exact-probe"
        val realm = probeRealm()
        val vault = DevicePasswordVault(context)
        val before = vault.list()
        var after = emptyList<PasswordView>()
        var beforeDigest: ByteArray? = null
        var afterDigest: ByteArray? = null
        try {
            check(AndroidVaultStore(context, "owner-password-transfers-v1").locked { it.load().ownerTransfers.isEmpty() })
            exactProbe(before, realm)
            beforeDigest = recordDigest(before)
            val intent = Intent(context, PasswordManagerActivity::class.java)
                .putExtra(SignIns.EXTRA_PAGE, SignIns.PAGE)
                .putExtra(EXTRA_KEY_ACCOUNT_NAME, "cyc779-no-account-${UUID.randomUUID()}.invalid")
            step = "launch-sign-ins-page"
            ActivityScenario.launch<PasswordManagerActivity>(intent).use { scenario ->
                step = "wait-owner-manager-focus"
                waitForFocus(scenario)
                step = "verify-sign-ins-page"
                compose.onNodeWithText(context.getString(R.string.cyclon_sign_ins_title)).assertExists()
                step = "tap-owner-open"
                tap(compose.onNodeWithText(context.getString(R.string.passwords_open)))
                step = "wait-local-storage-choice"
                waitForOneNode(hasText(context.getString(R.string.passwords_on_device)))
                step = "tap-local-storage-choice"
                tap(compose.onNodeWithText(context.getString(R.string.passwords_on_device)))
                step = "visit-all-passwords-settings-and-return"
                waitForOneNode(hasText(context.getString(R.string.cyclon_sign_ins_all_passwords)))
                tap(compose.onNodeWithText(context.getString(R.string.cyclon_sign_ins_all_passwords)).performScrollTo())
                waitForOneNode(hasText(context.getString(R.string.passwords_tab_settings)))
                tap(compose.onNodeWithText(context.getString(R.string.passwords_tab_settings)))
                tap(compose.onNodeWithContentDescription(context.getString(R.string.cyclon_back)))
                waitForOneNode(hasText(context.getString(R.string.cyclon_sign_ins_title)))
                step = "wait-sign-ins-probe-row"
                waitForOneNode(hasText(USERNAME, substring = true))
                step = "verify-no-access-labels-on-sign-ins"
                compose.onAllNodes(hasText("Ask each time", substring = true, ignoreCase = true)).assertCountEquals(0)
                compose.onAllNodes(hasText("Always allow", substring = true, ignoreCase = true)).assertCountEquals(0)
                compose.onAllNodes(hasText("Require device authentication", substring = true, ignoreCase = true)).assertCountEquals(0)
                step = "open-verified-probe-detail-from-sign-ins"
                tap(compose.onNode(hasText(USERNAME, substring = true)).performScrollTo())
                step = "verify-probe-detail-without-assistant-controls"
                waitForOneNode(hasContentDescription(context.getString(R.string.passwords_back_to_passwords)))
                compose.onNodeWithText(USERNAME).assertExists()
                compose.onAllNodes(hasText("Sign in with Cyclon", substring = true, ignoreCase = true)).assertCountEquals(0)
                compose.onAllNodes(hasText("Ask each time", substring = true, ignoreCase = true)).assertCountEquals(0)
                compose.onAllNodes(hasText("Always allow", substring = true, ignoreCase = true)).assertCountEquals(0)
                compose.onAllNodes(hasText("Require device authentication", substring = true, ignoreCase = true)).assertCountEquals(0)
                compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
                step = "back-returns-to-sign-ins"
                tap(compose.onNodeWithContentDescription(context.getString(R.string.passwords_back_to_passwords)))
                waitForOneNode(hasText(context.getString(R.string.cyclon_sign_ins_title)))
                check(probeRealm() == realm)
            }
        } finally {
            val operationStep = step
            var verified = false
            step = "verify-unrelated-state"
            try {
                after = vault.list()
                exactProbe(after, realm)
                beforeDigest?.let {
                    afterDigest = recordDigest(after)
                    check(MessageDigest.isEqual(it, afterDigest))
                }
                verified = true
            } finally {
                if (verified) step = operationStep
                beforeDigest?.fill(0); afterDigest?.fill(0)
                before.forEach { it.chromeBytes.fill(0) }; after.forEach { it.chromeBytes.fill(0) }
            }
        }
    }

    private fun localOnly(action: () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("passwordAcceptance") == OPT_IN)
        step = "precondition"
        try {
            check(com.google.android.gms.BuildConfig.DEBUG && context.packageName == "com.google.android.gms")
            action()
        } catch (failure: Throwable) {
            throw AssertionError("Local-only sign-in acceptance failed at $step (${failure.javaClass.simpleName}); credential contents withheld")
        }
    }

    private fun isolatedVault(action: (String, DevicePasswordVault) -> Unit) {
        val identity = "google:cyc779-isolated-${UUID.randomUUID()}"
        val vault = DevicePasswordVault(context, identity)
        try { step = "native-storage"; action(identity, vault) }
        finally {
            val operationStep = step
            step = "isolated-storage-cleanup"
            try { vault.store.clear() }
            finally { AgentSignInAccess(context).retain(identity, emptySet()) }
            check(vault.list().isEmpty())
            step = operationStep
        }
    }

    private fun synthetic(suffix: String): ByteArray = Proto.field(1, PasswordData.create(
        "https://cyc779.example.invalid/", "https://cyc779.example.invalid/", "cyc779-public-$suffix",
        "cyc779-public-fixture", 0).encode())

    private fun denied(action: () -> Unit) {
        try { action() } catch (_: SecurityException) { return }
        error("Revoked operation was accepted")
    }

    private fun waitForOneNode(matcher: SemanticsMatcher) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1
        }
    }

    private fun waitForFocus(scenario: ActivityScenario<PasswordManagerActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var since = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            if (!focused) since = 0L
            else if (since == 0L) since = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - since >= 750) return
            Thread.sleep(20)
        }
        error("Owner manager did not receive input focus")
    }

    private fun tap(node: SemanticsNodeInteraction) {
        val bounds = node.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInWindow
        onView(withId(android.R.id.content)).perform(GeneralClickAction(Tap.SINGLE,
            CoordinatesProvider { view ->
                val location = IntArray(2); view.rootView.getLocationOnScreen(location)
                floatArrayOf(bounds.center.x + location[0], bounds.center.y + location[1])
            }, Press.FINGER, InputDevice.SOURCE_TOUCHSCREEN, 0))
    }

    private fun probeRealm(): String {
        check(Build.VERSION.SDK_INT >= 34)
        val manager = context.packageManager
        val installed = manager.getPackageInfo(PROBE, PackageManager.GET_SIGNING_CERTIFICATES)
        val uid = installed.applicationInfo?.uid ?: throw SecurityException()
        check(uid >= 10_000 && manager.getPackagesForUid(uid)?.toSet() == setOf(PROBE))
        val signing = installed.signingInfo ?: throw SecurityException()
        val signers = signing.apkContentsSigners
        check(signers.size == 1)
        val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
        try { check(digest.joinToString("") { "%02x".format(it.toInt() and 255) } == DEBUG_CERT_SHA256) }
        finally { digest.fill(0) }
        return PasswordCaller.from(CallingAppInfo(PROBE, signing)).realms.single()
    }

    private fun exactProbe(entries: List<PasswordView>, realm: String): PasswordView {
        check(entries.map { it.id }.toSet().size == entries.size)
        val target = entries.single { it.data.realm == realm && it.data.username == USERNAME }
        check(target.id == PasswordData.create(realm, realm, USERNAME, SEED, 0).tag())
        check(target.data.origin == realm && !target.data.blocked && target.data.password == SEED)
        check(entries.count { !it.data.blocked && it.data.matchesSearch(USERNAME) } == 1)
        return target
    }

    private fun recordDigest(entries: List<PasswordView>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { it.id }.forEach { entry ->
            val id = entry.id.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(id.size).array()); digest.update(id)
            digest.update(ByteBuffer.allocate(4).putInt(entry.chromeBytes.size).array()); digest.update(entry.chromeBytes)
        }
        return digest.digest()
    }

    private companion object {
        const val OPT_IN = "cyc779-local-only"
        const val PROBE = "ai.cyclon.dev.passwordprobe"
        const val USERNAME = "cyc680-native-fill-synthetic"
        const val SEED = "cyc680-synthetic-native-fill-only"
        const val DEBUG_CERT_SHA256 = "435044407117265d1dcc7769fcfd6092437791a31d561178574bb2398f41b516"
    }
}
