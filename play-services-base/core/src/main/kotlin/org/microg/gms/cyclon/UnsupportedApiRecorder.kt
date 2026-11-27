/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cyclon

import android.app.Application
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.Feature
import com.google.android.gms.common.internal.ConnectionInfo
import com.google.android.gms.common.internal.GetServiceRequest
import com.google.android.gms.common.internal.IGmsCallbacks
import org.json.JSONArray
import org.json.JSONObject
import org.microg.gms.common.GmsService
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure feature descriptor (platform-free for host tests).
 */
data class FeatureSpec(val name: String, val version: Long)

/**
 * One distinct unsupported service, error status, missing API feature, unresolved Chimera action or
 * ModuleInstall probe observed in microG (CYC-3474).
 *
 * Holds only package name, service/action identifier, numeric status/GMS version, feature names/versions,
 * counts and timestamps — never request extras, accounts, scopes, tokens or user data.
 */
data class UnsupportedApiEntry(
    val packageName: String,
    val kind: String,
    val service: String,
    val serviceId: Int? = null,
    val gmsVersion: Int? = null,
    val appVersionCode: Long? = null,
    val appVersionName: String? = null,
    val microgVersionCode: Long? = null,
    val microgVersionName: String? = null,
    val statusCode: Int? = null,
    val statusName: String? = null,
    val features: List<String> = emptyList(),
    val count: Long = 1L,
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
) {
    val key: String
        get() = listOf(
            packageName,
            appVersionCode?.toString() ?: "",
            microgVersionCode?.toString() ?: "",
            kind,
            service,
            statusCode?.toString() ?: "",
            features.joinToString(","),
        ).joinToString("|")

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("packageName", packageName)
        put("kind", kind)
        put("service", service)
        serviceId?.let { put("serviceId", it) }
        gmsVersion?.let { put("gmsVersion", it) }
        appVersionCode?.let { put("appVersionCode", it) }
        appVersionName?.let { put("appVersionName", it) }
        microgVersionCode?.let { put("microgVersionCode", it) }
        microgVersionName?.let { put("microgVersionName", it) }
        statusCode?.let { put("statusCode", it) }
        statusName?.let { put("statusName", it) }
        if (features.isNotEmpty()) put("features", JSONArray(features))
        put("count", count)
        put("firstSeenAt", firstSeenAt)
        put("lastSeenAt", lastSeenAt)
    }

    companion object {
        fun fromJson(obj: JSONObject?): UnsupportedApiEntry? {
            if (obj == null) return null
            val pkg = obj.optString("packageName").takeIf { it.isNotEmpty() } ?: return null
            val kind = obj.optString("kind").takeIf { it.isNotEmpty() } ?: return null
            val service = obj.optString("service").takeIf { it.isNotEmpty() } ?: return null
            val features = ArrayList<String>()
            obj.optJSONArray("features")?.let { arr ->
                for (i in 0 until minOf(arr.length(), UnsupportedApiStore.MAX_FEATURES)) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let { features.add(it) }
                }
            }
            return UnsupportedApiEntry(
                packageName = pkg,
                kind = kind,
                service = service,
                serviceId = if (obj.has("serviceId") && !obj.isNull("serviceId")) obj.optInt("serviceId") else null,
                gmsVersion = if (obj.has("gmsVersion") && !obj.isNull("gmsVersion")) obj.optInt("gmsVersion") else null,
                appVersionCode = if (obj.has("appVersionCode") && !obj.isNull("appVersionCode")) obj.optLong("appVersionCode") else null,
                appVersionName = obj.optString("appVersionName").takeIf { it.isNotEmpty() },
                microgVersionCode = if (obj.has("microgVersionCode") && !obj.isNull("microgVersionCode")) obj.optLong("microgVersionCode") else null,
                microgVersionName = obj.optString("microgVersionName").takeIf { it.isNotEmpty() },
                statusCode = if (obj.has("statusCode") && !obj.isNull("statusCode")) obj.optInt("statusCode") else null,
                statusName = obj.optString("statusName").takeIf { it.isNotEmpty() },
                features = features,
                count = obj.optLong("count", 1L).coerceAtLeast(1L),
                firstSeenAt = obj.optLong("firstSeenAt", 0L),
                lastSeenAt = obj.optLong("lastSeenAt", 0L),
            )
        }
    }
}

/**
 * Snapshot representation of a store slot, preserving undelivered [dirty] and [lastDrainedAt] state
 * across process restarts.
 */
data class PersistedUnsupportedApiSlot(
    val entry: UnsupportedApiEntry,
    val dirty: Boolean = true,
    val lastDrainedAt: Long = 0L,
) {
    fun toJson(): JSONObject = entry.toJson().apply {
        put("dirty", dirty)
        put("lastDrainedAt", lastDrainedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject?): PersistedUnsupportedApiSlot? {
            val entry = UnsupportedApiEntry.fromJson(obj) ?: return null
            val dirty = if (obj != null && obj.has("dirty")) obj.optBoolean("dirty", true) else true
            val lastDrainedAt = obj?.optLong("lastDrainedAt", 0L)?.coerceAtLeast(0L) ?: 0L
            return PersistedUnsupportedApiSlot(entry = entry, dirty = dirty, lastDrainedAt = lastDrainedAt)
        }
    }
}

/**
 * Bounded, synchronized in-memory store for [UnsupportedApiEntry] records with per-key re-report throttling.
 * Separated from Android APIs so all deduplication, feature-diff and drain logic is directly unit-testable.
 */
class UnsupportedApiStore(
    private val maxEntries: Int = MAX_ENTRIES,
    private val reReportWindowMs: Long = RE_REPORT_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Slot(
        var entry: UnsupportedApiEntry,
        var dirty: Boolean,
        var lastDrainedAt: Long,
    )

    private val slots = LinkedHashMap<String, Slot>()

    /**
     * Records an observation. Returns true whenever the observation was accepted and mutated the store
     * (new entry or updated timestamp/count), so callers can persist changes for cross-process readers
     * independently of the re-report throttle.
     */
    @Synchronized
    fun record(
        packageName: String?,
        kind: String,
        service: String?,
        serviceId: Int? = null,
        gmsVersion: Int? = null,
        statusCode: Int? = null,
        statusName: String? = null,
        features: List<String> = emptyList(),
        appVersionCode: Long? = null,
        appVersionName: String? = null,
        microgVersionCode: Long? = null,
        microgVersionName: String? = null,
    ): Boolean {
        val cleanPkg = cleanPackage(packageName) ?: return false
        val cleanService = clip(service, MAX_SERVICE_LENGTH) ?: return false
        val cleanFeatures = features.mapNotNull { clip(it, MAX_FEATURE_LENGTH) }.distinct().sorted().take(MAX_FEATURES)
        val cleanGmsVersion = gmsVersion?.takeIf { it > 0 }
        val cleanAppVersionName = clip(appVersionName, MAX_VERSION_NAME_LENGTH)
        val cleanMicrogVersionName = clip(microgVersionName, MAX_VERSION_NAME_LENGTH)
        val now = clock()
        val candidate = UnsupportedApiEntry(
            packageName = cleanPkg,
            kind = kind,
            service = cleanService,
            serviceId = serviceId,
            gmsVersion = cleanGmsVersion,
            appVersionCode = appVersionCode,
            appVersionName = cleanAppVersionName,
            microgVersionCode = microgVersionCode,
            microgVersionName = cleanMicrogVersionName,
            statusCode = statusCode,
            statusName = statusName,
            features = cleanFeatures,
            count = 1L,
            firstSeenAt = now,
            lastSeenAt = now,
        )
        val existing = slots.remove(candidate.key)
        if (existing != null) {
            val reDirty = !existing.dirty && (existing.lastDrainedAt <= 0L || now - existing.lastDrainedAt !in 0 until reReportWindowMs)
            existing.entry = existing.entry.copy(
                gmsVersion = cleanGmsVersion ?: existing.entry.gmsVersion,
                appVersionName = cleanAppVersionName ?: existing.entry.appVersionName,
                microgVersionName = cleanMicrogVersionName ?: existing.entry.microgVersionName,
                count = existing.entry.count + 1L,
                lastSeenAt = maxOf(existing.entry.lastSeenAt, now),
            )
            if (reDirty) existing.dirty = true
            slots[candidate.key] = existing
            return true
        }
        slots[candidate.key] = Slot(candidate, dirty = true, lastDrainedAt = 0L)
        while (slots.size > maxEntries) {
            val oldestKey = slots.entries.minByOrNull { it.value.entry.lastSeenAt }?.key ?: break
            slots.remove(oldestKey)
        }
        return true
    }

    /** Returns entries seen for [packageName] within [windowMs], newest first, bounded by [maxBytes], without clearing dirty bits. */
    @Synchronized
    fun recentFor(
        packageName: String?,
        windowMs: Long,
        limit: Int = DEFAULT_RECENT_LIMIT,
        maxBytes: Int = MAX_IPC_REPLY_BYTES,
    ): List<UnsupportedApiEntry> {
        val cleanPkg = cleanPackage(packageName) ?: return emptyList()
        val now = clock()
        val boundedWindow = windowMs.coerceIn(1_000L, MAX_RECENT_WINDOW_MS)
        val boundedLimit = limit.coerceIn(1, MAX_RECENT_LIMIT)
        val candidates = slots.values.asSequence()
            .map { it.entry }
            .filter { it.packageName == cleanPkg && now - it.lastSeenAt in 0..boundedWindow }
            .sortedByDescending { it.lastSeenAt }
            .take(boundedLimit)
            .toList()
        return fitWithinBytes(candidates, maxBytes).first
    }

    /**
     * Returns newly dirty entries (newest first, up to [limit] and [maxBytes]) WITHOUT clearing dirty flags.
     * The second element is true when additional dirty entries remain after this batch.
     */
    @Synchronized
    fun peekDirty(
        limit: Int = DEFAULT_DRAIN_LIMIT,
        maxBytes: Int = MAX_IPC_REPLY_BYTES,
    ): Pair<List<UnsupportedApiEntry>, Boolean> {
        val boundedLimit = limit.coerceIn(1, MAX_DRAIN_LIMIT)
        val allDirty = slots.values.filter { it.dirty }.sortedByDescending { it.entry.lastSeenAt }
        val capped = allDirty.take(boundedLimit).map { it.entry }
        val (fitted, overflowedBytes) = fitWithinBytes(capped, maxBytes)
        val hasMore = overflowedBytes || allDirty.size > fitted.size
        return fitted to hasMore
    }

    /**
     * Marks previously peeked entries clean only if no newer observation has arrived since [ackedLastSeenAt].
     * Returns the number of slots transitioned from dirty to clean.
     */
    @Synchronized
    fun ackDrained(acks: List<Pair<String, Long>>): Int {
        val now = clock()
        var acked = 0
        for ((key, ackedLastSeenAt) in acks) {
            val slot = slots[key] ?: continue
            if (slot.dirty && slot.entry.lastSeenAt <= ackedLastSeenAt) {
                slot.dirty = false
                slot.lastDrainedAt = now
                acked++
            }
        }
        return acked
    }

    /** Returns newly dirty entries (newest first, up to [limit] and [maxBytes]) and marks only the returned entries drained. */
    @Synchronized
    fun drainDirty(
        limit: Int = DEFAULT_DRAIN_LIMIT,
        maxBytes: Int = MAX_IPC_REPLY_BYTES,
    ): List<UnsupportedApiEntry> {
        val now = clock()
        val boundedLimit = limit.coerceIn(1, MAX_DRAIN_LIMIT)
        val dirtySlots = slots.values.filter { it.dirty }.sortedByDescending { it.entry.lastSeenAt }.take(boundedLimit)
        val fitted = fitWithinBytes(dirtySlots.map { it.entry }, maxBytes).first
        val fittedKeys = fitted.mapTo(HashSet(fitted.size)) { it.key }
        for (slot in dirtySlots) {
            if (slot.entry.key in fittedKeys) {
                slot.dirty = false
                slot.lastDrainedAt = now
            }
        }
        return fitted
    }

    /**
     * Restores slots persisted by this same process prior to restart, preserving undelivered [dirty]
     * and [lastDrainedAt] state while merging any observations recorded before restoration completed.
     */
    @Synchronized
    fun restoreOwn(persisted: List<PersistedUnsupportedApiSlot>) {
        for (saved in persisted) {
            val cleanPkg = cleanPackage(saved.entry.packageName) ?: continue
            val cleanService = clip(saved.entry.service, MAX_SERVICE_LENGTH) ?: continue
            val normalized = saved.entry.copy(
                packageName = cleanPkg,
                service = cleanService,
                features = saved.entry.features.mapNotNull { clip(it, MAX_FEATURE_LENGTH) }.distinct().sorted().take(MAX_FEATURES),
            )
            val existing = slots[normalized.key]
            if (existing == null) {
                slots[normalized.key] = Slot(normalized, dirty = saved.dirty, lastDrainedAt = saved.lastDrainedAt)
            } else {
                val mergedLastDrainedAt = maxOf(existing.lastDrainedAt, saved.lastDrainedAt)
                val mergedDirty = when {
                    existing.lastDrainedAt > 0L -> existing.dirty
                    saved.dirty -> true
                    existing.entry.lastSeenAt > normalized.lastSeenAt ->
                        saved.lastDrainedAt <= 0L || (existing.entry.lastSeenAt - saved.lastDrainedAt) !in 0 until reReportWindowMs
                    else -> existing.dirty
                }
                existing.entry = existing.entry.copy(
                    gmsVersion = existing.entry.gmsVersion ?: normalized.gmsVersion,
                    count = maxOf(existing.entry.count, normalized.count),
                    firstSeenAt = if (existing.entry.firstSeenAt > 0L && normalized.firstSeenAt > 0L)
                        minOf(existing.entry.firstSeenAt, normalized.firstSeenAt) else maxOf(existing.entry.firstSeenAt, normalized.firstSeenAt),
                    lastSeenAt = maxOf(existing.entry.lastSeenAt, normalized.lastSeenAt),
                )
                existing.lastDrainedAt = mergedLastDrainedAt
                existing.dirty = mergedDirty
            }
        }
        while (slots.size > maxEntries) {
            val oldestKey = slots.entries.minByOrNull { it.value.entry.lastSeenAt }?.key ?: break
            slots.remove(oldestKey)
        }
    }

    /** Merges persisted entries from a sibling process file. */
    @Synchronized
    fun mergeExternal(entries: List<UnsupportedApiEntry>, markDirtyIfNewer: Boolean = true) {
        val now = clock()
        for (incoming in entries) {
            val cleanPkg = cleanPackage(incoming.packageName) ?: continue
            val cleanService = clip(incoming.service, MAX_SERVICE_LENGTH) ?: continue
            val normalized = incoming.copy(
                packageName = cleanPkg,
                service = cleanService,
                features = incoming.features.mapNotNull { clip(it, MAX_FEATURE_LENGTH) }.distinct().sorted().take(MAX_FEATURES),
            )
            val existing = slots[normalized.key]
            if (existing == null) {
                slots[normalized.key] = Slot(normalized, dirty = markDirtyIfNewer, lastDrainedAt = if (markDirtyIfNewer) 0L else now)
            } else if (normalized.lastSeenAt > existing.entry.lastSeenAt || normalized.count > existing.entry.count) {
                existing.entry = existing.entry.copy(
                    gmsVersion = normalized.gmsVersion ?: existing.entry.gmsVersion,
                    count = maxOf(existing.entry.count, normalized.count),
                    firstSeenAt = if (existing.entry.firstSeenAt > 0L && normalized.firstSeenAt > 0L)
                        minOf(existing.entry.firstSeenAt, normalized.firstSeenAt) else maxOf(existing.entry.firstSeenAt, normalized.firstSeenAt),
                    lastSeenAt = maxOf(existing.entry.lastSeenAt, normalized.lastSeenAt),
                )
                if (markDirtyIfNewer && (existing.lastDrainedAt <= 0L || now - existing.lastDrainedAt !in 0 until reReportWindowMs)) {
                    existing.dirty = true
                }
            }
        }
        while (slots.size > maxEntries) {
            val oldestKey = slots.entries.minByOrNull { it.value.entry.lastSeenAt }?.key ?: break
            slots.remove(oldestKey)
        }
    }

    @Synchronized
    fun snapshotSlots(): List<PersistedUnsupportedApiSlot> =
        slots.values.map { PersistedUnsupportedApiSlot(it.entry, it.dirty, it.lastDrainedAt) }

    @Synchronized
    fun snapshotAll(): List<UnsupportedApiEntry> = slots.values.map { it.entry }

    @Synchronized
    fun clear() {
        slots.clear()
    }

    companion object {
        const val KIND_UNSUPPORTED_SERVICE = "unsupported_service"
        const val KIND_SERVICE_ERROR = "service_error"
        const val KIND_MISSING_FEATURES = "missing_features"
        const val KIND_UNRESOLVED_ACTION = "unresolved_action"
        const val KIND_MODULE_INSTALL = "module_install"

        const val MAX_ENTRIES = 256
        const val MAX_FEATURES = 16
        const val MAX_PACKAGE_LENGTH = 200
        const val MAX_SERVICE_LENGTH = 160
        const val MAX_FEATURE_LENGTH = 120
        const val MAX_VERSION_NAME_LENGTH = 100
        const val MAX_IPC_REPLY_BYTES = 128 * 1024
        const val DEFAULT_RECENT_LIMIT = 16
        const val MAX_RECENT_LIMIT = 32
        const val MAX_RECENT_WINDOW_MS = 10 * 60 * 1000L
        const val DEFAULT_DRAIN_LIMIT = 64
        const val MAX_DRAIN_LIMIT = 128
        const val RE_REPORT_WINDOW_MS = 6 * 60 * 60 * 1000L

        private const val MICROG_PACKAGE = "com.google.android.gms"
        private val PACKAGE_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

        fun cleanPackage(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            if (trimmed.isEmpty() || trimmed.length > MAX_PACKAGE_LENGTH) return null
            if (trimmed == MICROG_PACKAGE) return null
            return if (PACKAGE_REGEX.matches(trimmed)) trimmed else null
        }

        fun clip(raw: String?, maxLen: Int): String? {
            val clean = raw?.replace(Regex("\\s+"), " ")?.trim() ?: return null
            if (clean.isEmpty()) return null
            return if (clean.length <= maxLen) clean else clean.take(maxLen - 1) + "…"
        }

        /**
         * Returns the requested features that [advertised] lacks or advertises at a lower version.
         * Format: `name@req` when absent, or `name@req(have:adv)` when present at a lower version.
         */
        fun missingFeatures(requested: List<FeatureSpec>, advertised: List<FeatureSpec>): List<String> {
            if (requested.isEmpty()) return emptyList()
            val offered = HashMap<String, Long>(advertised.size)
            for (feat in advertised) {
                val name = feat.name.trim()
                if (name.isEmpty()) continue
                val prev = offered[name]
                if (prev == null || feat.version > prev) offered[name] = feat.version
            }
            val missing = LinkedHashSet<String>()
            for (req in requested) {
                val name = req.name.trim()
                if (name.isEmpty()) continue
                val have = offered[name]
                if (have == null) {
                    missing.add("$name@${req.version}")
                } else if (req.version > have) {
                    missing.add("$name@${req.version}(have:$have)")
                }
            }
            return missing.sorted().take(MAX_FEATURES)
        }

        /**
         * Maps ConnectionResult codes that indicate microG lacks or rejected a service/version to stable names.
         * Ordinary runtime outcomes (SUCCESS, SIGN_IN_REQUIRED, NETWORK_ERROR, CANCELED, etc.) return null.
         */
        fun unsupportedStatusName(statusCode: Int): String? = when (statusCode) {
            ConnectionResult.SERVICE_MISSING -> "SERVICE_MISSING"
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "SERVICE_VERSION_UPDATE_REQUIRED"
            ConnectionResult.SERVICE_DISABLED -> "SERVICE_DISABLED"
            ConnectionResult.API_UNAVAILABLE -> "API_UNAVAILABLE"
            ConnectionResult.SERVICE_UPDATING -> "SERVICE_UPDATING"
            ConnectionResult.API_DISABLED -> "API_DISABLED"
            ConnectionResult.API_DISABLED_FOR_CONNECTION -> "API_DISABLED_FOR_CONNECTION"
            else -> null
        }

        fun formatFeatureSpecs(features: List<FeatureSpec>): List<String> =
            features.mapNotNull { f ->
                val name = f.name.trim()
                if (name.isEmpty()) null else "$name@${f.version}"
            }.distinct().sorted().take(MAX_FEATURES)

        /**
         * Selects the longest prefix of [entries] whose serialized JSON array stays within [maxBytes] UTF-8 bytes
         * (keeping Binder ContentProvider replies safely below Android's TransactionTooLargeException ceiling).
         */
        fun fitWithinBytes(
            entries: List<UnsupportedApiEntry>,
            maxBytes: Int = MAX_IPC_REPLY_BYTES,
        ): Pair<List<UnsupportedApiEntry>, Boolean> {
            if (entries.isEmpty() || maxBytes <= 2) return emptyList<UnsupportedApiEntry>() to entries.isNotEmpty()
            val out = ArrayList<UnsupportedApiEntry>(entries.size)
            var usedBytes = 2 // "[]"
            var overflowed = false
            for (entry in entries) {
                val itemBytes = entry.toJson().toString().toByteArray(Charsets.UTF_8).size
                val sepBytes = if (out.isNotEmpty()) 1 else 0
                if (usedBytes + sepBytes + itemBytes > maxBytes) {
                    if (out.isEmpty()) {
                        // Single entry exceeds cap on its own: skip rather than wedging Binder forever.
                        overflowed = true
                        continue
                    }
                    overflowed = true
                    break
                }
                out.add(entry)
                usedBytes += sepBytes + itemBytes
            }
            return out to overflowed
        }
    }
}

/**
 * Process-wide hook used by [org.microg.gms.AbstractGmsServiceBroker], [org.microg.gms.chimera.ServiceProvider]
 * and [org.microg.gms.moduleinstall.ModuleInstallService] to record unsupported services and missing features
 * without ever throwing or blocking GMS client binds.
 */
object UnsupportedApiRecorder {
    private const val TAG = "CyclonUnsupportedApi"
    const val DIR_NAME = "cyclon-unsupported-api"
    const val MAX_FILE_BYTES = 512 * 1024

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var processTag: String = "default"

    @Volatile
    private var microgVersionCode: Long? = null

    @Volatile
    private var microgVersionName: String? = null

    private val loadLock = Any()

    @Volatile
    private var ownSnapshotLoaded = false

    private val persistPending = AtomicBoolean(false)
    private val persistScheduled = AtomicBoolean(false)

    val store = UnsupportedApiStore()

    private val io = ThreadPoolExecutor(
        0, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "cyclon-unsupported-api-io").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        val (verCode, verName) = resolvePackageVersion(context, "com.google.android.gms")
            .let { if (it.first != null) it else resolvePackageVersion(app, app.packageName) }
        synchronized(loadLock) {
            appContext = app
            processTag = sanitizeProcessTag(currentProcessName(app))
            microgVersionCode = verCode
            microgVersionName = verName
            ownSnapshotLoaded = false
        }
        io.execute {
            runCatching { ensureOwnSnapshotLoaded(app) }
        }
    }

    @JvmStatic
    fun wrapCallback(
        delegate: IGmsCallbacks?,
        request: GetServiceRequest?,
        service: GmsService?,
        callingUid: Int,
    ): IGmsCallbacks? {
        if (delegate == null || request == null) return delegate
        val requestedFeatures = extractRequestedFeatures(request)
        val serviceId = request.serviceId
        val serviceName = resolveServiceName(service, serviceId)
        val gmsVersion = request.gmsVersion
        val reqPkg = request.packageName
        return object : IGmsCallbacks {
            override fun asBinder(): IBinder = delegate.asBinder()

            override fun onPostInitComplete(statusCode: Int, binder: IBinder?, params: Bundle?) {
                runCatching {
                    recordPostInit(
                        requestedPkg = reqPkg,
                        callingUid = callingUid,
                        serviceName = serviceName,
                        serviceId = serviceId,
                        gmsVersion = gmsVersion,
                        statusCode = statusCode,
                        requestedFeatures = requestedFeatures,
                        advertisedFeatures = emptyList(),
                    )
                }
                delegate.onPostInitComplete(statusCode, binder, params)
            }

            override fun onAccountValidationComplete(statusCode: Int, params: Bundle?) {
                delegate.onAccountValidationComplete(statusCode, params)
            }

            override fun onPostInitCompleteWithConnectionInfo(
                statusCode: Int,
                binder: IBinder?,
                info: ConnectionInfo?,
            ) {
                runCatching {
                    val advertised = info?.features?.mapNotNull { toSpec(it) }.orEmpty()
                    recordPostInit(
                        requestedPkg = reqPkg,
                        callingUid = callingUid,
                        serviceName = serviceName,
                        serviceId = serviceId,
                        gmsVersion = gmsVersion,
                        statusCode = statusCode,
                        requestedFeatures = requestedFeatures,
                        advertisedFeatures = advertised,
                    )
                }
                delegate.onPostInitCompleteWithConnectionInfo(statusCode, binder, info)
            }
        }
    }

    @JvmStatic
    fun recordUnsupportedService(request: GetServiceRequest?, service: GmsService?, callingUid: Int) {
        if (request == null) return
        runCatching {
            val pkg = resolveCallerPackage(request.packageName, callingUid) ?: return
            val (appVerCode, appVerName) = resolvePackageVersion(appContext, pkg)
            val requested = UnsupportedApiStore.formatFeatureSpecs(extractRequestedFeatures(request))
            val changed = store.record(
                packageName = pkg,
                kind = UnsupportedApiStore.KIND_UNSUPPORTED_SERVICE,
                service = resolveServiceName(service, request.serviceId),
                serviceId = request.serviceId,
                gmsVersion = request.gmsVersion,
                features = requested,
                appVersionCode = appVerCode,
                appVersionName = appVerName,
                microgVersionCode = microgVersionCode,
                microgVersionName = microgVersionName,
            )
            if (changed) schedulePersist()
        }
    }

    @JvmStatic
    fun recordUnresolvedAction(serviceAction: String?, callingUid: Int) {
        if (serviceAction.isNullOrBlank()) return
        runCatching {
            val pkg = resolveCallerPackage(null, callingUid) ?: return
            val (appVerCode, appVerName) = resolvePackageVersion(appContext, pkg)
            val changed = store.record(
                packageName = pkg,
                kind = UnsupportedApiStore.KIND_UNRESOLVED_ACTION,
                service = serviceAction,
                appVersionCode = appVerCode,
                appVersionName = appVerName,
                microgVersionCode = microgVersionCode,
                microgVersionName = microgVersionName,
            )
            if (changed) schedulePersist()
        }
    }

    @JvmStatic
    fun recordModuleInstallProbe(callingPackage: String?, features: List<Feature>?, callingUid: Int) {
        if (features.isNullOrEmpty()) return
        runCatching {
            val pkg = resolveCallerPackage(callingPackage, callingUid) ?: return
            val specs = features.mapNotNull { toSpec(it) }
            val formatted = UnsupportedApiStore.formatFeatureSpecs(specs)
            if (formatted.isEmpty()) return
            val (appVerCode, appVerName) = resolvePackageVersion(appContext, pkg)
            val changed = store.record(
                packageName = pkg,
                kind = UnsupportedApiStore.KIND_MODULE_INSTALL,
                service = GmsService.MODULE_INSTALL.name,
                serviceId = GmsService.MODULE_INSTALL.SERVICE_ID,
                features = formatted,
                appVersionCode = appVerCode,
                appVersionName = appVerName,
                microgVersionCode = microgVersionCode,
                microgVersionName = microgVersionName,
            )
            if (changed) schedulePersist()
        }
    }

    fun recentFor(
        context: Context,
        packageName: String?,
        windowMs: Long,
        limit: Int,
        maxBytes: Int = UnsupportedApiStore.MAX_IPC_REPLY_BYTES,
    ): List<UnsupportedApiEntry> {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        ensureOwnSnapshotLoaded(app)
        mergeSiblingSnapshots(app)
        return store.recentFor(packageName, windowMs, limit, maxBytes)
    }

    fun peekDirty(
        context: Context,
        limit: Int,
        maxBytes: Int = UnsupportedApiStore.MAX_IPC_REPLY_BYTES,
    ): Pair<List<UnsupportedApiEntry>, Boolean> {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        ensureOwnSnapshotLoaded(app)
        mergeSiblingSnapshots(app)
        return store.peekDirty(limit, maxBytes)
    }

    fun ackDrained(context: Context, acks: List<Pair<String, Long>>): Int {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        ensureOwnSnapshotLoaded(app)
        val acked = store.ackDrained(acks)
        if (acked > 0) schedulePersist()
        return acked
    }

    fun drainDirty(
        context: Context,
        limit: Int,
        maxBytes: Int = UnsupportedApiStore.MAX_IPC_REPLY_BYTES,
    ): List<UnsupportedApiEntry> {
        val app = context.applicationContext ?: context
        if (appContext == null) appContext = app
        ensureOwnSnapshotLoaded(app)
        mergeSiblingSnapshots(app)
        val drained = store.drainDirty(limit, maxBytes)
        if (drained.isNotEmpty()) schedulePersist()
        return drained
    }

    private fun recordPostInit(
        requestedPkg: String?,
        callingUid: Int,
        serviceName: String,
        serviceId: Int,
        gmsVersion: Int,
        statusCode: Int,
        requestedFeatures: List<FeatureSpec>,
        advertisedFeatures: List<FeatureSpec>,
    ) {
        val errorName = UnsupportedApiStore.unsupportedStatusName(statusCode)
        if (errorName != null) {
            val pkg = resolveCallerPackage(requestedPkg, callingUid) ?: return
            val (appVerCode, appVerName) = resolvePackageVersion(appContext, pkg)
            val changed = store.record(
                packageName = pkg,
                kind = UnsupportedApiStore.KIND_SERVICE_ERROR,
                service = serviceName,
                serviceId = serviceId,
                gmsVersion = gmsVersion,
                statusCode = statusCode,
                statusName = errorName,
                features = UnsupportedApiStore.formatFeatureSpecs(requestedFeatures),
                appVersionCode = appVerCode,
                appVersionName = appVerName,
                microgVersionCode = microgVersionCode,
                microgVersionName = microgVersionName,
            )
            if (changed) schedulePersist()
            return
        }
        if (statusCode == ConnectionResult.SUCCESS && requestedFeatures.isNotEmpty()) {
            val missing = UnsupportedApiStore.missingFeatures(requestedFeatures, advertisedFeatures)
            if (missing.isEmpty()) return
            val pkg = resolveCallerPackage(requestedPkg, callingUid) ?: return
            val (appVerCode, appVerName) = resolvePackageVersion(appContext, pkg)
            val changed = store.record(
                packageName = pkg,
                kind = UnsupportedApiStore.KIND_MISSING_FEATURES,
                service = serviceName,
                serviceId = serviceId,
                gmsVersion = gmsVersion,
                features = missing,
                appVersionCode = appVerCode,
                appVersionName = appVerName,
                microgVersionCode = microgVersionCode,
                microgVersionName = microgVersionName,
            )
            if (changed) schedulePersist()
        }
    }

    private fun resolvePackageVersion(ctx: Context?, pkg: String): Pair<Long?, String?> {
        if (ctx == null) return null to null
        return runCatching {
            val info = ctx.packageManager.getPackageInfo(pkg, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            code.takeIf { it > 0L } to info.versionName
        }.getOrElse { null to null }
    }

    private fun extractRequestedFeatures(request: GetServiceRequest): List<FeatureSpec> {
        val out = ArrayList<FeatureSpec>()
        request.apiFeatures?.forEach { toSpec(it)?.let { spec -> out.add(spec) } }
        request.defaultFeatures?.forEach { toSpec(it)?.let { spec -> out.add(spec) } }
        return out
    }

    private fun toSpec(feature: Feature?): FeatureSpec? {
        val name = feature?.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return FeatureSpec(name, feature.version)
    }

    private fun resolveServiceName(service: GmsService?, serviceId: Int): String =
        if (service != null && service != GmsService.UNKNOWN) service.name else GmsService.nameFromServiceId(serviceId)

    fun resolveCallerPackage(requested: String?, callingUid: Int): String? {
        val ctx = appContext
        val uidPackages = if (ctx != null && callingUid > 0 && callingUid != Process.myUid()) {
            runCatching { ctx.packageManager.getPackagesForUid(callingUid)?.toList() }.getOrNull()
        } else {
            null
        }
        val cleanRequested = UnsupportedApiStore.cleanPackage(requested)
        if (uidPackages != null && uidPackages.isNotEmpty()) {
            if (cleanRequested != null && cleanRequested in uidPackages) return cleanRequested
            return uidPackages.firstNotNullOfOrNull { UnsupportedApiStore.cleanPackage(it) }
        }
        return cleanRequested
    }

    private fun schedulePersist() {
        val ctx = appContext ?: return
        persistPending.set(true)
        if (!persistScheduled.compareAndSet(false, true)) return
        try {
            io.execute {
                try {
                    while (persistPending.getAndSet(false)) {
                        runCatching {
                            ensureOwnSnapshotLoaded(ctx)
                            writeOwnSnapshot(ctx)
                        }.onFailure {
                            Log.w(TAG, "Persist skipped: ${it.javaClass.simpleName}")
                        }
                    }
                } finally {
                    persistScheduled.set(false)
                    if (persistPending.get()) schedulePersist()
                }
            }
        } catch (_: Throwable) {
            persistScheduled.set(false)
        }
    }

    fun flushPersist(timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val latch = CountDownLatch(1)
            io.execute { latch.countDown() }
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            if (!latch.await(remaining, TimeUnit.MILLISECONDS)) return false
            if (!persistScheduled.get() && !persistPending.get()) return true
            if (System.currentTimeMillis() >= deadline) return false
        }
    }

    fun serializeSnapshot(slots: List<PersistedUnsupportedApiSlot>, maxBytes: Int = MAX_FILE_BYTES): String {
        val prefix = """{"version":1,"entries":["""
        val suffix = "]}"
        val overhead = prefix.toByteArray(Charsets.UTF_8).size + suffix.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder(prefix)
        var usedBytes = overhead
        var written = 0
        val ordered = slots.sortedWith(
            compareByDescending<PersistedUnsupportedApiSlot> { it.dirty }
                .thenByDescending { it.entry.lastSeenAt },
        )
        for (slot in ordered) {
            val serialized = slot.toJson().toString()
            val itemBytes = serialized.toByteArray(Charsets.UTF_8).size
            val sepBytes = if (written > 0) 1 else 0
            if (usedBytes + sepBytes + itemBytes > maxBytes) continue
            if (written > 0) sb.append(',')
            sb.append(serialized)
            usedBytes += sepBytes + itemBytes
            written++
        }
        sb.append(suffix)
        return sb.toString()
    }

    fun parseSnapshot(text: String): List<PersistedUnsupportedApiSlot> {
        val arr = JSONObject(text).optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<PersistedUnsupportedApiSlot>(minOf(arr.length(), UnsupportedApiStore.MAX_ENTRIES))
        for (i in 0 until minOf(arr.length(), UnsupportedApiStore.MAX_ENTRIES)) {
            PersistedUnsupportedApiSlot.fromJson(arr.optJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    private fun storageDir(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

    private fun writeOwnSnapshot(context: Context) {
        val dir = storageDir(context)
        if (!dir.exists() && !dir.mkdirs()) return
        val tag = processTag
        val payload = serializeSnapshot(store.snapshotSlots(), MAX_FILE_BYTES)
        val tmp = File(dir, "$tag.json.tmp")
        val target = File(dir, "$tag.json")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun ensureOwnSnapshotLoaded(context: Context) {
        if (ownSnapshotLoaded) return
        synchronized(loadLock) {
            if (ownSnapshotLoaded) return
            val file = File(storageDir(context), "$processTag.json")
            if (file.isFile && file.length() <= MAX_FILE_BYTES) {
                runCatching {
                    val parsed = readSnapshotFile(file)
                    if (parsed.isNotEmpty()) store.restoreOwn(parsed)
                }
            }
            ownSnapshotLoaded = true
        }
    }

    private fun mergeSiblingSnapshots(context: Context) {
        val dir = storageDir(context)
        val ownName = "$processTag.json"
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != ownName } ?: return
        for (file in files) {
            if (file.length() > MAX_FILE_BYTES) continue
            runCatching {
                val parsed = readSnapshotFile(file)
                if (parsed.isNotEmpty()) store.mergeExternal(parsed.map { it.entry }, markDirtyIfNewer = true)
            }
        }
    }

    private fun readSnapshotFile(file: File): List<PersistedUnsupportedApiSlot> =
        parseSnapshot(file.readText(Charsets.UTF_8))

    private fun currentProcessName(context: Context): String {
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { Application.getProcessName() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return context.packageName
    }

    private fun sanitizeProcessTag(processName: String): String {
        val suffix = processName.substringAfter(':', "default")
        return suffix.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32).ifEmpty { "default" }
    }
}
