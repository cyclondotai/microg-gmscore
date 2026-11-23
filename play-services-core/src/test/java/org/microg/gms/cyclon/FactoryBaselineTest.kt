/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.os.Bundle
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.microg.gms.checkin.CheckinService
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.settings.SettingsContract
import org.microg.gms.settings.SettingsProvider
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSystemProperties
import java.util.concurrent.Executor

private const val DEVICE = "cyclondevice@gmail.com"
private const val OWNER = "owner@example.invalid"
private const val DEVICE_TOKEN = "synthetic-device-token"
private const val OWNER_TOKEN = "synthetic-owner-token"
private const val ANDROID_ID = 1234567890123456789L
private const val SECURITY_TOKEN = -987654321012345678L // gitleaks:allow synthetic test value
private const val NOW = 1_800_000_000_000L

private fun account(name: String) = Account(name, "com.google")

private class FakeHost : FactoryBaselineHost {
    var info = LastCheckinInfo(NOW - 1000, ANDROID_ID, SECURITY_TOKEN, "1-synthetic", "version", "device-version")
    val accounts = mutableListOf(account(OWNER), account(DEVICE))
    val devices = mutableSetOf(DEVICE)
    val passwords = mutableMapOf(DEVICE to DEVICE_TOKEN, OWNER to OWNER_TOKEN)
    val data = mutableMapOf(
        DEVICE to mutableMapOf("GoogleUserId" to "1001", "services" to "mail,android", "flags" to "1", "firstName" to "Device"),
        OWNER to mutableMapOf("GoogleUserId" to "2002", "services" to "owner-service"),
    )
    var accepted = true
    var digest: String? = null
    var pin = CorePin.OK
    var coreReply: String? = "ok"
    var addResult = true
    var addFailure: Exception? = null
    var enrollmentResult = true
    val sealed = mutableListOf<ByteArray>()
    val calls = mutableListOf<String>()
    val readAccounts = mutableSetOf<String>()
    var added: Triple<Account, String, Map<String, String>>? = null
    var written: LastCheckinInfo? = null
    var restoredKey: String? = null

    override fun now() = NOW
    override fun checkin() = info
    override fun checkinEnabled() = true
    override fun googleAccounts() = accounts.toList()
    override fun isDevice(account: Account) = account.name in devices
    override fun password(account: Account): String? {
        readAccounts += account.name
        return passwords[account.name]
    }
    override fun userData(account: Account, key: String): String? {
        readAccounts += account.name
        return data[account.name]?.get(key)
    }
    override fun enrollmentAccepted(name: String, androidId: Long) = accepted
    override fun sealedDigest() = digest
    override fun storeSealedDigest(digest: String) { this.digest = digest }
    override fun corePin() = pin
    override fun sealToCore(payload: ByteArray): String? {
        sealed += payload.copyOf()
        return coreReply
    }
    override fun addAccount(account: Account, masterToken: String, userData: Map<String, String>): Boolean {
        calls += "addAccount"
        addFailure?.let { throw it }
        if (addResult) {
            accounts += account
            devices += account.name
            added = Triple(account, masterToken, userData)
        }
        return addResult
    }
    override fun hideFromApps() { calls += "hide" }
    override fun restoreEnrollment(key: String): Boolean {
        calls += "enrollment"
        restoredKey = key
        return enrollmentResult
    }
    override fun writeCheckin(info: LastCheckinInfo) {
        calls += "checkin"
        written = info
        this.info = info
    }
    override fun enableGcm() { calls += "gcm" }
    override fun enableCheckin() { calls += "enableCheckin" }
}

private fun wiped() = FakeHost().apply {
    info = LastCheckinInfo(0, 0, 0, SettingsContract.CheckIn.INITIAL_DIGEST, "", "")
    accounts.clear()
    devices.clear()
    passwords.clear()
    data.clear()
}

private fun payload(mutate: (JSONObject) -> Unit = {}): ByteArray {
    val host = FakeHost()
    val json = JSONObject(String(FactoryBaseline.buildPayload(host)!!))
    mutate(json)
    return json.toString().toByteArray()
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FactoryBaselineTest {
    @After fun cleanup() {
        ShadowSystemProperties.override("cyclon.factory_baseline.microg_pending", "")
    }

    @Test fun sealHoldsTheDeviceAccountAndNeverReadsAnOwnerAccount() {
        val host = FakeHost()
        FactoryBaseline.seal(host)
        val bytes = host.sealed.single()
        val text = String(bytes)
        assertFalse(text.contains(OWNER))
        assertFalse(text.contains(OWNER_TOKEN))
        assertFalse(text.contains("owner-service"))
        assertEquals(setOf(DEVICE), host.readAccounts)

        val root = JSONObject(text)
        assertEquals(setOf("schema", "checkin", "enrollment", "deviceAccount"), root.keys().asSequence().toSet())
        val device = root.getJSONObject("deviceAccount")
        assertEquals(DEVICE, device.getString("name"))
        assertEquals(DEVICE_TOKEN, device.getString("masterToken"))
        val userData = device.getJSONObject("userData")
        assertEquals(setOf("cyclon_device_account", "GoogleUserId", "services"), userData.keys().asSequence().toSet())
        assertEquals("1", userData.getString("cyclon_device_account"))
        assertEquals(ANDROID_ID.toString(), root.getJSONObject("checkin").getString("androidId"))
        assertEquals(EnrollmentSession.keyFor(DEVICE, ANDROID_ID), root.getJSONObject("enrollment").getString("key"))
        assertNotNull(FactoryBaseline.parse(bytes))
    }

    @Test fun sealSkipsAPhoneWithOnlyAnOwnerAccount() {
        val host = FakeHost().apply {
            accounts.remove(account(DEVICE))
            devices.clear()
        }
        FactoryBaseline.seal(host)
        assertTrue(host.sealed.isEmpty())
        assertTrue(host.readAccounts.isEmpty())
    }

    @Test fun sealSkipsWhenTwoAccountsClaimToBeTheDevice() {
        val host = FakeHost().apply {
            accounts += account("second@example.invalid")
            devices += "second@example.invalid"
        }
        FactoryBaseline.seal(host)
        assertTrue(host.sealed.isEmpty())
        assertTrue(host.readAccounts.isEmpty())
    }

    @Test fun sealNeedsCheckinTokenAndAcceptedReceipt() {
        for (incomplete in listOf(
            FakeHost().apply { info = info.copy(androidId = 0) },
            FakeHost().apply { info = info.copy(securityToken = 0) },
            FakeHost().apply { accepted = false },
            FakeHost().apply { passwords.remove(DEVICE) },
        )) {
            FactoryBaseline.seal(incomplete)
            assertTrue(incomplete.sealed.isEmpty())
        }
    }

    @Test fun sealStoresTheDigestOnlyAfterCoreAcceptsAndDoesNotRepeat() {
        val host = FakeHost().apply { coreReply = "error" }
        FactoryBaseline.seal(host)
        assertNull(host.digest)
        host.coreReply = "ok"
        FactoryBaseline.seal(host)
        assertNotNull(host.digest)
        assertEquals(2, host.sealed.size)
        FactoryBaseline.seal(host)
        assertEquals(2, host.sealed.size)
        host.info = host.info.copy(lastCheckin = NOW)
        FactoryBaseline.seal(host)
        assertEquals(3, host.sealed.size)
    }

    @Test fun sealNeverCallsACoreAuthorityHeldByAnotherPackage() {
        for (pin in listOf(CorePin.MISMATCH, CorePin.ABSENT)) {
            val host = FakeHost().apply { this.pin = pin }
            FactoryBaseline.seal(host)
            assertTrue(host.sealed.isEmpty())
            assertNull(host.digest)
        }
    }

    @Test fun corePinNeedsTheSystemCorePackage() {
        fun provider(name: String, flags: Int) = ProviderInfo().apply {
            packageName = name
            applicationInfo = ApplicationInfo().apply { this.flags = flags }
        }
        assertEquals(CorePin.ABSENT, FactoryBaseline.pinCore(null))
        assertEquals(CorePin.OK, FactoryBaseline.pinCore(provider("ai.cyclon.core", ApplicationInfo.FLAG_SYSTEM)))
        assertEquals(CorePin.MISMATCH, FactoryBaseline.pinCore(provider("ai.cyclon.core", 0)))
        assertEquals(CorePin.MISMATCH, FactoryBaseline.pinCore(provider("com.example.other", ApplicationInfo.FLAG_SYSTEM)))
    }

    @Test fun restoreRunsInOrderWithIdentityLast() {
        val host = wiped()
        assertEquals(FactoryBaseline.APPLIED, FactoryBaseline.restore(host, payload()))
        assertEquals(listOf("addAccount", "hide", "enrollment", "checkin", "gcm", "enableCheckin"), host.calls)
        val (account, token, userData) = host.added!!
        assertEquals(account(DEVICE), account)
        assertEquals(DEVICE_TOKEN, token)
        assertEquals(mapOf("cyclon_device_account" to "1", "GoogleUserId" to "1001", "services" to "mail,android"), userData)
        assertEquals(EnrollmentSession.keyFor(DEVICE, ANDROID_ID), host.restoredKey)
        val written = host.written!!
        assertEquals(ANDROID_ID, written.androidId)
        assertEquals(SECURITY_TOKEN, written.securityToken)
        assertEquals(SettingsContract.CheckIn.INITIAL_DIGEST, written.digest)
        assertEquals("version", written.versionInfo)
        assertEquals("device-version", written.deviceDataVersionInfo)
    }

    @Test fun restoreAgesARecentCheckinSoItRunsNow() {
        val limit = NOW - CheckinService.REGULAR_CHECKIN_INTERVAL - 60_000
        fun restoredTime(sealed: Long): Long {
            val host = wiped()
            FactoryBaseline.restore(host, payload { it.getJSONObject("checkin").put("lastCheckin", sealed) })
            return host.written!!.lastCheckin
        }
        assertEquals(limit, restoredTime(NOW - 1000))
        assertEquals(limit, restoredTime(NOW + 1000))
        assertEquals(limit - 5, restoredTime(limit - 5))
        assertEquals(0, restoredTime(-5))
    }

    @Test fun restoreResumesFromPartialWithoutAddingTheAccountAgain() {
        val host = wiped().apply {
            accounts += account(DEVICE)
            devices += DEVICE
        }
        assertEquals("partial", FactoryBaseline.state(host))
        assertEquals(FactoryBaseline.APPLIED, FactoryBaseline.restore(host, payload()))
        assertEquals(listOf("hide", "enrollment", "checkin", "gcm", "enableCheckin"), host.calls)
        assertEquals("provisioned", FactoryBaseline.state(host))
    }

    @Test fun restoreRefusesAPhoneThatAlreadyCheckedIn() {
        val host = wiped().apply { info = info.copy(androidId = 42) }
        assertEquals(FactoryBaseline.TARGET_PROVISIONED, FactoryBaseline.restore(host, payload()))
        assertTrue(host.calls.isEmpty())
    }

    @Test fun restoreRefusesADifferentDeviceAccount() {
        val host = wiped().apply {
            accounts += account("other-device@example.invalid")
            devices += "other-device@example.invalid"
        }
        assertEquals(FactoryBaseline.TARGET_PROVISIONED, FactoryBaseline.restore(host, payload()))
        assertTrue(host.calls.isEmpty())
    }

    @Test fun restoreNeverAdoptsAnOwnerAccountOfTheSameName() {
        val sealedName = "renamed-device@example.invalid"
        val bytes = payload {
            val device = it.getJSONObject("deviceAccount")
            device.put("name", sealedName)
            it.getJSONObject("enrollment").put("key", EnrollmentSession.keyFor(sealedName, ANDROID_ID))
        }
        val host = wiped().apply { accounts += account(sealedName) }
        assertEquals(FactoryBaseline.TARGET_PROVISIONED, FactoryBaseline.restore(host, bytes))
        assertTrue(host.calls.isEmpty())
    }

    @Test fun restoreKeepsAnOwnerAccountOfAnotherName() {
        val host = wiped().apply { accounts += account(OWNER) }
        assertEquals(FactoryBaseline.APPLIED, FactoryBaseline.restore(host, payload()))
        assertTrue(account(OWNER) in host.accounts)
    }

    @Test fun refusedAccountCreationIsBusyAndWritesNoIdentity() {
        for (host in listOf(wiped().apply { addResult = false }, wiped().apply { addFailure = SecurityException() })) {
            assertEquals(FactoryBaseline.BUSY, FactoryBaseline.restore(host, payload()))
            assertNull(host.written)
            assertEquals(listOf("addAccount"), host.calls)
        }
    }

    @Test fun failedReceiptWriteIsBusyAndWritesNoIdentity() {
        val host = wiped().apply { enrollmentResult = false }
        assertEquals(FactoryBaseline.BUSY, FactoryBaseline.restore(host, payload()))
        assertNull(host.written)
    }

    @Test fun stateReadsCheckinAndDeviceAccount() {
        assertEquals("empty", FactoryBaseline.state(wiped()))
        assertEquals("provisioned", FactoryBaseline.state(FakeHost()))
        val ownerOnly = wiped().apply { accounts += account(OWNER) }
        assertEquals("empty", FactoryBaseline.state(ownerOnly))
    }

    @Test fun parseRefusesAnythingOutsideTheSchema() {
        val invalid = listOf<(JSONObject) -> Unit>(
            { it.put("extra", 1) },
            { it.put("schema", 2) },
            { it.remove("enrollment") },
            { it.getJSONObject("checkin").put("androidId", "0") },
            { it.getJSONObject("checkin").put("androidId", "-5") },
            { it.getJSONObject("checkin").put("androidId", "0123") },
            { it.getJSONObject("checkin").put("androidId", ANDROID_ID) },
            { it.getJSONObject("checkin").put("securityToken", "0") },
            { it.getJSONObject("checkin").put("lastCheckin", "soon") },
            { it.getJSONObject("checkin").put("enabled", "yes") },
            { it.getJSONObject("checkin").put("extra", "x") },
            { it.getJSONObject("enrollment").put("status", "attempted") },
            { it.getJSONObject("enrollment").put("key", "a".repeat(64)) },
            { it.getJSONObject("deviceAccount").getJSONObject("userData").put("firstName", "Owner") },
            { it.getJSONObject("deviceAccount").getJSONObject("userData").remove("cyclon_device_account") },
            { it.getJSONObject("deviceAccount").getJSONObject("userData").put("cyclon_device_account", "0") },
            { it.getJSONObject("deviceAccount").put("masterToken", "") },
            { it.getJSONObject("deviceAccount").put("masterToken", "x".repeat(4097)) },
            { it.getJSONObject("deviceAccount").put("masterToken", "bad\ntoken") },
            { it.getJSONObject("deviceAccount").put("name", "n".repeat(201)) },
            { it.getJSONObject("deviceAccount").put("extra", "x") },
        )
        assertNotNull(FactoryBaseline.parse(payload()))
        for ((index, mutate) in invalid.withIndex()) {
            assertNull("case $index", FactoryBaseline.parse(payload(mutate)))
        }
        assertNull(FactoryBaseline.parse(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(FactoryBaseline.parse(ByteArray(32_769) { ' '.code.toByte() }))
    }

    @Test fun holdNeedsPendingPropertyNoIdentityAndTheFirstTenMinutes() {
        assertTrue(FactoryBaseline.holds(true, 599_999) { 0 })
        assertFalse(FactoryBaseline.holds(true, 600_000) { 0 })
        assertFalse(FactoryBaseline.holds(false, 1_000) { 0 })
        assertFalse(FactoryBaseline.holds(true, 1_000) { 7 })
    }
}

/** The real provider, AccountManager, settings and receipts together. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class FactoryBaselineProviderTest {
    private val coreUid = 12345
    private var permitted = true
    private lateinit var context: Context
    private lateinit var provider: FactoryBaselineProvider
    private val previousExecutor = FactoryBaseline.executor

    @Before fun setup() {
        val app: Context = RuntimeEnvironment.getApplication()
        context = object : ContextWrapper(app) {
            override fun getPackageName() = "com.google.android.gms"
            override fun getApplicationContext(): Context = this
            override fun enforceCallingPermission(permission: String, message: String?) {
                assertEquals("ai.cyclon.microg.permission.ENROLLMENT_CHALLENGE", permission)
                if (!permitted) throw SecurityException(message)
            }
        }
        val settings = Robolectric.buildContentProvider(SettingsProvider::class.java)
            .create(ProviderInfo().apply { authority = "com.google.android.gms.microg.settings" }).get()
        ShadowContentResolver.registerProviderInternal("com.google.android.gms.microg.settings", settings)
        installCore(system = true)
        ShadowBinder.setCallingUid(coreUid)
        FactoryBaseline.executor = Executor { it.run() }
        provider = FactoryBaselineProvider().also {
            it.attachInfo(context, ProviderInfo().apply {
                authority = FactoryBaselineProvider.AUTHORITY
                exported = true
            })
        }
    }

    @After fun cleanup() {
        ShadowBinder.reset()
        FactoryBaseline.executor = previousExecutor
        ShadowSystemProperties.override("cyclon.factory_baseline.microg_pending", "")
    }

    private fun installCore(system: Boolean, sharedWith: String? = null) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "ai.cyclon.core"
            applicationInfo = ApplicationInfo().apply {
                packageName = "ai.cyclon.core"
                uid = coreUid
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
            }
        })
        if (sharedWith != null) shadowOf(context.packageManager).setPackagesForUid(coreUid, "ai.cyclon.core", sharedWith)
        else shadowOf(context.packageManager).setPackagesForUid(coreUid, "ai.cyclon.core")
    }

    private fun restoreExtras(bytes: ByteArray = payload(), reason: String = "just_reset") = Bundle().apply {
        putInt("schema", 1)
        putString("reason", reason)
        putByteArray("microg", bytes)
    }

    @Test fun onlyTheSystemCoreMayCall() {
        permitted = false
        assertThrows(SecurityException::class.java) { provider.call("status", null, null) }
        permitted = true

        ShadowBinder.setCallingUid(coreUid + 1)
        assertThrows(SecurityException::class.java) { provider.call("status", null, null) }
        ShadowBinder.setCallingUid(coreUid)

        installCore(system = false)
        assertThrows(SecurityException::class.java) { provider.call("restore", null, restoreExtras()) }
        installCore(system = true, sharedWith = "com.example.other")
        assertThrows(SecurityException::class.java) { provider.call("restore", null, restoreExtras()) }

        installCore(system = true)
        assertThrows(SecurityException::class.java) { provider.call("export", null, null) }
        assertThrows(SecurityException::class.java) { provider.call("status", "arg", null) }
    }

    @Test fun statusAnswersTheContractAndNoSecret() {
        val status = provider.call("status", null, null)
        assertEquals(1, status.getInt("restoreContract"))
        assertEquals("empty", status.getString("state"))
        assertEquals(setOf("restoreContract", "state"), status.keySet())
    }

    @Test fun restoreBuildsTheMarkedHiddenAccountAndIdentity() {
        val manager = AccountManager.get(context)
        manager.addAccountExplicitly(account(OWNER), OWNER_TOKEN, null)

        assertEquals("applied", provider.call("restore", null, restoreExtras()).getString("outcome"))

        val device = account(DEVICE)
        assertEquals(DEVICE_TOKEN, manager.getPassword(device))
        assertEquals("1", manager.getUserData(device, DeviceAccount.MARKER))
        assertEquals("1001", manager.getUserData(device, "GoogleUserId"))
        assertEquals("mail,android", manager.getUserData(device, "services"))
        assertNull(manager.getUserData(device, "firstName"))
        assertEquals(OWNER_TOKEN, manager.getPassword(account(OWNER)))
        assertTrue(EnrollmentSession(context, DEVICE, ANDROID_ID).accepted)
        val info = LastCheckinInfo.read(context)
        assertEquals(ANDROID_ID, info.androidId)
        assertEquals(SECURITY_TOKEN, info.securityToken)
        assertEquals(SettingsContract.CheckIn.INITIAL_DIGEST, info.digest)
        assertEquals("provisioned", provider.call("status", null, null).getString("state"))
        assertEquals("target_provisioned", provider.call("restore", null, restoreExtras()).getString("outcome"))
    }

    @Test fun malformedRestoreIsInvalidAndChangesNothing() {
        val wrongSchema = restoreExtras().apply { putInt("schema", 2) }
        val wrongReason = restoreExtras(reason = "because")
        val badPayload = restoreExtras("{}".toByteArray())
        for (extras in listOf(wrongSchema, wrongReason, badPayload, Bundle())) {
            assertEquals("invalid_baseline", provider.call("restore", null, extras).getString("outcome"))
        }
        assertEquals(0L, LastCheckinInfo.read(context).androidId)
        assertTrue(AccountManager.get(context).getAccountsByType("com.google").isEmpty())
    }

    @Test fun acceptSealsToThePinnedCoreAndRestoredReceiptDoesNot() {
        val received = mutableListOf<ByteArray>()
        val core = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
                assertEquals("seal_microg", method)
                assertEquals(1, extras!!.getInt("schema"))
                received += extras.getByteArray("microg")!!.copyOf()
                return Bundle().apply { putString("result", "ok") }
            }
            override fun query(u: android.net.Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) = null
            override fun getType(u: android.net.Uri): String? = null
            override fun insert(u: android.net.Uri, v: android.content.ContentValues?): android.net.Uri? = null
            override fun delete(u: android.net.Uri, s: String?, a: Array<out String>?) = 0
            override fun update(u: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
        }
        core.attachInfo(context, ProviderInfo().apply { authority = FactoryBaseline.CORE_AUTHORITY })
        ShadowContentResolver.registerProviderInternal(FactoryBaseline.CORE_AUTHORITY, core)
        val coreApp = ApplicationInfo().apply {
            packageName = "ai.cyclon.core"
            uid = coreUid
            flags = ApplicationInfo.FLAG_SYSTEM
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "ai.cyclon.core"
            applicationInfo = coreApp
            providers = arrayOf(ProviderInfo().apply {
                packageName = "ai.cyclon.core"
                name = "ai.cyclon.core.setup.FactoryBaselineProvider"
                authority = FactoryBaseline.CORE_AUTHORITY
                applicationInfo = coreApp
            })
        })
        assertEquals(CorePin.OK, AndroidFactoryBaselineHost(context).corePin())

        AccountManager.get(context).addAccountExplicitly(
            account(DEVICE), DEVICE_TOKEN, Bundle().apply { putString(DeviceAccount.MARKER, "1") })
        LastCheckinInfo(NOW, ANDROID_ID, SECURITY_TOKEN, "1-synthetic", "v", "d").write(context)

        EnrollmentSession.restoreAccepted(context, EnrollmentSession.keyFor(DEVICE, ANDROID_ID))
        assertTrue(received.isEmpty())

        assertTrue(EnrollmentSession(context, DEVICE, ANDROID_ID).accept())
        assertEquals(1, received.size)
        assertEquals(DEVICE, JSONObject(String(received.single())).getJSONObject("deviceAccount").getString("name"))
    }

    @Test fun holdReadsThePropertyAndTheStoredIdentity() {
        assertFalse(FactoryBaseline.holdsCheckin(context))
        ShadowSystemProperties.override("cyclon.factory_baseline.microg_pending", "1")
        assertTrue(FactoryBaseline.holdsCheckin(context))
        LastCheckinInfo(NOW, ANDROID_ID, SECURITY_TOKEN, "1-synthetic", "v", "d").write(context)
        assertFalse(FactoryBaseline.holdsCheckin(context))
    }
}
