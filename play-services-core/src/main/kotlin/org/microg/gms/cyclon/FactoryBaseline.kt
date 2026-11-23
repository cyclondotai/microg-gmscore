/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.JsonWriter
import android.util.Log
import org.json.JSONObject
import org.microg.gms.checkin.CheckinManager
import org.microg.gms.checkin.CheckinPreferences
import org.microg.gms.checkin.CheckinService
import org.microg.gms.checkin.LastCheckinInfo
import org.microg.gms.gcm.GcmPrefs
import org.microg.gms.settings.SettingsContract
import java.io.StringWriter
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What survives a factory reset for microG (CYC-2935): the phone's check-in identity, its accepted registration receipt
 * and Cyclon's own device account. Core stores it in `/metadata/cyclon/microg.json` and pushes it back once after a reset.
 *
 * Only an account [DeviceAccount.isDevice] accepts is ever read here. An owner's Google account, its token and its data
 * are never exported. Nothing in this file logs a token, an ID or the payload.
 */
object FactoryBaseline {
    private const val TAG = "CyclonFactoryBaseline"
    private const val GOOGLE = "com.google"
    private const val PREFERENCES = "cyclon_factory_baseline"
    private const val SEALED_DIGEST = "sealedDigest"
    private const val MICROG_PENDING = "cyclon.factory_baseline.microg_pending"
    private const val HOLD_WINDOW_MS = 600_000L
    private const val MAX_BYTES = 32_768
    private const val MAX_NAME = 200
    private const val MAX_TOKEN = 4_096
    private const val GOOGLE_USER_ID = "GoogleUserId"
    private const val SERVICES = "services"

    internal const val CORE_PACKAGE = "ai.cyclon.core"
    internal const val CORE_AUTHORITY = "ai.cyclon.core.factory_baseline"
    internal const val PROVIDER_MISMATCH = "factory_baseline.provider_mismatch"

    internal const val APPLIED = "applied"
    internal const val TARGET_PROVISIONED = "target_provisioned"
    internal const val INVALID_BASELINE = "invalid_baseline"
    internal const val BUSY = "busy"

    private val TOP_KEYS = setOf("schema", "checkin", "enrollment", "deviceAccount")
    private val CHECKIN_KEYS =
        setOf("androidId", "securityToken", "digest", "lastCheckin", "versionInfo", "deviceDataVersionInfo", "enabled")
    private val ENROLLMENT_KEYS = setOf("key", "status")
    private val DEVICE_KEYS = setOf("name", "masterToken", "userData")
    /** Everything microG reads back from this account on the check-in and auth path; the rest regenerates. */
    private val USER_DATA_KEYS = listOf(DeviceAccount.MARKER, GOOGLE_USER_ID, SERVICES)
    private val DECIMAL = Regex("-?[1-9][0-9]{0,18}")
    private val ENROLLMENT_KEY = Regex("[0-9a-f]{64}")

    /** Replaced by host tests so a seal runs on the test's thread. */
    @Volatile internal var executor: Executor = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean()

    /** Seals the current identity in the background when it differs from what Core last accepted. */
    fun sealIfNeeded(context: Context) {
        val app = context.applicationContext ?: context
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            try {
                seal(AndroidFactoryBaselineHost(app))
            } catch (e: Exception) {
                Log.w(TAG, "Seal skipped: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Whether a first check-in must wait for the restore: without this, a check-in on cellular would create a new
     * Android ID before Core delivers the sealed one.
     */
    @JvmStatic
    fun holdsCheckin(context: Context): Boolean =
        holds(pendingProperty() == "1", SystemClock.elapsedRealtime()) { LastCheckinInfo.read(context).androidId }

    internal fun holds(pending: Boolean, elapsedMs: Long, androidId: () -> Long): Boolean =
        pending && elapsedMs < HOLD_WINDOW_MS && androidId() <= 0

    private fun pendingProperty(): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, MICROG_PENDING, "") as String
    }.getOrDefault("")

    /** `empty`, `partial` (a restore that crashed after adding the account) or `provisioned`. */
    internal fun state(host: FactoryBaselineHost): String = when {
        host.checkin().androidId > 0 -> "provisioned"
        host.googleAccounts().any(host::isDevice) -> "partial"
        else -> "empty"
    }

    internal fun seal(host: FactoryBaselineHost) {
        val payload = buildPayload(host) ?: return
        try {
            val digest = sha256(payload)
            if (host.sealedDigest() == digest) return
            when (host.corePin()) {
                CorePin.ABSENT -> return
                CorePin.MISMATCH -> {
                    Log.w(TAG, PROVIDER_MISMATCH)
                    return
                }
                CorePin.OK -> Unit
            }
            val result = try {
                host.sealToCore(payload)
            } catch (e: Exception) {
                Log.w(TAG, "Core did not take the seal: ${e.javaClass.simpleName}")
                return
            }
            if (result == "ok") host.storeSealedDigest(digest)
        } finally {
            payload.fill(0)
        }
    }

    /** The payload for the single device account, or null when this phone has nothing complete to seal. */
    internal fun buildPayload(host: FactoryBaselineHost): ByteArray? {
        val account = host.googleAccounts().filter(host::isDevice).singleOrNull() ?: return null
        val info = host.checkin()
        if (info.androidId <= 0 || info.securityToken == 0L) return null
        if (!host.enrollmentAccepted(account.name, info.androidId)) return null
        val masterToken = host.password(account)?.takeIf { it.isNotEmpty() } ?: return null
        val userData = linkedMapOf(DeviceAccount.MARKER to "1")
        for (key in listOf(GOOGLE_USER_ID, SERVICES)) {
            host.userData(account, key)?.takeIf { it.isNotEmpty() }?.let { userData[key] = it }
        }
        val baseline = Baseline(
            androidId = info.androidId,
            securityToken = info.securityToken,
            digest = info.digest,
            lastCheckin = info.lastCheckin,
            versionInfo = info.versionInfo,
            deviceDataVersionInfo = info.deviceDataVersionInfo,
            enabled = host.checkinEnabled(),
            enrollmentKey = EnrollmentSession.keyFor(account.name, info.androidId),
            name = account.name,
            masterToken = masterToken,
            userData = userData,
        )
        // Never seal what a restore would refuse.
        return encode(baseline).takeIf { parse(it) != null }
    }

    /** Applies a sealed baseline to a wiped microG. The check-in identity is written last. */
    internal fun restore(host: FactoryBaselineHost, payload: ByteArray): String {
        val baseline = parse(payload) ?: return INVALID_BASELINE
        // CheckinManager.checkin holds this lock, so a check-in in flight cannot write a second identity over this one.
        val outcome = synchronized(CheckinManager::class.java) { applyIdentity(host, baseline) }
        if (outcome == APPLIED) {
            host.enableGcm()
            host.enableCheckin()
        }
        return outcome
    }

    private fun applyIdentity(host: FactoryBaselineHost, baseline: Baseline): String {
        if (host.checkin().androidId > 0) return TARGET_PROVISIONED
        val accounts = host.googleAccounts()
        val devices = accounts.filter(host::isDevice)
        if (devices.any { it.name != baseline.name }) return TARGET_PROVISIONED
        if (devices.isEmpty()) {
            // An unmarked account of this name belongs to the owner and is never adopted.
            if (accounts.any { it.name == baseline.name }) return TARGET_PROVISIONED
            val added = try {
                host.addAccount(Account(baseline.name, GOOGLE), baseline.masterToken, baseline.userData)
            } catch (_: SecurityException) {
                false
            }
            if (!added) return BUSY
        }
        host.hideFromApps()
        if (!host.restoreEnrollment(baseline.enrollmentKey)) return BUSY
        // An aged time makes TriggerReceiver check in now; the initial digest makes that check-in refill GServices.
        val aged = maxOf(0L, minOf(baseline.lastCheckin, host.now() - CheckinService.REGULAR_CHECKIN_INTERVAL - 60_000L))
        host.writeCheckin(
            LastCheckinInfo(
                aged,
                baseline.androidId,
                baseline.securityToken,
                SettingsContract.CheckIn.INITIAL_DIGEST,
                baseline.versionInfo,
                baseline.deviceDataVersionInfo,
            )
        )
        return APPLIED
    }

    internal fun pinCore(provider: ProviderInfo?): CorePin = when {
        provider == null -> CorePin.ABSENT
        provider.packageName == CORE_PACKAGE &&
            (provider.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 -> CorePin.OK
        else -> CorePin.MISMATCH
    }

    internal fun encode(baseline: Baseline): ByteArray {
        val out = StringWriter()
        JsonWriter(out).use { json ->
            json.beginObject()
            json.name("schema").value(1)
            json.name("checkin").beginObject()
            json.name("androidId").value(baseline.androidId.toString())
            json.name("securityToken").value(baseline.securityToken.toString())
            json.name("digest").value(baseline.digest)
            json.name("lastCheckin").value(baseline.lastCheckin)
            json.name("versionInfo").value(baseline.versionInfo)
            json.name("deviceDataVersionInfo").value(baseline.deviceDataVersionInfo)
            json.name("enabled").value(baseline.enabled)
            json.endObject()
            json.name("enrollment").beginObject()
            json.name("key").value(baseline.enrollmentKey)
            json.name("status").value("accepted")
            json.endObject()
            json.name("deviceAccount").beginObject()
            json.name("name").value(baseline.name)
            json.name("masterToken").value(baseline.masterToken)
            json.name("userData").beginObject()
            for (key in USER_DATA_KEYS) baseline.userData[key]?.let { json.name(key).value(it) }
            json.endObject()
            json.endObject()
            json.endObject()
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** The validated baseline, or null for anything outside the sealed schema. */
    internal fun parse(bytes: ByteArray): Baseline? {
        if (bytes.size > MAX_BYTES) return null
        return try {
            decode(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(bytes: ByteArray): Baseline? {
        val root = JSONObject(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        if (root.fieldNames() != TOP_KEYS || root.opt("schema") != 1) return null
        val checkin = root.opt("checkin") as? JSONObject ?: return null
        val enrollment = root.opt("enrollment") as? JSONObject ?: return null
        val device = root.opt("deviceAccount") as? JSONObject ?: return null
        if (checkin.fieldNames() != CHECKIN_KEYS || enrollment.fieldNames() != ENROLLMENT_KEYS ||
            device.fieldNames() != DEVICE_KEYS) return null

        val androidId = decimal(checkin.opt("androidId"))?.takeIf { it > 0 } ?: return null
        val securityToken = decimal(checkin.opt("securityToken"))?.takeIf { it != 0L } ?: return null
        val digest = checkin.opt("digest") as? String ?: return null
        val lastCheckin = (checkin.opt("lastCheckin") as? Number)?.takeIf { it is Int || it is Long }?.toLong()
            ?: return null
        val versionInfo = checkin.opt("versionInfo") as? String ?: return null
        val deviceDataVersionInfo = checkin.opt("deviceDataVersionInfo") as? String ?: return null
        val enabled = checkin.opt("enabled") as? Boolean ?: return null

        val name = (device.opt("name") as? String)
            ?.takeIf { it.length in 1..MAX_NAME && it.all { c -> c in '!'..'~' } } ?: return null
        val masterToken = (device.opt("masterToken") as? String)
            ?.takeIf { it.length in 1..MAX_TOKEN && it.all { c -> c in ' '..'~' } } ?: return null
        val sealedUserData = device.opt("userData") as? JSONObject ?: return null
        if (!USER_DATA_KEYS.containsAll(sealedUserData.fieldNames())) return null
        val userData = LinkedHashMap<String, String>()
        for (key in USER_DATA_KEYS) {
            if (sealedUserData.has(key)) userData[key] = sealedUserData.opt(key) as? String ?: return null
        }
        if (userData[DeviceAccount.MARKER] != "1") return null

        val key = (enrollment.opt("key") as? String)?.takeIf(ENROLLMENT_KEY::matches) ?: return null
        if (enrollment.opt("status") != "accepted" || key != EnrollmentSession.keyFor(name, androidId)) return null
        return Baseline(
            androidId, securityToken, digest, lastCheckin, versionInfo, deviceDataVersionInfo, enabled, key, name,
            masterToken, userData,
        )
    }

    private fun decimal(value: Any?): Long? = (value as? String)?.takeIf(DECIMAL::matches)?.toLongOrNull()

    private fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Private preferences that hold only the digest of the last payload Core accepted. */
    internal fun preferences(context: Context) =
        @Suppress("DEPRECATION") context.getSharedPreferences(PREFERENCES, Context.MODE_MULTI_PROCESS)

    internal fun sealedDigestOf(context: Context): String? = preferences(context).getString(SEALED_DIGEST, null)

    internal fun storeSealedDigest(context: Context, digest: String) {
        preferences(context).edit().putString(SEALED_DIGEST, digest).commit()
    }
}

internal enum class CorePin { OK, ABSENT, MISMATCH }

/** The sealed identity. A plain class on purpose: it has no generated toString that could print the token. */
internal class Baseline(
    val androidId: Long,
    val securityToken: Long,
    val digest: String,
    val lastCheckin: Long,
    val versionInfo: String,
    val deviceDataVersionInfo: String,
    val enabled: Boolean,
    val enrollmentKey: String,
    val name: String,
    val masterToken: String,
    val userData: Map<String, String>,
)

/** The Android calls seal and restore make, kept apart so their order and refusals can be tested on a host. */
internal interface FactoryBaselineHost {
    fun now(): Long
    fun checkin(): LastCheckinInfo
    fun checkinEnabled(): Boolean
    fun googleAccounts(): List<Account>
    fun isDevice(account: Account): Boolean
    fun password(account: Account): String?
    fun userData(account: Account, key: String): String?
    fun enrollmentAccepted(name: String, androidId: Long): Boolean
    fun sealedDigest(): String?
    fun storeSealedDigest(digest: String)
    fun corePin(): CorePin
    fun sealToCore(payload: ByteArray): String?
    fun addAccount(account: Account, masterToken: String, userData: Map<String, String>): Boolean
    fun hideFromApps()
    fun restoreEnrollment(key: String): Boolean
    fun writeCheckin(info: LastCheckinInfo)
    fun enableGcm()
    fun enableCheckin()
}

internal class AndroidFactoryBaselineHost(private val context: Context) : FactoryBaselineHost {
    private val accounts get() = AccountManager.get(context)

    override fun now() = System.currentTimeMillis()
    override fun checkin() = LastCheckinInfo.read(context)
    override fun checkinEnabled() = CheckinPreferences.isEnabled(context)
    override fun googleAccounts() = accounts.getAccountsByType("com.google").toList()
    override fun isDevice(account: Account) = DeviceAccount.isDevice(context, account)
    override fun password(account: Account): String? = accounts.getPassword(account)
    override fun userData(account: Account, key: String): String? = accounts.getUserData(account, key)
    override fun enrollmentAccepted(name: String, androidId: Long) = EnrollmentSession(context, name, androidId).accepted
    override fun sealedDigest() = FactoryBaseline.sealedDigestOf(context)
    override fun storeSealedDigest(digest: String) = FactoryBaseline.storeSealedDigest(context, digest)
    override fun corePin() =
        FactoryBaseline.pinCore(context.packageManager.resolveContentProvider(FactoryBaseline.CORE_AUTHORITY, 0))

    override fun sealToCore(payload: ByteArray): String? = context.contentResolver.call(
        Uri.parse("content://${FactoryBaseline.CORE_AUTHORITY}"), "seal_microg", null,
        Bundle().apply {
            putInt("schema", 1)
            putByteArray("microg", payload)
        },
    )?.getString("result")

    override fun addAccount(account: Account, masterToken: String, userData: Map<String, String>): Boolean {
        val extras = Bundle().apply { userData.forEach { (key, value) -> putString(key, value) } }
        return accounts.addAccountExplicitly(account, masterToken, extras)
    }

    override fun hideFromApps() = DeviceAccount.hideFromApps(context)
    override fun restoreEnrollment(key: String) = EnrollmentSession.restoreAccepted(context, key)
    override fun writeCheckin(info: LastCheckinInfo) {
        info.write(context)
    }

    override fun enableGcm() = GcmPrefs.setEnabled(context, true)
    override fun enableCheckin() = CheckinPreferences.setEnabled(context, true)
}
