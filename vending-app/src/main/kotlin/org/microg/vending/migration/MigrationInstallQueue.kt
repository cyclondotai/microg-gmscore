/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.DeadObjectException
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Durable, separate receipts for each transfer. A status query never authorizes another install. */
internal class MigrationInstallQueue(private val context: Context,
    private val controlCall: ((Int, Bundle) -> Bundle)? = null,
    private val storeCall: ((Int, String) -> Bundle)? = null) {
    /** Tests: the options each faked [storeCall] request would carry to Aurora. */
    internal var sentOptions: ((Int, Bundle?) -> Unit)? = null
    /** Tests: optional override for post-install app-data restore dispatch. */
    internal var appDataRestore: ((String, String) -> MigrationAppDataRestore.Result)? = null
    /** Tests: optional wall-clock override for bulk-deferral expiry and renewal scheduling. */
    internal var clock: () -> Long = { System.currentTimeMillis() }
    /** Cached Aurora capabilities (op 7) for the current dispatch round so failures don't re-bind in a tight loop. */
    @Volatile private var roundCapabilities: Bundle? = null
    /** Last actionable priority list already sent to Aurora in the current dispatch round, per session. */
    private val roundSyncedPriority = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private val prefs = context.getSharedPreferences("migration-installs", Context.MODE_PRIVATE)
    /** Play answered 429 since the job last cleared this; stop the round and wait before asking again. */
    @Volatile var throttled = false
    private data class DispatchEntry(
        val item: Pair<String, String>,
        val throttledAt: Long,
        val tier: Int,
        val priorityRank: Int,
        val sizeRank: Long,
    )
    /** Ordered priority packages for [id], falling back to the legacy unordered set if no order was stored. */
    fun priorityOrder(id: String = currentSessionId().orEmpty()): List<String> {
        val stored = prefs.getString("$id:priority_order", null)
        if (stored != null) return stored.split(',').filter { it.isNotEmpty() }
        return prefs.getStringSet("$id:priority", emptySet()).orEmpty().sorted()
    }
    /** Whether non-prioritized bulk apps for [id] are buffered until Switch finishes copying (CYC-3422). */
    fun isBulkDeferred(id: String = currentSessionId().orEmpty(), nowMs: Long = clock()): Boolean {
        if (id.isEmpty()) return false
        if (prefs.getString("handoff_session", null) != id) return false
        if (!prefs.getBoolean("$id:defer_bulk", false)) return false
        if (prefs.getBoolean("$id:bulk_released", false)) return false
        if (prefs.getBoolean("$id:selection_closed", false)) return false
        if (prefs.getString("$id:transfer_outcome", null) != null) return false
        if (prefs.contains("handoff_outcome")) return false
        val deferredAt = prefs.getLong("$id:defer_bulk_at", 0L)
        if (deferredAt > 0L && nowMs - deferredAt >= MAX_DEFER_BULK_MS) return false
        return true
    }
    private fun isBulkDeferred(snapshot: Map<String, *>, id: String, nowMs: Long = clock()): Boolean {
        if (id.isEmpty()) return false
        if (snapshot["handoff_session"] != id) return false
        if (snapshot["$id:defer_bulk"] != true) return false
        if (snapshot["$id:bulk_released"] == true) return false
        if (snapshot["$id:selection_closed"] == true) return false
        if (snapshot["$id:transfer_outcome"] != null) return false
        if (snapshot.containsKey("handoff_outcome")) return false
        val deferredAt = snapshot["$id:defer_bulk_at"] as? Long ?: 0L
        if (deferredAt > 0L && nowMs - deferredAt >= MAX_DEFER_BULK_MS) return false
        return true
    }
    /** Remaining wall-clock time until [id]'s bulk deferral fails open, or 0 when not deferred / already expired. */
    internal fun deferBulkRemainingMs(id: String, nowMs: Long = clock()): Long {
        if (!isBulkDeferred(id, nowMs)) return 0L
        val deferredAt = prefs.getLong("$id:defer_bulk_at", 0L)
        if (deferredAt <= 0L) return MAX_DEFER_BULK_MS
        return (MAX_DEFER_BULK_MS - (nowMs - deferredAt)).coerceIn(0L, MAX_DEFER_BULK_MS)
    }
    /** Whether [id]'s bulk apps opted into smallest-first ordering (durable across Pause rebind and release). */
    private fun isSmallestFirstBulk(id: String): Boolean =
        prefs.getBoolean("$id:defer_opted_in", false) || prefs.getBoolean("$id:defer_bulk", false)
    /** Pending installs across sessions. Apps Play refused most recently go last, so one cannot block the rest. */
    fun dispatchOrder(nowMs: Long = clock()): List<Pair<String, String>> = sessions().flatMap { id ->
        // Each app's sort keys are read once, not on every comparison (a stable sort on the same keys: the same order).
        val ordered = priorityOrder(id)
        val priority = ordered.toSet()
        val deferred = isBulkDeferred(id, nowMs)
        val smallestFirst = isSmallestFirstBulk(id)
        val rank = if (prefs.contains("$id:priority_order")) ordered.withIndex().associate { (i, name) -> name to i } else emptyMap()
        pending(id).asSequence()
            .filter { name -> !deferred || name in priority }
            .sorted()
            .map { name ->
                val entryTier = tier(id, name, priority)
                val sizeRank = if (smallestFirst && entryTier > 0) {
                    val size = prefs.getLong("$id:$name:installSize", 0L)
                    if (size > 0L) size else Long.MAX_VALUE
                } else {
                    Long.MAX_VALUE
                }
                DispatchEntry(
                    id to name,
                    prefs.getLong("$id:$name:throttled_at", 0),
                    entryTier,
                    rank[name] ?: Int.MAX_VALUE,
                    sizeRank,
                )
            }.toList()
    }.sortedWith(compareBy({ it.throttledAt }, { it.tier }, { it.priorityRank }, { it.sizeRank })).map { it.item }
    /**
     * Within a session's open apps: the ones Setup asked for first ([prioritize]: the sign-in apps), then the ones going to
     * the phone's Google account after Play refused the anonymous session, then the rest in package order (11-17
     * founders-02: Google Authenticator waited behind every "ai.", "app." and "ch." app, and two refused apps behind the
     * whole 287-app pass).
     */
    private fun tier(id: String, name: String, priority: Set<String>): Int = when {
        name in priority -> 0
        prefs.getBoolean("$id:$name:owner_account", false) -> 1
        else -> 2
    }
    /** Already dispatched to Aurora and waiting in its queue (not actively downloading/installing or settled). */
    private fun isQueuedNotActive(id: String, name: String): Boolean {
        if (!isSelected(id, name) || isPending(id, name)) return false
        if (prefs.getBoolean("$id:$name:terminal", false) || prefs.getBoolean("$id:$name:installed", false)) return false
        val status = prefs.getString("$id:$name:status", null)
        return status == "queued" || status == "none" || status.isNullOrEmpty()
    }
    /** Whether [name] in [id] is still pending dispatch or non-terminal/uninstalled in Aurora. */
    private fun isPriorityActive(id: String, name: String): Boolean =
        isPending(id, name) || (isSelected(id, name) &&
            !prefs.getBoolean("$id:$name:terminal", false) &&
            !prefs.getBoolean("$id:$name:installed", false))
    /**
     * Retains priority packages up to [MAX_PRIORITY_TOTAL] without ever evicting still-pending or active
     * selected packages back into deferred limbo: settled entries are pruned first, then unselected entries.
     */
    private fun trimPriorityOrder(id: String, merged: List<String>): List<String> {
        if (merged.size <= MAX_PRIORITY_TOTAL) return merged
        val selectionClosed = prefs.getBoolean("$id:selection_closed", false)
        val active = merged.filter { isPriorityActive(id, it) }
        val activeSet = active.toSet()
        var room = (MAX_PRIORITY_TOTAL - active.size).coerceAtLeast(0)
        val staged = if (room > 0 && !selectionClosed) {
            merged.filter { it !in activeSet && !isSelected(id, it) }.take(room)
        } else {
            emptyList()
        }
        room = (room - staged.size).coerceAtLeast(0)
        val stagedSet = staged.toSet()
        val settled = if (room > 0) {
            merged.filter { it !in activeSet && it !in stagedSet }.take(room)
        } else {
            emptyList()
        }
        val keep = activeSet + stagedSet + settled
        return merged.filter { it in keep }
    }
    /**
     * Setup's sign-in apps for its handoff [run] go to Aurora before the copy's other apps (Core's
     * setup.transfer.handoff.prioritize). Requested packages move to the front of the priority order so
     * pending and already-queued (non-downloading) apps run next right after any active download.
     */
    fun prioritize(run: String, packages: List<String>): Bundle {
        val wanted = packages.filter { PACKAGE.matches(it) }.distinct().take(MAX_PRIORITY)
        val hasQueued = synchronized(LOCK) {
            val id = prefs.getString("handoff_session", null)
            prefs.getString("handoff_run", null) == run && id != null && wanted.any { isQueuedNotActive(id, it) }
        }
        // Check Aurora's prioritize_downloads capability outside LOCK only when already-queued apps need store reorder.
        val storeSupportsPrioritize = if (hasQueued) {
            val caps = control(7, Bundle())
            if (caps.getString("error") != "store_unavailable") {
                val supported = caps.getBoolean("prioritize_downloads")
                synchronized(LOCK) { prefs.edit().putBoolean("store_prioritize_supported", supported).apply() }
                supported
            } else {
                prefs.getBoolean("store_prioritize_supported", false)
            }
        } else {
            prefs.getBoolean("store_prioritize_supported", false)
        }
        return synchronized(LOCK) {
            val id = prefs.getString("handoff_session", null)
            if (prefs.getString("handoff_run", null) != run || id == null) return@synchronized Bundle().apply {
                putBoolean("supported", true); putBoolean("accepted", false); putInt("prioritized", 0)
            }
            val wantedSet = wanted.toSet()
            val merged = wanted + priorityOrder(id).filterNot { it in wantedSet }
            val ordered = trimPriorityOrder(id, merged)
            val edit = prefs.edit()
                .putString("$id:priority_order", ordered.joinToString(","))
                .putStringSet("$id:priority", ordered.toSet())
            if (wanted.isNotEmpty()) {
                edit.putBoolean("$id:priority_dirty", true)
                roundSyncedPriority.remove(id)
                val now = clock()
                if (isBulkDeferred(id, now)) edit.putLong("$id:defer_bulk_at", now)
            }
            check(edit.commit())
            val open = wanted.count { isPending(id, it) || (storeSupportsPrioritize && isQueuedNotActive(id, it)) }
            if (open > 0) schedule()
            Bundle().apply {
                putBoolean("supported", true)
                putBoolean("accepted", true)
                putInt("prioritized", open)
                putBoolean("store_prioritize_supported", storeSupportsPrioritize)
            }
        }
    }
    init {
        // PlaySetupService opens the queue on the main thread, which must never wait for an install in flight.
        if (legacy()) synchronized(LOCK) {
            if (legacy()) {
                val id = "previous-transfer"
                val names = prefs.getStringSet("packages", emptySet()).orEmpty().toSet()
                val ambiguous = prefs.getStringSet("pending", emptySet()).orEmpty()
                val edit = prefs.edit().putString("sessions", id).putString("current_session", id)
                    .putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true).putStringSet("$id:packages", names)
                for (name in names) {
                    for (key in listOf("status", "error")) edit.putString("$id:$name:$key", prefs.getString("$name:$key", null))
                    edit.putLong("$id:$name:expectedVersion", prefs.getLong("$name:expectedVersion", 0))
                    edit.putBoolean("$id:$name:ever_installed", prefs.getBoolean("$name:installed", false))
                    if (name in ambiguous) {
                        // Legacy requests have no session identity. Require an owner retry rather than replaying an unknown install.
                        edit.putString("$id:$name:status", "failed").putString("$id:$name:error", "previous_transfer_requires_review")
                            .putBoolean("$id:$name:terminal", true)
                    }
                }
                check(edit.commit())
            }
        }
    }
    private fun legacy() = !prefs.contains("sessions") && prefs.contains("packages")
    fun currentSessionId(): String? = prefs.getString("current_session", null)
    fun sessions(): List<String> = prefs.getString("sessions", "").orEmpty().split(',').filter { it.isNotEmpty() }
    /** Whether [id] lists any app, chosen or left out. */
    fun hasApps(id: String): Boolean = selected(id).isNotEmpty() || prefs.getStringSet("$id:excluded", null)?.isNotEmpty() == true
    /**
     * The transfer the results screen opens on: [requestedId] when it names a known transfer, else the current one when it
     * lists apps, else the latest that does. An empty transfer (a hand-off cancelled before any app was chosen) never hides
     * an earlier one with apps; with none, the current transfer.
     */
    fun resultsSession(requestedId: String? = null): String? {
        val ids = sessions()
        requestedId?.takeIf { it in ids }?.let { return it }
        val current = currentSessionId()
        if (current != null && hasApps(current)) return current
        return ids.firstOrNull(::hasApps) ?: current
    }
    /** How many apps [id] lists, chosen or left out. */
    fun appCount(id: String): Int {
        val selected = selected(id)
        return selected.size + prefs.getStringSet("$id:excluded", emptySet()).orEmpty().count { it !in selected }
    }
    /** Transfers worth offering in the results screen's picker, newest first: those with apps, and [requestedId]. */
    fun visibleSessions(requestedId: String? = null): List<String> =
        sessions().filter { it == requestedId || hasApps(it) }
    fun beginTransfer(): String {
        val id = synchronized(LOCK) { newSession() }
        acknowledgeTransferStart(id)
        return id
    }

    private fun acknowledgeTransferStart(id: String) {
        if (MigrationAppDataRestore.notifyTransferStarting(context)) {
            synchronized(LOCK) {
                check(prefs.edit().putBoolean("$id:start_acknowledged", true).commit())
            }
        }
    }

    private fun carryHandoffState(edit: android.content.SharedPreferences.Editor, from: String, to: String) {
        if (prefs.getBoolean("$from:defer_bulk", false)) {
            edit.putBoolean("$to:defer_bulk", true).putBoolean("$from:defer_bulk", false)
        }
        if (prefs.getBoolean("$from:bulk_released", false)) {
            edit.putBoolean("$to:bulk_released", true)
        }
        if (prefs.getBoolean("$from:defer_opted_in", false)) {
            edit.putBoolean("$to:defer_opted_in", true)
        }
        val deferredAt = prefs.getLong("$from:defer_bulk_at", 0L)
        if (deferredAt > 0L) {
            edit.putLong("$to:defer_bulk_at", deferredAt)
        }
        prefs.getString("$from:priority_order", null)?.let {
            edit.putString("$to:priority_order", it).remove("$from:priority_order")
        }
        val stagedPriority = prefs.getStringSet("$from:priority", null)
        if (!stagedPriority.isNullOrEmpty()) {
            edit.putStringSet("$to:priority", stagedPriority.toSet()).remove("$from:priority")
        }
        if (prefs.getBoolean("$from:priority_dirty", false)) {
            edit.putBoolean("$to:priority_dirty", true).putBoolean("$from:priority_dirty", false)
        }
    }

    private fun newSession(run: String? = null, deferBulk: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        val edit = prefs.edit().putString("current_session", id).putString("sessions", (listOf(id) + sessions()).joinToString(","))
        val handoffSession = prefs.getString("handoff_session", null)
        var supersededDeferredId: String? = null
        if (run != null) {
            // MigrationActivity calls SinglePackageRestore.markTransferLaunched in-process before beginHandoff(run).
            edit.putString("handoff_run", run).putString("handoff_session", id).remove("handoff_outcome")
                .putBoolean("$id:start_acknowledged", true)
            if (deferBulk) {
                edit.putBoolean("$id:defer_bulk", true)
                    .putBoolean("$id:defer_opted_in", true)
                    .putLong("$id:defer_bulk_at", clock())
            }
            if (handoffSession != null && handoffSession != id && prefs.getBoolean("$handoffSession:defer_bulk", false)) {
                edit.putBoolean("$handoffSession:defer_bulk", false).putBoolean("$handoffSession:bulk_released", true)
                supersededDeferredId = handoffSession
            }
        } else if (prefs.contains("handoff_run") && !prefs.contains("handoff_outcome") &&
            (handoffSession == null || !prefs.getBoolean("$handoffSession:selected", false))) {
            edit.putString("handoff_session", id)
            if (handoffSession != null && handoffSession != id) {
                carryHandoffState(edit, from = handoffSession, to = id)
            }
        }
        check(edit.commit())
        if (supersededDeferredId != null) {
            changed(supersededDeferredId)
            if (pending(supersededDeferredId).isNotEmpty()) schedule()
        }
        return id
    }

    fun beginHandoff(run: String, resumeOnly: Boolean, deferBulk: Boolean = false): Bundle = synchronized(LOCK) {
        if (prefs.getString("handoff_run", null) != run) {
            if (resumeOnly) return Bundle().apply { putBoolean("accepted", false) }
            newSession(run, deferBulk = deferBulk)
            return Bundle().apply { putBoolean("accepted", true) }
        }
        val accepted = !prefs.contains("handoff_outcome")
        val id = prefs.getString("handoff_session", null)
        if (accepted && id != null) {
            val now = clock()
            if (isBulkDeferred(id, now)) {
                // Live heartbeat on an active deferred copy extends the fail-open cap.
                if (resumeOnly || deferBulk) {
                    check(prefs.edit().putLong("$id:defer_bulk_at", now).commit())
                }
            } else if (deferBulk &&
                !prefs.getBoolean("$id:defer_bulk", false) &&
                !prefs.getBoolean("$id:selection_closed", false) &&
                !prefs.getBoolean("$id:bulk_released", false)
            ) {
                check(prefs.edit()
                    .putBoolean("$id:defer_bulk", true)
                    .putBoolean("$id:defer_opted_in", true)
                    .putLong("$id:defer_bulk_at", now)
                    .commit())
            }
        }
        Bundle().apply { putBoolean("accepted", accepted) }
    }
    /** Releases non-prioritized bulk apps for [run] once Switch finishes copying (CYC-3422). */
    fun releaseBulkHandoff(run: String): Bundle = synchronized(LOCK) {
        val id = prefs.getString("handoff_session", null)
        if (prefs.getString("handoff_run", null) != run || id == null) {
            return@synchronized Bundle().apply {
                putBoolean("supported", true)
                putBoolean("accepted", false)
            }
        }
        val wasDeferred = prefs.getBoolean("$id:defer_bulk", false)
        check(prefs.edit().putBoolean("$id:defer_bulk", false).putBoolean("$id:bulk_released", true).commit())
        if (wasDeferred) changed(id)
        if (pending(id).isNotEmpty()) scheduleImmediate() else syncDeferredWake()
        Bundle().apply {
            putBoolean("supported", true)
            putBoolean("accepted", true)
            putBoolean("bulk_deferred", false)
        }
    }

    /** A projection of saved selections only. Reading it never waits for or calls Aurora. */
    fun handoff(run: String, offset: Int = 0): Bundle {
        val snapshot = prefs.all
        return Bundle().apply {
            putBoolean("supported", true)
            if (snapshot["handoff_run"] != run) return@apply
            putString("run_id", run)
            val id = snapshot["handoff_session"] as? String
            val outcome = snapshot["handoff_outcome"] as? String
            putString("session_id", id)
            putString("outcome", outcome)
            putBoolean("selection_closed", if (id == null) outcome != null else snapshot["$id:selection_closed"] == true)
            putLong("selection_changed_at", snapshot["$id:selection_changed_at"] as? Long ?: 0)
            val handoffPackages = (snapshot["$id:handoff_packages"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            val includeExcluded = id != null && (snapshot["$id:selected"] == true || snapshot["$id:selection_closed"] == true)
            val excludedPackages = if (includeExcluded) {
                (snapshot["$id:excluded"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            } else emptySet()
            val selected = (handoffPackages + excludedPackages).sorted()
            val names = selected.drop(offset).take(64)
            putInt("total", selected.size)
            if (offset + names.size < selected.size) putInt("next_offset", offset + names.size)
            putStringArrayList("packages", ArrayList(names))
            putStringArrayList("titles", ArrayList(names.map { name -> (snapshot["$id:$name:title"] as? String)?.take(80) ?: name }))
            putStringArrayList("icon_urls", ArrayList(names.map { name -> (snapshot["$id:$name:icon_url"] as? String).orEmpty() }))
            putLongArray("install_sizes", LongArray(names.size) { i -> (snapshot["$id:${names[i]}:installSize"] as? Long)?.takeIf { it > 0L } ?: 0L })
            putBoolean("bulk_deferred", id != null && isBulkDeferred(snapshot, id))
            val pending = snapshot["$id:pending"] as? Set<*> ?: emptySet<String>()
            val restoreEligible = id != null && id == snapshot["current_session"] &&
                (snapshot["$id:option:restore_source"] as? String)?.toIntOrNull() in ANDROID_D2D_RESTORE_SOURCES &&
                (appDataRestore != null || snapshot["$id:start_acknowledged"] == true)
            val restorePending = if (restoreEligible) {
                (snapshot["$id:app_data_restore_pending"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
            } else emptySet()
            val statuses = ArrayList<String>(names.size)
            val errors = ArrayList<String>(names.size)
            val excludedFlags = BooleanArray(names.size)
            val recoverableFlags = BooleanArray(names.size)
            for ((index, name) in names.withIndex()) {
                val rawStatus = (snapshot["$id:$name:status"] as? String).orEmpty()
                val rawError = (snapshot["$id:$name:error"] as? String).orEmpty()
                val rawErrorCode = (snapshot["$id:$name:errorCode"] as? String).orEmpty()
                val inSelection = name in handoffPackages
                excludedFlags[index] = !inSelection
                recoverableFlags[index] = !inSelection && snapshot["$id:$name:recoverable"] == true
                val baseStatus = when {
                    name in pending -> "pending"
                    // summary() may have lost its Aurora connection while checking a dispatched app.
                    inSelection && rawStatus == "unavailable" && rawError == "store_unavailable" -> "queued"
                    // An app already on the phone has no Aurora download row: Aurora says "none", and summary() saved it
                    // as installed (11-18 founders-02: Gmail and Chrome kept Setup's copy at "284 of 287" for good).
                    rawStatus == "none" && snapshot["$id:$name:installed"] == true -> "installed"
                    !inSelection && rawStatus.isEmpty() -> "unavailable"
                    else -> rawStatus
                }
                // Hold Setup sign-in launch on Android-to-Android copies until post-install app-data restore settles,
                // covering both pending restores already queued by summary() and newly installed APKs before summary()
                // has run, so BackupManagerService.restorePackages(pkg) cannot force-stop and wipe a newly signed-in app.
                val awaitingSummaryRestore = restoreEligible &&
                    baseStatus !in TERMINAL &&
                    snapshot["$id:$name:app_data_restore_done"] != true &&
                    snapshot["$id:$name:preexisting"] != true &&
                    (snapshot["$id:$name:install_dispatched"] == true || snapshot["$id:$name:observed_not_installed"] == true) &&
                    isInstalledAtExpectedVersion(snapshot, id!!, name)
                val status = if ((baseStatus == "installed" && name in restorePending) || awaitingSummaryRestore) {
                    "restoring_data"
                } else {
                    baseStatus
                }
                val error = if (status in TERMINAL) rawErrorCode.ifEmpty { rawError } else ""
                statuses += status
                errors += error
            }
            putStringArrayList("statuses", statuses)
            putStringArrayList("errors", errors)
            putBooleanArray("excluded", excludedFlags)
            putBooleanArray("recoverable", recoverableFlags)
        }
    }

    private fun isInstalledAtExpectedVersion(snapshot: Map<String, *>, id: String, name: String): Boolean {
        val info = try {
            context.packageManager.getPackageInfo(name, 0)
        } catch (_: Exception) {
            return false
        }
        val listed = snapshot["$id:$name:expectedVersion"] as? Long ?: 0L
        val expected = if (snapshot["$id:$name:owner_account"] == true) {
            snapshot["$id:$name:ownerVersion"] as? Long ?: listed
        } else {
            listed
        }
        return info.longVersionCode >= expected
    }

    fun endHandoff(run: String, outcome: String): Bundle = synchronized(LOCK) {
        if (prefs.getString("handoff_run", null) != run) return Bundle().apply { putBoolean("accepted", false) }
        require(outcome in setOf("success", "cancelled")) { "Invalid transfer outcome" }
        val id = prefs.getString("handoff_session", null)
        var unblockedDeferred = false
        if (id != null) {
            val wasDeferred = prefs.getBoolean("$id:defer_bulk", false)
            if (wasDeferred || !prefs.getBoolean("$id:bulk_released", false)) {
                check(prefs.edit().putBoolean("$id:defer_bulk", false).putBoolean("$id:bulk_released", true).commit())
                unblockedDeferred = wasDeferred
            }
        }
        if (!prefs.contains("handoff_outcome")) {
            if (id != null) {
                if (!transferEnded(outcome, id, recordHandoff = true).getBoolean("accepted")) {
                    if (unblockedDeferred) changed(id)
                    if (pending(id).isNotEmpty()) schedule() else syncDeferredWake()
                    return Bundle().apply { putBoolean("accepted", false) }
                }
            } else {
                check(prefs.edit().putString("handoff_outcome", outcome).commit())
            }
        } else if (id != null) {
            if (unblockedDeferred) changed(id)
            if (pending(id).isNotEmpty()) schedule() else syncDeferredWake()
        }
        handoff(run).apply { putBoolean("accepted", true) }
    }
    /** Active, unclosed handoff session currently bound to [currentSessionId], if any. */
    private fun activeUnclosedHandoffSession(): String? {
        if (!prefs.contains("handoff_run") || prefs.contains("handoff_outcome")) return null
        val id = prefs.getString("handoff_session", null) ?: return null
        if (id != currentSessionId()) return null
        if (prefs.getBoolean("$id:selection_closed", false)) return null
        if (prefs.getString("$id:transfer_outcome", null) != null) return null
        return id
    }
    /**
     * Switch's Pause (op 13) when its copy starts. A hand-off session Transfer Phone just began (beginHandoff) and nothing
     * has been chosen or checked in yet is this copy's session: it is reused rather than replaced, which used to strand it
     * as an empty transfer in the results screen's list (CYC-3525).
     */
    fun startPausedTransfer(): String {
        val (id, created) = synchronized(LOCK) {
            val bound = activeUnclosedHandoffSession()
            val selected = bound != null && prefs.getBoolean("$bound:selected", false)
            if (bound != null && (selected || untouched(bound))) {
                val now = clock()
                if (isBulkDeferred(bound, now)) {
                    check(prefs.edit().putLong("$bound:defer_bulk_at", now).commit())
                }
                bound to (!selected && !prefs.getBoolean("$bound:start_acknowledged", false))
            } else {
                currentSessionId()?.let { if (prefs.getBoolean("$it:pause_requested", false)) resume(it) }
                newSession() to true
            }
        }
        if (created) acknowledgeTransferStart(id)
        return id
    }
    /** No app chosen, left out or checked in [id] yet. */
    private fun untouched(id: String): Boolean = !prefs.getBoolean("$id:selected", false) && selected(id).isEmpty() &&
        prefs.getStringSet("$id:excluded", emptySet()).isNullOrEmpty()
    fun catalogSession(): String {
        val (id, created) = synchronized(LOCK) {
            val current = currentSessionId()
            if (current == null) {
                newSession() to true
            } else if (prefs.getBoolean("$current:selected", false)) {
                if (current == activeUnclosedHandoffSession()) {
                    current to false
                } else {
                    val held = prefs.getBoolean("$current:pause_requested", false)
                    val next = newSession()
                    if (held) { resume(current); pause(next) }
                    next to true
                }
            } else {
                current to false
            }
        }
        if (created) acknowledgeTransferStart(id)
        return id
    }
    private fun session(): String = currentSessionId() ?: newSession()
    fun options(id: String): Bundle = Bundle().apply {
        putString("session_id", id)
        for (key in listOf("documents_type", "restore_source", "disable_pre_archiving")) {
            val stored = prefs.getString("$id:option:$key", null) ?: continue
            if (key == "disable_pre_archiving") putBoolean(key, stored.toBoolean()) else putInt(key, stored.toInt())
        }
    }
    fun requireUpdateHold() { check(prefs.edit().putBoolean("requires_update_hold", true).commit()) }
    fun storeReady(): Boolean = !prefs.getBoolean("requires_update_hold", false) || control(7, Bundle()).getBoolean("automatic_update_hold")
    fun pause(id: String): Bundle {
        val result = control(4, options(id))
        if (result.getBoolean("success")) {
            check(prefs.edit().putBoolean("$id:pause_requested", true).commit())
            if (isBulkDeferred(id) && !MigrationInstallJob.isRunning()) syncDeferredWake()
        }
        return result
    }
    fun resume(id: String): Bundle {
        check(prefs.edit().putBoolean("$id:pause_requested", false).commit())
        if (!MigrationInstallJob.isRunning()) syncDeferredWake()
        return control(5, options(id))
    }
    fun renewPause(id: String) { if (prefs.getBoolean("$id:pause_requested", false)) control(4, options(id)) }
    fun releaseCompletedPause(id: String) { if (prefs.getBoolean("$id:pause_requested", false)) resume(id) }
    fun recordOptions(options: Bundle) = synchronized(LOCK) {
        // Never publish transferStarting here: Switch dispatches op 12 asynchronously after Copy starts,
        // so emitting a start boundary here could land after batchStarted and create a spurious ceiling.
        // Only sessions whose start boundary was acknowledged before Copy (beginHandoff, catalogSession,
        // startPausedTransfer, or beginTransfer) are eligible for post-install app-data restore.
        val id = session(); val edit = prefs.edit()
        for (key in listOf("documents_type", "restore_source", "disable_pre_archiving")) {
            if (options.containsKey(key)) edit.putString("$id:option:$key", options.get(key).toString())
        }
        val source = options.get("restore_source")?.toString()?.toIntOrNull()
        if (source in ANDROID_D2D_RESTORE_SOURCES && !prefs.contains("$id:d2d_seen_elapsed_ms")) {
            edit.putInt("$id:d2d_seen_boot_count", currentBootCount(context))
                .putLong("$id:d2d_seen_elapsed_ms", SystemClock.elapsedRealtime())
        }
        check(edit.commit())
    }
    fun recordCatalog(documents: List<Bundle>) = synchronized(LOCK) {
        val id = session(); val edit = prefs.edit()
        val excluded = documents.filter { it.getInt("availability") != 1 && it.getString("error") != "provided_by_os" }
        val names = documents.map { it.getString("package_name")!! }.toSet()
        val previousExcluded = prefs.getStringSet("$id:excluded", emptySet()).orEmpty()
        val nextExcluded = (previousExcluded - names) + excluded.map { it.getString("package_name")!! }
        edit.putStringSet("$id:excluded", nextExcluded)
        if (prefs.getString("handoff_session", null) == id && prefs.getBoolean("$id:selected", false) &&
            (nextExcluded - previousExcluded).isNotEmpty()) {
            edit.putLong("$id:selection_changed_at", System.currentTimeMillis())
        }
        for (doc in documents) {
            metadata(edit, id, doc)
            val pkg = doc.getString("package_name")!!
            edit.putBoolean("$id:$pkg:recoverable", doc.getInt("availability") != 1 && doc.getBoolean("recoverable"))
        }
        check(edit.commit())
    }
    private fun metadata(edit: android.content.SharedPreferences.Editor, id: String, doc: Bundle) {
        val name = requireNotNull(doc.getString("package_name"))
        val existingTitle = prefs.getString("$id:$name:title", null)
        for (key in listOf("title", "icon_url", "developer_name", "error", "status")) {
            val value = doc.getString(key) ?: continue
            // Never overwrite a real Play display title with a package-name fallback (e.g. an unchecked doc from Switch).
            if (key == "title" && value == name && !existingTitle.isNullOrBlank() && existingTitle != name) continue
            edit.putString("$id:$name:$key", value)
        }
        if (doc.containsKey("version_code")) edit.putLong("$id:$name:expectedVersion", doc.getLong("version_code"))
        if (doc.containsKey("install_size")) {
            val size = doc.getLong("install_size")
            if (size > 0L) edit.putLong("$id:$name:installSize", size)
        }
    }
    fun select(documents: List<Bundle>) {
        // Aurora is asked outside LOCK: with Switch's update hold on, a slow answer must not hold the queue's bookkeeping.
        check(storeReady()) { "Update App Store before transferring apps" }
        record(documents)
    }
    private fun record(documents: List<Bundle>) = synchronized(LOCK) {
        val id = session()
        check(!prefs.getBoolean("$id:selection_closed", false)) { "App selection is already closed" }
        val names = documents.map { requireNotNull(it.getString("package_name")) }.toSet()
        val fresh = names - selected(id)
        names.forEach { bump(id, it) }
        val edit = prefs.edit().putBoolean("$id:selected", true).putStringSet("$id:packages", selected(id) + names)
            .putStringSet("$id:pending", pending(id) + fresh)
        if (prefs.getString("handoff_session", null) == id) {
            edit.putStringSet("$id:handoff_packages", selected(id) + names)
            if (fresh.isNotEmpty()) edit.putLong("$id:selection_changed_at", System.currentTimeMillis())
        }
        val prioritized = priorityOrder(id).toSet()
        if (names.any { it in prioritized }) {
            edit.putBoolean("$id:priority_dirty", true)
            roundSyncedPriority.remove(id)
        }
        for (doc in documents) metadata(edit, id, doc)
        check(edit.commit()); changed(id); schedule()
    }
    fun finishSelection(id: String) = synchronized(LOCK) {
        require(id in sessions())
        if (!prefs.getBoolean("$id:selection_closed", false)) {
            check(prefs.edit().putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true)
                .putBoolean("$id:defer_bulk", false).putBoolean("$id:bulk_released", true).commit())
            changed(id); schedule()
        }
    }
    /** A trusted companion's wizard return closes input; it does not certify any app installation. */
    fun transferEnded(outcome: String, expectedSessionId: String? = null): Bundle =
        transferEnded(outcome, expectedSessionId, recordHandoff = false)

    private fun transferEnded(outcome: String, expectedSessionId: String?, recordHandoff: Boolean): Bundle = synchronized(LOCK) {
        require(outcome in setOf("success", "cancelled")) { "Invalid transfer outcome" }
        val id = currentSessionId()
        if (id == null) return Bundle().apply { putBoolean("accepted", false); putString("error", "no_active_session") }
        if (expectedSessionId != null && expectedSessionId != id) return Bundle().apply {
            putBoolean("accepted", false); putString("error", "stale_session"); putString("session_id", id)
        }
        val alreadyClosed = prefs.getBoolean("$id:selection_closed", false)
        val edit = prefs.edit().putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true)
            .putBoolean("$id:defer_bulk", false).putBoolean("$id:bulk_released", true)
        if (!prefs.contains("$id:transfer_outcome")) edit.putString("$id:transfer_outcome", outcome)
        if (recordHandoff) edit.putString("handoff_outcome", outcome)
        check(edit.commit())
        changed(id); schedule()
        Bundle().apply {
            putBoolean("accepted", true); putString("session_id", id); putBoolean("selection_closed", true)
            putBoolean("already_closed", alreadyClosed)
            putString("transfer_outcome", prefs.getString("$id:transfer_outcome", null))
        }
    }
    fun selected(id: String = currentSessionId().orEmpty()): Set<String> = prefs.getStringSet("$id:packages", emptySet()).orEmpty().toSet()
    fun pending(id: String = currentSessionId().orEmpty()): Set<String> = prefs.getStringSet("$id:pending", emptySet()).orEmpty().toSet()
    /** Membership in the stored set without copying its ~300 entries; the set SharedPreferences returns is never changed. */
    private fun isPending(id: String, name: String) = prefs.getStringSet("$id:pending", null)?.contains(name) == true
    private fun isSelected(id: String, name: String) = prefs.getStringSet("$id:packages", null)?.contains(name) == true
    fun isIosTransfer(id: String = currentSessionId().orEmpty()): Boolean =
        prefs.getString("$id:option:restore_source", null)?.toIntOrNull() in IOS_RESTORE_SOURCES

    /** Only Android-to-Android local D2D transfers (cable=1, Wi-Fi=2, ejj.java:138-159) stage data in MigrationBackupTransport. */
    fun isAndroidD2dTransfer(id: String = currentSessionId().orEmpty()): Boolean =
        prefs.getString("$id:option:restore_source", null)?.toIntOrNull() in ANDROID_D2D_RESTORE_SOURCES

    private fun isAppDataRestoreEligible(id: String): Boolean =
        isAndroidD2dTransfer(id) && (appDataRestore != null || prefs.getBoolean("$id:start_acknowledged", false))

    fun pendingAppDataRestores(id: String = currentSessionId().orEmpty()): Set<String> =
        prefs.getStringSet("$id:app_data_restore_pending", emptySet()).orEmpty().toSet()

    internal fun recordAppDataRestoreResult(
        id: String,
        name: String,
        result: MigrationAppDataRestore.Result,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        var cleared = false
        synchronized(LOCK) {
            val current = pendingAppDataRestores(id)
            val staleSession = id != currentSessionId() || !isAppDataRestoreEligible(id)
            if (result.status == "busy" && !staleSession) {
                // Switch still owns the backup transport lease during the copy: keep pending without burning
                // error attempts, bounded by elapsed wall-clock time so a stale task never polls forever.
                val firstBusyKey = "$id:$name:app_data_restore_first_busy_at"
                val firstBusyAt = prefs.getLong(firstBusyKey, -1L)
                if (firstBusyAt < 0L) {
                    check(prefs.edit().putLong(firstBusyKey, nowMs).commit())
                    return
                }
                if (nowMs - firstBusyAt < MAX_APP_DATA_RESTORE_BUSY_MS) {
                    return
                }
                check(
                    prefs.edit()
                        .putStringSet("$id:app_data_restore_pending", current - name)
                        .putBoolean("$id:$name:app_data_restore_done", true)
                        .commit(),
                )
                cleared = name in current
            } else {
                val terminal = result.status in APP_DATA_RESTORE_TERMINAL
                val attempts = prefs.getInt("$id:$name:app_data_restore_attempts", 0) + 1
                val edit = prefs.edit().putInt("$id:$name:app_data_restore_attempts", attempts)
                if (terminal || staleSession || attempts >= MAX_APP_DATA_RESTORE_ATTEMPTS) {
                    edit.putStringSet("$id:app_data_restore_pending", current - name)
                        .putBoolean("$id:$name:app_data_restore_done", true)
                    cleared = name in current
                }
                check(edit.commit())
            }
        }
        if (cleared) changed(id)
    }

    /** Retries pending Android-to-Android post-install app-data restores for the current session only. */
    fun processPendingAppDataRestores(
        cancelled: () -> Boolean = { false },
        clock: () -> Long = { System.currentTimeMillis() },
    ): Boolean {
        val currentId = currentSessionId() ?: return true
        // Drop stale pending restores from older transfers so they never read a newer transfer's staging directory.
        synchronized(LOCK) {
            for (oldId in sessions()) {
                if (oldId != currentId && pendingAppDataRestores(oldId).isNotEmpty()) {
                    check(prefs.edit().remove("$oldId:app_data_restore_pending").commit())
                }
            }
        }
        if (!isAppDataRestoreEligible(currentId)) {
            synchronized(LOCK) {
                if (pendingAppDataRestores(currentId).isNotEmpty()) {
                    check(prefs.edit().remove("$currentId:app_data_restore_pending").commit())
                }
            }
            return true
        }
        val names = synchronized(LOCK) {
            val rank = priorityOrder(currentId).withIndex().associate { (i, pkg) -> pkg to i }
            pendingAppDataRestores(currentId).sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it }))
        }
        val d2dBoot = prefs.getInt("$currentId:d2d_seen_boot_count", -1)
        val d2dElapsed = prefs.getLong("$currentId:d2d_seen_elapsed_ms", -1L)
        var settled = true
        for (name in names) {
            if (cancelled()) return false
            val restoreKey = key(currentId, name)
            val claimed = synchronized(LOCK) {
                if (currentSessionId() != currentId || !isAppDataRestoreEligible(currentId)) return true
                if (name !in pendingAppDataRestores(currentId) ||
                    prefs.getBoolean("$currentId:$name:app_data_restore_done", false)
                ) {
                    return@synchronized false
                }
                if (!RESTORE_IN_FLIGHT.add(restoreKey)) {
                    settled = false
                    return@synchronized false
                }
                true
            }
            if (!claimed) continue
            val result = try {
                val res = if (currentSessionId() != currentId || !isAppDataRestoreEligible(currentId)) {
                    MigrationAppDataRestore.Result(ok = false, status = "stale_session")
                } else {
                    appDataRestore?.invoke(name, currentId)
                        ?: MigrationAppDataRestore.restorePackage(context, name, currentId, d2dBoot, d2dElapsed)
                }
                // Commit settlement before releasing RESTORE_IN_FLIGHT so no concurrent caller can start a duplicate restore.
                recordAppDataRestoreResult(currentId, name, res, nowMs = clock())
                res
            } finally {
                RESTORE_IN_FLIGHT.remove(restoreKey)
            }
            if (result.status !in APP_DATA_RESTORE_TERMINAL && name in pendingAppDataRestores(currentId)) {
                settled = false
            }
        }
        return settled
    }

    fun save(name: String, result: Bundle, dispatched: Boolean = false, id: String = session()) {
        var triggerRestore = false
        synchronized(LOCK) {
            val before = row(id, name)
            val edit = prefs.edit()
            for (key in listOf("status", "error")) edit.putString("$id:$name:$key", result.getString(key) ?: if (key == "error") result.getString("errorCode") else "unknown")
            // Backfill a real display title learned later from Aurora when catalog metadata only has the package name.
            val existingTitle = prefs.getString("$id:$name:title", null)?.trim()
            if (existingTitle.isNullOrEmpty() || existingTitle == name) {
                result.getString("title")?.trim()?.takeIf { it.isNotEmpty() && it != name }?.let {
                    edit.putString("$id:$name:title", it)
                }
            }
            // The store's own failure code beside the error: a refusal of the anonymous session stays known ([refusedBefore]).
            if (result.getString("errorCode") != null) edit.putString("$id:$name:errorCode", result.getString("errorCode")) else edit.remove("$id:$name:errorCode")
            edit.putBoolean("$id:$name:installed", result.getBoolean("installed"))
            edit.putBoolean("$id:$name:terminal", result.getBoolean("terminal"))
            edit.putLong("$id:$name:installedVersion", result.getLong("installed_version_code"))
            val nowInstalled = result.getBoolean("installed") && result.getLong("installed_version_code") >= expectedVersion(id, name)
            if (!nowInstalled) {
                val transientReadFailure = result.getString("error") == "store_unavailable" ||
                    result.getString("status").isNullOrEmpty() || result.getString("status") == "unknown"
                if (!result.getBoolean("installed") && !result.getBoolean("package_present") && !transientReadFailure) {
                    edit.putBoolean("$id:$name:observed_not_installed", true)
                }
            } else {
                edit.putBoolean("$id:$name:ever_installed", true)
                val installedByThisTransfer = prefs.getBoolean("$id:$name:install_dispatched", false) ||
                    prefs.getBoolean("$id:$name:observed_not_installed", false)
                val preexisting = prefs.getBoolean("$id:$name:preexisting", false)
                if (installedByThisTransfer && !preexisting && isAppDataRestoreEligible(id) &&
                    !prefs.getBoolean("$id:$name:app_data_restore_done", false)
                ) {
                    edit.putStringSet("$id:app_data_restore_pending", pendingAppDataRestores(id) + name)
                    if (id == currentSessionId() && RESTORE_IN_FLIGHT.add(key(id, name))) {
                        triggerRestore = true
                    }
                } else if (!installedByThisTransfer) {
                    edit.putBoolean("$id:$name:preexisting", true)
                }
            }
            if (dispatched) edit.putStringSet("$id:pending", pending(id) - name).putBoolean("$id:$name:uncertain", false)
                .putBoolean("$id:$name:acknowledged", true).putBoolean("$id:$name:owner_retry", false)
            check(edit.commit())
            // Only a change makes a status asked before it stale: a poll saving the same receipt again must not make
            // another summary drop the newer answer it just read (round 3: a failure lost at a transfer boundary).
            if (row(id, name) != before) bump(id, name)
        }
        if (triggerRestore) {
            val restoreKey = key(id, name)
            val override = appDataRestore
            if (override != null) {
                try {
                    val res = if (currentSessionId() != id || !isAppDataRestoreEligible(id)) {
                        MigrationAppDataRestore.Result(ok = false, status = "stale_session")
                    } else {
                        override.invoke(name, id)
                    }
                    recordAppDataRestoreResult(id, name, res)
                } finally {
                    RESTORE_IN_FLIGHT.remove(restoreKey)
                }
            } else {
                val d2dBoot = prefs.getInt("$id:d2d_seen_boot_count", -1)
                val d2dElapsed = prefs.getLong("$id:d2d_seen_elapsed_ms", -1L)
                var dispatchedAsync = false
                try {
                    MigrationAppDataRestore.triggerAsync(
                        context = context,
                        packageName = name,
                        sessionId = id,
                        d2dSeenBootCount = d2dBoot,
                        d2dSeenElapsedMs = d2dElapsed,
                        shouldRun = { currentSessionId() == id && isAppDataRestoreEligible(id) },
                    ) { res ->
                        try {
                            recordAppDataRestoreResult(id, name, res)
                        } finally {
                            RESTORE_IN_FLIGHT.remove(restoreKey)
                        }
                        // Never call schedule() while MigrationInstallJob is already running (JobScheduler stops and
                        // restarts an active job on re-schedule, burning expedited quota during A2A copies).
                        if (!MigrationInstallJob.isRunning() &&
                            res.status !in APP_DATA_RESTORE_TERMINAL &&
                            name in pendingAppDataRestores(id)
                        ) {
                            runCatching { schedule() }
                        }
                    }
                    dispatchedAsync = true
                } finally {
                    if (!dispatchedAsync) RESTORE_IN_FLIGHT.remove(restoreKey)
                }
            }
        }
    }
    /** What [save] writes for one app, to tell a change from the same receipt saved again. */
    private fun row(id: String, name: String): List<Any?> = listOf(
        prefs.getString("$id:$name:status", null), prefs.getString("$id:$name:error", null),
        prefs.getBoolean("$id:$name:installed", false), prefs.getBoolean("$id:$name:terminal", false),
        prefs.getLong("$id:$name:installedVersion", 0), prefs.getBoolean("$id:$name:ever_installed", false),
        isPending(id, name), prefs.getBoolean("$id:$name:uncertain", false),
        prefs.getBoolean("$id:$name:acknowledged", false), prefs.getBoolean("$id:$name:owner_retry", false))
    fun retryDocuments(id: String = currentSessionId().orEmpty()): List<Bundle> = (selected(id).filter { name ->
        prefs.getString("$id:$name:status", null) in setOf("failed", "cancelled", "unavailable", "owner_action_required") &&
            !prefs.getBoolean("$id:$name:ever_installed", false)
    } + recoverableExclusions(id)).map { name -> document(id, name) }
    /** Apps Switch left out because the store could not check them in time; the owner may still add them here. */
    private fun recoverableExclusions(id: String) = prefs.getStringSet("$id:excluded", emptySet()).orEmpty()
        .filter { !isSelected(id, it) && prefs.getBoolean("$id:$it:recoverable", false) }.sorted()
    /** Whether Aurora can install with the phone's Google account, the fallback when the anonymous session fails. */
    fun ownerAccountSupported(): Boolean = control(7, Bundle()).getBoolean("owner_account_fallback")
    /** Aurora's answer to [allAccountsSupported] in this round; null until asked, or when Aurora could not answer. */
    @Volatile private var allAccounts: Boolean? = null
    /**
     * Whether Aurora tries every account itself in one install request (all_accounts, CYC-2499): the app mirror, the
     * anonymous session, then each of the phone's Google accounts. FakeStore then sends no second request for the
     * Google account; an older Aurora keeps that two-step fallback. Asked once a round ([dispatchRound]).
     */
    fun allAccountsSupported(): Boolean = allAccounts ?: control(7, Bundle()).let { capabilities ->
        capabilities.getBoolean("all_accounts_install").also {
            if (capabilities.getString("error") != "store_unavailable") allAccounts = it
        }
    }
    /** The last install request for the app had Aurora try every account ([allAccountsSupported]). */
    private fun allAccountsAsked(id: String, name: String) = prefs.getBoolean("$id:$name:all_accounts", false)
    /**
     * Aurora went on to one of the phone's Google accounts for an all_accounts request (its answer's google_account). As
     * for FakeStore's own Google account request, the version Play offers that account is the floor from now on.
     */
    private fun adoptGoogleAccount(id: String, name: String, result: Bundle) {
        if (!allAccountsAsked(id, name) || !result.getBoolean("google_account") || result.getLong("version_code") <= 0) return
        check(prefs.edit().putBoolean("$id:$name:owner_account", true)
            .putLong("$id:$name:ownerVersion", result.getLong("version_code")).commit())
    }
    /**
     * Play refused the anonymous session: a session failure, or a download refusal (app_not_purchased, rate_limited) in
     * the install request's own reply. 11-17 founders-02: Aurora answered the request with the refusal (Play refused the
     * purchase within 0.2 s), which only a later status check matched, so Stu, SamTrans and C&A failed for good.
     */
    private fun anonymousRefusal(result: Bundle) = result.getString("error") in ANONYMOUS_FAILURES ||
        result.getString("errorCode") in DOWNLOAD_REFUSALS || result.getString("error") in DOWNLOAD_REFUSALS
    /** A failed app the Google account has not tried yet: asked again by [summary], which queues a refusal for the account. */
    private fun refusedBefore(id: String, name: String) = !allAccountsAsked(id, name) && prefs.getString("$id:$name:status", null) == "failed" &&
        (prefs.getString("$id:$name:errorCode", null) in DOWNLOAD_REFUSALS || prefs.getString("$id:$name:error", null) in DOWNLOAD_REFUSALS) &&
        prefs.getString("$id:transfer_outcome", null) == null &&
        !prefs.getBoolean("$id:$name:owner_account", false) && !prefs.getBoolean("$id:$name:ever_installed", false)
    /** An owner Retry starts again with the anonymous session; the Google account fallback applies again after it. */
    fun retry(id: String, packageName: String? = null) {
        fun names() = retryDocuments(id).map { it.getString("package_name")!! }.filter { packageName == null || it == packageName }
        // Aurora refreshes its session before the apps are queued again, without holding the lock (Switch's chunks).
        val options = synchronized(LOCK) { require(id in sessions()); if (names().isEmpty()) return; options(id) }
        control(6, options.apply { putBoolean("force_refresh", true) })
        synchronized(LOCK) { requeue(id, names()) }
        changed(id); schedule()
    }
    private fun requeue(id: String, names: List<String>) {
        if (names.isEmpty()) return
        // A left-out app joins the selection now; the install looks it up again, as for any selected app.
        val added = recoverableExclusions(id).filter { it in names }
        val edit = prefs.edit().putStringSet("$id:pending", pending(id) + names)
        if (added.isNotEmpty()) {
            edit.putStringSet("$id:packages", selected(id) + added)
                .putStringSet("$id:excluded", prefs.getStringSet("$id:excluded", emptySet()).orEmpty() - added.toSet())
            if (prefs.getString("handoff_session", null) == id) {
                val priorHandoff = prefs.getStringSet("$id:handoff_packages", emptySet()).orEmpty()
                edit.putStringSet("$id:handoff_packages", priorHandoff + added)
            }
        }
        for (name in names) bump(id, name)
        for (name in names) edit.putInt("$id:$name:retry_gen", prefs.getInt("$id:$name:retry_gen", 0) + 1)
        // A Retry starts clean (Greptile on #102): the previous attempt's all_accounts mark goes with its account choice, so
        // a status still showing that failed attempt (google_account, its version_code) is never adopted for this one.
        for (name in names) edit.putBoolean("$id:$name:terminal", false).putBoolean("$id:$name:uncertain", false)
            .putBoolean("$id:$name:owner_retry", true).putBoolean("$id:$name:owner_account", false)
            .putBoolean("$id:$name:all_accounts", false).remove("$id:$name:ownerVersion")
            .putInt("$id:$name:throttle_count", 0)
        check(edit.commit())
    }
    fun needsEnqueue(name: String, state: Bundle, id: String = session()): Boolean {
        if (prefs.getBoolean("$id:$name:ever_installed", false)) return false
        val expected = expectedVersion(id, name)
        if (state.getBoolean("installed") && state.getLong("installed_version_code") >= expected) return false
        val status = state.getString("status")
        if (status == "installed" && state.getBoolean("installed") && state.getLong("installed_version_code") < expected) return true
        return status in setOf("failed", "cancelled", "unavailable") ||
            (status == "none" && (!state.getBoolean("installed") ||
                state.getLong("installed_version_code") < expectedVersion(id, name)))
    }
    private fun document(id: String, name: String) = Bundle().apply {
        putString("package_name", name)
        for (key in listOf("title", "icon_url", "developer_name", "error", "status")) putString(key, prefs.getString("$id:$name:$key", null))
        putLong("version_code", prefs.getLong("$id:$name:expectedVersion", 0))
        putLong("installed_version_code", prefs.getLong("$id:$name:installedVersion", 0))
        putBoolean("installed", prefs.getBoolean("$id:$name:installed", false))
        putBoolean("terminal", prefs.getBoolean("$id:$name:terminal", false))
    }
    /**
     * The lowest version that counts as installed: the one Switch listed, or, once the app fell back to the phone's
     * Google account, the version Play offered that account for the download. That one can be older (10-30 on
     * customer-98: Blindmate, Tinder, Tesla Robotaxi and tricount failed with selected_version_unavailable); as on a
     * Pixel, it is the app. Until the account's offer is known, an older copy already on the phone does not count.
     */
    private fun expectedVersion(id: String, name: String): Long = prefs.getLong("$id:$name:expectedVersion", 0).let { listed ->
        if (prefs.getBoolean("$id:$name:owner_account", false)) prefs.getLong("$id:$name:ownerVersion", listed) else listed
    }
    private fun satisfies(name: String, result: Bundle, id: String): Boolean = result.getBoolean("installed") &&
        result.getLong("installed_version_code") >= expectedVersion(id, name)
    private fun removed(result: Bundle): Boolean = result.getString("status") == "installed" &&
        !result.getBoolean("installed") && !result.getBoolean("package_present")
    private fun olderAttempt(name: String, result: Bundle, id: String): Boolean {
        val expected = expectedVersion(id, name)
        return !satisfies(name, result, id) && expected > result.getLong("version_code") &&
            (result.getString("status") in ACTIVE || (result.getString("status") == "installed" && result.getBoolean("package_present")))
    }
    private fun ownerReview(result: Bundle, reason: String): Bundle = Bundle(result).apply {
        putString("status", "owner_action_required"); putString("error", reason)
        putBoolean("installed", false); putBoolean("terminal", true)
    }
    private fun removedResult(result: Bundle) = Bundle(result).apply {
        putString("status", "removed"); putString("error", "removed_by_owner")
        putBoolean("installed", false); putBoolean("terminal", true)
    }
    /** A deferred bulk app that has never been dispatched/acknowledged and is not prioritized needs no Aurora poll. */
    private fun isDeferredUnpolled(
        id: String,
        name: String,
        bulkDeferred: Boolean,
        pendingSet: Set<String>,
        prioritySet: Set<String>,
    ): Boolean = bulkDeferred &&
        name in pendingSet &&
        name !in prioritySet &&
        !prefs.getBoolean("$id:$name:install_dispatched", false) &&
        !prefs.getBoolean("$id:$name:acknowledged", false) &&
        !prefs.getBoolean("$id:$name:ever_installed", false)
    /**
     * [summary]'s receipt as last saved, read from one preferences snapshot: no Aurora call, no wait for [LOCK]. The results
     * screen draws this at once and on every change while [summary] checks the open apps with Aurora in the background.
     * An app waiting to be handed to Aurora (an owner Retry included) reads as pending, not as its previous attempt.
     */
    fun cachedSummary(id: String): Bundle {
        val snapshot = prefs.all
        fun set(key: String) = (snapshot[key] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
        fun string(key: String) = snapshot[key] as? String
        fun flag(key: String) = snapshot[key] == true
        val selected = set("$id:packages")
        val pendingSet = set("$id:pending")
        val bulkDeferred = isBulkDeferred(snapshot, id)
        val prioritySet = if (bulkDeferred) {
            string("$id:priority_order")?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: set("$id:priority")
        } else emptySet()
        fun row(name: String, excluded: Boolean) = Bundle().apply {
            putString("package_name", name)
            for (key in listOf("title", "icon_url", "developer_name", "error", "errorCode")) putString(key, string("$id:$name:$key"))
            putLong("version_code", snapshot["$id:$name:expectedVersion"] as? Long ?: 0L)
            putLong("installed_version_code", snapshot["$id:$name:installedVersion"] as? Long ?: 0L)
            putLong("install_size", snapshot["$id:$name:installSize"] as? Long ?: 0L)
            val installed = flag("$id:$name:installed")
            val pending = name in pendingSet
            val rawStatus = string("$id:$name:status")
            val status = when {
                pending -> "pending"
                rawStatus == "none" && installed -> "installed"
                excluded && rawStatus.isNullOrEmpty() -> "unavailable"
                else -> rawStatus
            }
            putString("status", status)
            if (pending) { remove("error"); remove("errorCode") }
            putBoolean("installed", installed && !pending)
            putBoolean("terminal", excluded || (!pending && (installed || flag("$id:$name:terminal"))))
            putBoolean("excluded", excluded)
            putBoolean("recoverable", excluded && flag("$id:$name:recoverable"))
        }
        val results = selected.sorted().map { row(it, excluded = false) } +
            set("$id:excluded").filter { it !in selected }.sorted().map { row(it, excluded = true) }
        val activePending = if (bulkDeferred) pendingSet.any { it in prioritySet } else pendingSet.isNotEmpty()
        val hasActiveWork = activePending || results.any { row ->
            val pkg = row.getString("package_name").orEmpty()
            !row.getBoolean("terminal") && !(bulkDeferred && pkg in pendingSet && pkg !in prioritySet)
        }
        return Bundle().apply {
            putString("session_id", id); putParcelableArray("cyclon_app_results", results.toTypedArray())
            putString("transfer_outcome", string("$id:transfer_outcome"))
            putBoolean("all_installed", results.all { it.getBoolean("installed") })
            putBoolean("selection_closed", flag("$id:selection_closed"))
            putBoolean("installs_settled", pendingSet.isEmpty() && results.all { it.getBoolean("terminal") })
            putBoolean("bulk_deferred", bulkDeferred)
            putBoolean("has_active_work", hasActiveWork)
            putBoolean("complete", getBoolean("selection_closed") && getBoolean("installs_settled"))
            putBundle("setup_options", options(id))
            putString("cyclon_hold_state", if (getBoolean("complete")) "complete" else "installing")
        }
    }
    /**
     * Every app's state, asked of Aurora before taking [LOCK] (one call per open app): Switch's next chunk and its status
     * calls never wait for these round trips, only for the bookkeeping after them.
     */
    fun summary(id: String = currentSessionId().orEmpty(), reschedule: Boolean = true, freeze: Boolean = false): Bundle {
        var only: Set<String>? = null
        var attempt = 0
        while (true) {
            val revs = synchronized(LOCK) {
                val pending = pending(id)
                val bulkDeferred = isBulkDeferred(id)
                val prioritySet = if (bulkDeferred) priorityOrder(id).toSet() else emptySet()
                selected(id).filter { name ->
                    (only == null || name in only!!) &&
                        (!(prefs.getBoolean("$id:$name:terminal", false) && name !in pending) || refusedBefore(id, name)) &&
                        !isDeferredUnpolled(id, name, bulkDeferred, pending, prioritySet)
                }.associateWith { revision(id, it) }
            }
            val asked = statuses(revs.keys)
            // An all_accounts request's refusal is Aurora's last account's: nothing is left to queue for the Google account.
            val refused = asked.any { (name, it) -> it.getString("status") == "failed" && it.getString("errorCode") in DOWNLOAD_REFUSALS &&
                !allAccountsAsked(id, name) }
            // Null when Aurora could not be asked: the refused download then waits for a later summary, not a failure.
            val fallback = if (refused) control(7, Bundle()).takeUnless { it.getString("error") == "store_unavailable" }?.getBoolean("owner_account_fallback") else null
            val stale = mutableSetOf<String>()
            val result = synchronized(LOCK) { summarize(id, asked, revs, fallback, reschedule, stale) }
            // Before another session may touch the same Aurora row ([freeze]), the apps whose answer went stale meanwhile are
            // asked again: their receipt would otherwise keep a status the next session's install replaces (round 3).
            if (!freeze || stale.isEmpty() || ++attempt >= FREEZE_ATTEMPTS) return result
            only = stale
        }
    }
    /** [stale] collects the open apps whose answer was not used because they changed after Aurora was asked. */
    private fun summarize(id: String, asked: Map<String, Bundle>, revs: Map<String, Long>, ownerFallback: Boolean?,
                          reschedule: Boolean, stale: MutableSet<String>): Bundle {
        val bulkDeferred = isBulkDeferred(id)
        val prioritySet = if (bulkDeferred) priorityOrder(id).toSet() else emptySet()
        val pendingBefore = pending(id)
        val results = selected(id).sorted().map { name ->
            val saved = document(id, name)
            if (saved.getBoolean("terminal") && !isPending(id, name) && !refusedBefore(id, name)) return@map saved
            if (isDeferredUnpolled(id, name, bulkDeferred, pendingBefore, prioritySet)) {
                return@map saved.apply { putBoolean("terminal", false) }
            }
            // Aurora's answer is stale when the app changed since it was asked, or an install request for it is still
            // waiting on Aurora: keep what was saved (open while in flight) and check it next time. An app that became
            // open after the others were asked is checked next time too, so Aurora is never called under the lock.
            val current = asked[name]
            if (current == null || key(id, name) in IN_FLIGHT || revision(id, name) != revs[name]) {
                if (key(id, name) !in IN_FLIGHT) stale += name
                return@map saved.apply { if (isPending(id, name) || key(id, name) in IN_FLIGHT) putBoolean("terminal", false) }
            }
            var result = saved.apply {
                if (current.getString("status") != "none") { remove("error"); remove("errorCode") }
                if (current.getString("title") == name) current.remove("title")
                if (current.getString("icon_url").isNullOrEmpty()) current.remove("icon_url")
                putAll(current)
            }
            adoptGoogleAccount(id, name, result)
            val acknowledged = prefs.getBoolean("$id:$name:acknowledged", !isPending(id, name) && prefs.contains("$id:$name:status"))
            val ownerRetry = prefs.getBoolean("$id:$name:owner_retry", false)
            val status = result.getString("status")
            if (status == "failed" && result.getString("errorCode") in DOWNLOAD_REFUSALS && !isPending(id, name) &&
                !allAccountsAsked(id, name) &&
                !prefs.getBoolean("$id:$name:owner_account", false) && !prefs.getBoolean("$id:$name:ever_installed", false) &&
                ownerFallback != false) {
                if (ownerFallback == null) return@map result.apply { putBoolean("installed", false); putBoolean("terminal", false) }
                // Play refused the anonymous session the download itself (run 6: "not purchased / unavailable in your
                // country"). As on a Pixel, Play may install with the phone's Google account: queue it once more for that.
                check(prefs.edit().putStringSet("$id:pending", pending(id) + name).putBoolean("$id:$name:owner_account", true).remove("$id:$name:ownerVersion")
                    .putBoolean("$id:$name:uncertain", false).putBoolean("$id:$name:terminal", false)
                    .putInt("$id:$name:throttle_count", 0).commit())
                result.putString("status", "queued"); result.remove("error"); result.remove("errorCode")
                result.putBoolean("installed", false); result.putBoolean("terminal", false)
                save(name, result, id = id)
                if (reschedule) schedule()
                return@map result
            }
            if (removed(result) || (prefs.getBoolean("$id:$name:ever_installed", false) &&
                    !satisfies(name, result, id) && result.getString("error") != "store_unavailable")) {
                result = removedResult(result)
                check(prefs.edit().putBoolean("$id:$name:ever_installed", true).commit())
                save(name, result, dispatched = true, id = id)
            } else if (!satisfies(name, result, id) && status == "awaiting_install" && !ownerRetry) {
                result = ownerReview(result, "installation_confirmation_required")
                save(name, result, dispatched = true, id = id)
            } else if (!satisfies(name, result, id) && status == "none" && acknowledged && !ownerRetry) {
                // An acknowledged row can disappear after restart/owner cleanup. Its absence is not install consent.
                val previous = prefs.getString("$id:$name:status", null)
                result = if (previous in TERMINAL && prefs.getString("$id:$name:error", null) != null) {
                    result.apply { putString("status", previous); putString("error", prefs.getString("$id:$name:error", null)) }
                } else ownerReview(result, "installation_requires_review")
                save(name, result, dispatched = true, id = id)
            } else if (olderAttempt(name, result, id) && !ownerRetry) {
                // Also repairs receipts adopted by the previous version, which dropped the newer pending target.
                check(prefs.edit().putStringSet("$id:pending", pending(id) + name)
                    .putBoolean("$id:$name:acknowledged", true).commit())
            } else if (status == "none" && prefs.getString("$id:$name:error", null) != null && !ownerRetry) {
                result.putString("status", prefs.getString("$id:$name:status", "unavailable"))
                result.putString("error", prefs.getString("$id:$name:error", null))
            } else if (status == "installed" && !satisfies(name, result, id) && !isPending(id, name)) {
                result = ownerReview(result, "installation_verification_required")
            }
            if (!satisfies(name, result, id)) result.putBoolean("installed", false)
            val terminal = result.getBoolean("installed") || (!isPending(id, name) &&
                result.getString("status") in TERMINAL && result.getString("error") != "store_unavailable")
            result.putBoolean("terminal", terminal)
            save(name, result, id = id)
            result
        } + prefs.getStringSet("$id:excluded", emptySet()).orEmpty().filter { !isSelected(id, it) }.sorted().map { name ->
            document(id, name).apply { putBoolean("terminal", true); putBoolean("excluded", true) }
        }
        val pendingSet = pending(id)
        val activePending = if (bulkDeferred) pendingSet.any { it in prioritySet } else pendingSet.isNotEmpty()
        val hasActiveWork = activePending || results.any { row ->
            val pkg = row.getString("package_name").orEmpty()
            !row.getBoolean("terminal") && !(bulkDeferred && pkg in pendingSet && pkg !in prioritySet)
        }
        return Bundle().apply {
            putString("session_id", id); putParcelableArray("cyclon_app_results", results.toTypedArray())
            putString("transfer_outcome", prefs.getString("$id:transfer_outcome", null))
            putBoolean("all_installed", results.all { it.getBoolean("installed") })
            putBoolean("selection_closed", prefs.getBoolean("$id:selection_closed", false))
            putBoolean("installs_settled", pendingSet.isEmpty() && results.all { it.getBoolean("terminal") })
            putBoolean("bulk_deferred", bulkDeferred)
            putBoolean("has_active_work", hasActiveWork)
            putBoolean("complete", getBoolean("selection_closed") && getBoolean("installs_settled"))
            putBundle("setup_options", options(id))
            putString("cyclon_hold_state", if (getBoolean("complete")) "complete" else "installing")
        }
    }
    /** Cached Aurora capabilities for this dispatch round so an unavailable store is not re-bound per app. */
    private fun storeCapabilities(): Bundle = roundCapabilities ?: control(7, Bundle()).also { caps ->
        roundCapabilities = caps
        if (caps.getString("error") != "store_unavailable") {
            synchronized(LOCK) {
                prefs.edit().putBoolean("store_prioritize_supported", caps.getBoolean("prioritize_downloads")).apply()
            }
        }
    }

    /**
     * Selects up to [MAX_STORE_PRIORITY] actionable priority packages for Aurora's 200-slot priority list.
     * Reserves slots for already-dispatched/active apps before not-yet-dispatched pending hints so queued
     * sign-in apps in Aurora are never pushed past slot 200 (and evaluated under Aurora's 6 GiB bulk floor),
     * while preserving relative priority order among the selected packages.
     */
    private fun selectStorePriorityBatch(id: String, actionable: List<String>): List<String> {
        if (actionable.size <= MAX_STORE_PRIORITY) return actionable
        val dispatched = actionable.filter { !isPending(id, it) }.take(MAX_STORE_PRIORITY)
        val room = (MAX_STORE_PRIORITY - dispatched.size).coerceAtLeast(0)
        val pendingHints = if (room > 0) actionable.filter { isPending(id, it) }.take(room) else emptyList()
        val keep = (dispatched + pendingHints).toSet()
        return actionable.filter { it in keep }
    }

    /** Forwards a session's actionable ordered priority list to Aurora when it advertises `prioritize_downloads`. */
    private fun syncStorePriority(id: String) {
        val (ordered, toSend, hasOverflow) = synchronized(LOCK) {
            if (!prefs.getBoolean("$id:priority_dirty", false)) return
            val all = priorityOrder(id)
            val actionable = all.filter { isPriorityActive(id, it) }
            val batch = selectStorePriorityBatch(id, actionable)
            Triple(all, batch, actionable.size > batch.size)
        }
        if (toSend.isEmpty()) {
            synchronized(LOCK) {
                if (priorityOrder(id) == ordered) check(prefs.edit().putBoolean("$id:priority_dirty", false).commit())
            }
            return
        }
        if (roundSyncedPriority[id] == toSend) return
        val capabilities = storeCapabilities()
        if (capabilities.getString("error") == "store_unavailable") return
        var allSentAccepted = true
        if (capabilities.getBoolean("prioritize_downloads")) {
            val reply = control(8, options(id).apply { putStringArrayList("packages", ArrayList(toSend)) })
            if (reply.getString("error") == "store_unavailable") return
            val unsupported = reply.getString("error") == "unsupported_operation"
            if (!reply.getBoolean("success", false) && !unsupported) return
            if (!unsupported && reply.containsKey("packages")) {
                val accepted = reply.getStringArrayList("packages").orEmpty().toSet()
                allSentAccepted = toSend.all { it in accepted }
            }
            if (!unsupported) roundSyncedPriority[id] = toSend
        }
        synchronized(LOCK) {
            if (!hasOverflow && allSentAccepted && priorityOrder(id) == ordered) {
                check(prefs.edit().putBoolean("$id:priority_dirty", false).commit())
            }
        }
    }
    /**
     * One pass over every pending install; false if any must be tried again. A pass over a whole copy takes as long as the
     * copy, so the session's results are also checked every [SUMMARY_EVERY_MS] on the way: that check queues Play's
     * refusals of the anonymous session once more for the Google account (11-17 founders-02: eight refused apps would
     * otherwise wait for the end of a 287-app pass).
     */
    fun dispatchRound(
        cancelled: () -> Boolean = { false },
        clock: () -> Long = this.clock,
        processRestores: Boolean = true,
    ): Boolean {
        throttled = false
        allAccounts = null
        roundCapabilities = null
        roundSyncedPriority.clear()
        var settled = true
        var unfrozen: String? = null
        var checkedAt = clock()
        // Chosen anew after each hand-off (dispatchOrder): a priority Setup set or an app queued again for the Google
        // account meanwhile goes next, not after the round. Each app once a round per attempt: its key holds what makes
        // it another attempt (the Google account, the owner's Retry).
        val attempted = mutableSetOf<String>()
        fun attempt(id: String, name: String) = "$id:$name:${prefs.getBoolean("$id:$name:owner_account", false)}:${prefs.getInt("$id:$name:retry_gen", 0)}"
        fun next() = dispatchOrder().firstOrNull { (id, name) -> attempt(id, name) !in attempted }
        try {
            for (id in sessions()) {
                if (cancelled()) return false
                syncStorePriority(id)
            }
            var entry = next()
            while (entry != null) {
                val (id, name) = entry
                if (cancelled()) return false
                syncStorePriority(id)
                unfrozen = id
                attempted += attempt(id, name)
                if (!dispatch(name, id)) settled = false
                if (throttled) return false // Play limits the store, not one session: pause every install.
                // Freeze this session's receipts before another session can touch the same Aurora row.
                if (next()?.first != id) { summary(id, reschedule = false, freeze = true); unfrozen = null; checkedAt = clock() }
                else if (clock() - checkedAt >= SUMMARY_EVERY_MS) {
                    summary(id, reschedule = false)
                    if (pendingAppDataRestores(id).isNotEmpty()) processPendingAppDataRestores(cancelled, clock)
                    checkedAt = clock()
                }
                entry = next()
            }
            if (processRestores && !processPendingAppDataRestores(cancelled)) settled = false
            return settled
        } finally {
            // A stop or 429 can end the round mid-session; the next round may start with another session.
            unfrozen?.let { summary(it, reschedule = false, freeze = true) }
        }
    }
    /**
     * Hands one pending app to Aurora. Aurora is asked and called outside [LOCK]: only the bookkeeping around those calls
     * holds it, so Switch's next chunk ([select]) never waits behind an install (11-17 founders-02: chunks took up to 32 s).
     * The job runs dispatches one at a time; [retry] and [select] may change the app's records meanwhile, so each step
     * reads them again under the lock.
     */
    fun dispatch(name: String, id: String = session()): Boolean {
        // In flight from before Aurora is asked about it: a summary meanwhile leaves the app alone (and saves nothing that
        // would make this answer look stale).
        val asked = synchronized(LOCK) { if (!isPending(id, name)) return true; IN_FLIGHT += key(id, name); revision(id, name) }
        try {
            val current = bridge(3, name)
            if (current.getString("error") == "store_unavailable") return false
            // Whether Aurora can resume an install waiting for confirmation after an owner Retry: asked before the lock.
            val approvedRetry = current.getString("status") == "awaiting_install" &&
                control(7, Bundle()).getBoolean("owner_approved_retry")
            val request = synchronized(LOCK) {
                // The owner retried while Aurora was asked: decide in the next round, with nothing changed by this stale answer.
                if (revision(id, name) != asked) return false
                adoptGoogleAccount(id, name, current)
                decide(name, id, current, approvedRetry)?.also { bump(id, name) }
            } ?: return true
            if (request.done != null) return request.done
            // Aurora tries every account in this one request: no second one for the Google account follows (CYC-2499).
            val allAccounts = allAccountsSupported()
            if (allAccounts) request.options.apply { putBoolean("all_accounts", true); putBoolean("owner_retry_on_refusal", false) }
            synchronized(LOCK) {
                if (prefs.getInt("$id:$name:retry_gen", 0) == request.retryGen)
                    check(prefs.edit().putBoolean("$id:$name:all_accounts", allAccounts).commit())
            }
            val result = bridge(2, name, request.options)
            // Aurora's answers that need another Aurora call, asked before taking the lock again.
            val supported = if (anonymousRefusal(result) && !request.ownerAccount && !allAccounts) ownerAccountSupported() else false
            // Without a store session, ask Aurora to prepare one before the next round (unless the Google account takes over).
            if (result.getString("error") in SESSION_FAILURES && !supported) control(6)
            return synchronized(LOCK) { settle(name, id, result, request, supported) }
        } finally { synchronized(LOCK) { IN_FLIGHT -= key(id, name); bump(id, name) } }
    }
    private class Request(val options: Bundle, val ownerRetry: Boolean, val ownerAccount: Boolean, val done: Boolean? = null, val retryGen: Int = 0)
    /** What to do with [current]: null when nothing is left to do, a [Request] to send, or one whose [Request.done] is the answer. */
    private fun decide(name: String, id: String, current: Bundle, approvedRetry: Boolean): Request? {
        if (!isPending(id, name)) return null
        fun finished(value: Boolean) = Request(Bundle(), false, false, value)
        if (prefs.getBoolean("$id:$name:ever_installed", false)) {
            save(name, if (satisfies(name, current, id)) current else removedResult(current), true, id)
            return finished(true)
        }
        val ownerRetry = prefs.getBoolean("$id:$name:owner_retry", false)
        val needsReview = !satisfies(name, current, id) && (
            current.getString("status") == "awaiting_install" || (current.getString("status") == "none" &&
                (prefs.getBoolean("$id:$name:uncertain", false) || prefs.getBoolean("$id:$name:acknowledged", false))))
        val result = if (removed(current)) {
            check(prefs.edit().putBoolean("$id:$name:ever_installed", true).commit())
            removedResult(current)
        } else if (needsReview && !ownerRetry) {
            ownerReview(current, if (current.getString("status") == "awaiting_install") "installation_confirmation_required" else "installation_requires_review")
        } else if (olderAttempt(name, current, id) && current.getString("status") in ACTIVE) {
            // Let the old attempt settle, then reconcile this session's still-pending target.
            save(name, current, id = id)
            check(prefs.edit().putBoolean("$id:$name:acknowledged", true).commit())
            return finished(false)
        } else if (ownerRetry && current.getString("status") == "awaiting_install" && !approvedRetry) {
            ownerReview(current, "installation_confirmation_required") // Legacy Aurora: use its owner-facing install UI.
        } else if (needsEnqueue(name, current, id) || (ownerRetry && current.getString("status") == "awaiting_install")) {
            // Consume this single owner approval atomically before IPC. A lost reply must
            // return to review; the captured ownerRetry value applies only to this request.
            val edit = prefs.edit().putBoolean("$id:$name:uncertain", true)
                .putBoolean("$id:$name:owner_retry", false)
            if ((current.getBoolean("installed") || current.getBoolean("package_present")) &&
                !prefs.getBoolean("$id:$name:install_dispatched", false) &&
                !prefs.getBoolean("$id:$name:observed_not_installed", false)
            ) {
                edit.putBoolean("$id:$name:preexisting", true)
            } else if (!current.getBoolean("installed") && !current.getBoolean("package_present")) {
                // Persist confirmed absence before sending the install IPC so a lost reply (store_unavailable)
                // cannot make a newly installed app look preexisting when it finishes installing later.
                edit.putBoolean("$id:$name:observed_not_installed", true)
            }
            check(edit.commit())
            val ownerAccount = prefs.getBoolean("$id:$name:owner_account", false)
            val priorityRank = priorityOrder(id).indexOf(name)
            return Request(options(id).apply {
                // The Google account gets whatever version Play offers it; the reply's version_code becomes the floor.
                putLong("minimum_version_code", if (ownerAccount) 0 else expectedVersion(id, name))
                putBoolean("owner_approved_retry", ownerRetry)
                putBoolean("owner_account_approved", ownerAccount)
                // summary() queues a download Play refuses the anonymous session once more for the Google account.
                putBoolean("owner_retry_on_refusal", !ownerAccount)
                if (priorityRank >= 0) {
                    putBoolean("priority", true)
                    putInt("priority_rank", priorityRank)
                }
            }, ownerRetry, ownerAccount, retryGen = prefs.getInt("$id:$name:retry_gen", 0))
        } else current
        // App was already installed on the phone before FakeStore ever requested an install: mark preexisting so we
        // never overwrite the owner's live app data with an older staged backup on a repeat transfer.
        if (satisfies(name, result, id) &&
            !prefs.getBoolean("$id:$name:install_dispatched", false) &&
            !prefs.getBoolean("$id:$name:observed_not_installed", false)
        ) {
            check(prefs.edit().putBoolean("$id:$name:preexisting", true).commit())
        }
        return finished(record(name, id, result, requestedInstall = false, ownerRetry = ownerRetry))
    }
    /** The bookkeeping after Aurora's answer to an install request ([dispatch]). */
    private fun settle(name: String, id: String, answer: Bundle, request: Request, supported: Boolean): Boolean {
        // The owner tapped Retry again while this request was with Aurora: the newer approval stays pending, untouched.
        if (prefs.getInt("$id:$name:retry_gen", 0) != request.retryGen) return false
        var result = answer
        val ownerRetry = request.ownerRetry
        // Review of #103: Aurora refused this request before enqueuing it (no store, a rate limit, no session), so its
        // download row is still the previous attempt's: this attempt asked no account yet, and that row's account choice
        // must not be adopted by a later summary. The next request marks all_accounts again.
        val preEnqueue = result.getString("error").let { it == "store_unavailable" || it == RATE_LIMITED || it in SESSION_FAILURES }
        if (preEnqueue) check(prefs.edit().putBoolean("$id:$name:all_accounts", false).commit())
        if (result.getString("error") == "store_unavailable") return false
        adoptGoogleAccount(id, name, result)
        if (prefs.getBoolean("$id:$name:owner_account", false) && result.getLong("version_code") > 0) {
            check(prefs.edit().putLong("$id:$name:ownerVersion", result.getLong("version_code")).commit())
        }
        if (anonymousRefusal(result) &&
            !prefs.getBoolean("$id:$name:owner_account", false) && supported) {
            // As on a Pixel, Play may install with the phone's Google account. Aurora refused before enqueuing,
            // so this is no uncertain install: keep the app pending for the account and keep the owner's retry.
            check(prefs.edit().putBoolean("$id:$name:owner_account", true).remove("$id:$name:ownerVersion").putBoolean("$id:$name:uncertain", false)
                .putBoolean("$id:$name:owner_retry", ownerRetry).putInt("$id:$name:throttle_count", 0).commit())
            return false
        }
        val sessionFailure = result.getString("error") in SESSION_FAILURES
        if (result.getString("error") == RATE_LIMITED || sessionFailure) {
            // Aurora refused before enqueuing, so this is no uncertain install. Keep it pending, and keep the owner's retry.
            // Without a store session (and after the Google account fallback), [dispatch] already asked Aurora to prepare
            // one before the next round instead of failing the app: on 10-30 a copy's last apps failed this way while the
            // store recovered.
            throttled = true
            val refusals = prefs.getInt("$id:$name:throttle_count", 0) + 1
            check(prefs.edit().putBoolean("$id:$name:uncertain", false).putBoolean("$id:$name:owner_retry", ownerRetry)
                .putLong("$id:$name:throttled_at", System.currentTimeMillis()).putInt("$id:$name:throttle_count", refusals).commit())
            if (refusals < MAX_RATE_LIMITED) return false
            // Play keeps refusing: show the owner a failure they can retry.
            result = Bundle(result).apply { putString("status", "unavailable"); putBoolean("terminal", true) }
        }
        return record(name, id, result, requestedInstall = true, ownerRetry = ownerRetry)
    }
    /** Saves [result] for [name]; false while an older attempt of it still has to settle. */
    private fun record(name: String, id: String, answer: Bundle, requestedInstall: Boolean, ownerRetry: Boolean): Boolean {
        var result = answer
        if (result.getString("error") == "store_unavailable") return false
        // Aurora refused before enqueuing. Freeze that reason; a status refresh would show an older download row's error.
        if (requestedInstall && result.getString("status") == "unavailable") result = Bundle(result).apply { putBoolean("terminal", true) }
        if (requestedInstall && result.getString("status") == "installed" && !satisfies(name, result, id) &&
            (result.getBoolean("package_present") || result.getBoolean("installed"))) {
            // Legacy Aurora cannot enforce the selected minimum. Its settled lower offer needs review,
            // not an endless clear/re-add/reinstall cycle.
            result = ownerReview(result, "selected_version_unavailable")
        }
        val retainTarget = olderAttempt(name, result, id) && result.getString("status") in ACTIVE
        if (requestedInstall && !retainTarget) {
            check(prefs.edit().putBoolean("$id:$name:install_dispatched", true).commit())
        }
        save(name, result, dispatched = !retainTarget, id = id)
        if (retainTarget) check(prefs.edit().putBoolean("$id:$name:acknowledged", true).putBoolean("$id:$name:uncertain", false).commit())
        changed(id)
        return !retainTarget
    }
    /**
     * Aurora's status of each of [names], over one binding (CYC-2573: one bind and unbind per app made Aurora create and
     * destroy its service for each). A failed call fails only its app, as [bridge] does, and the next app binds again;
     * so does one after Aurora died. A call that finds the binding of an earlier app dead (Aurora died before Android
     * told us) is made once more over a new binding, as [bridge]'s own binding would have been. The storeCall test hook
     * keeps one [bridge] call per app.
     */
    private fun statuses(names: Collection<String>): Map<String, Bundle> {
        if (storeCall != null) return names.associateWith { bridge(3, it) }
        var link: AuroraMigrationClient? = null
        fun drop() { link?.let { runCatching { it.close() } }; link = null }
        fun ask(name: String): Bundle = (link ?: AuroraMigrationClient(context).also { link = it }).call(3, name)
        try {
            return names.associateWith { name ->
                if (link?.alive == false) drop()
                val reused = link != null
                try {
                    ask(name)
                } catch (e: Exception) {
                    drop()
                    if (reused && e is DeadObjectException) {
                        try { ask(name) } catch (_: Exception) { drop(); unavailable(name, "store_unavailable") }
                    } else unavailable(name, "store_unavailable")
                }
            }
        } finally { drop() }
    }
    fun bridge(operation: Int, name: String, options: Bundle? = null): Bundle = try {
        storeCall?.let { sentOptions?.invoke(operation, options); it(operation, name) }
            ?: AuroraMigrationClient(context).use { it.call(operation, name, options) }
    } catch (_: Exception) { unavailable(name, "store_unavailable").apply { putBoolean("recoverable", true) } }
    fun control(operation: Int, options: Bundle = options(session())): Bundle = try {
        controlCall?.invoke(operation, options) ?: AuroraMigrationClient(context).use { it.control(operation, options) }
    } catch (_: Exception) { Bundle().apply { putBoolean("success", false); putBoolean("recoverable", true); putString("error", "store_unavailable") } }
    fun changed(id: String) { context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName).putExtra("session_id", id)) }
    /** Tests: optional override for JobScheduler.schedule to test wake/schedule ordering and failure recovery. */
    internal var jobSchedulerHook: ((JobScheduler, JobInfo) -> Int)? = null
    private fun scheduleJob(scheduler: JobScheduler, info: JobInfo): Int =
        jobSchedulerHook?.invoke(scheduler, info) ?: scheduler.schedule(info)
    private fun schedule() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        // Expedited (CYC-2499, 11-18 founders-02): at thermal status 2 Android held the default-priority job back for
        // minutes (Restricted due to: thermal) while the copy's apps waited; an expedited job still runs there.
        check(scheduleInstallJob(JobInfo.Builder(JOB, ComponentName(context, MigrationInstallJob::class.java)).setPersisted(true)) {
            scheduleJob(scheduler, it)
        })
        // Only cancel the fallback deferred wake AFTER the primary install job is durably scheduled.
        scheduler.cancel(DEFER_WAKE_JOB)
    }
    internal fun scheduleImmediate() = schedule()
    /**
     * Soonest delay before [MigrationInstallJob] should wake during an idle deferred copy:
     * ~10 min to renew Aurora's 15-min update hold while paused, capped at the remaining 12h fail-open window.
     */
    internal fun nextDeferredWakeDelayMs(nowMs: Long = clock()): Long? = synchronized(LOCK) {
        var soonest: Long? = null
        for (id in sessions()) {
            if (!isBulkDeferred(id, nowMs)) continue
            val pauseRequested = prefs.getBoolean("$id:pause_requested", false)
            val hasPending = pending(id).isNotEmpty()
            if (!pauseRequested && !hasPending) continue
            val remaining = deferBulkRemainingMs(id, nowMs)
            val delay = if (pauseRequested) minOf(DEFER_RENEW_INTERVAL_MS, remaining) else remaining
            soonest = if (soonest == null) delay else minOf(soonest, delay)
        }
        soonest
    }
    /**
     * Schedules a lightweight non-expedited delayed wake while a deferred copy has no active work,
     * or cancels any pending wake once deferral ends or settles. Returns the scheduled latency or null.
     */
    internal fun syncDeferredWake(nowMs: Long = clock()): Long? = synchronized(LOCK) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return@synchronized null
        val delayMs = nextDeferredWakeDelayMs(nowMs)
        if (delayMs == null) {
            scheduler.cancel(DEFER_WAKE_JOB)
            return@synchronized null
        }
        val latency = delayMs.coerceAtLeast(MIN_DEFER_WAKE_MS)
        val job = JobInfo.Builder(DEFER_WAKE_JOB, ComponentName(context, MigrationInstallJob::class.java))
            .setPersisted(true)
            .setExpedited(false)
            .setMinimumLatency(latency)
            .setOverrideDeadline(latency + DEFER_WAKE_FLEX_MS)
            .build()
        check(scheduleJob(scheduler, job) == JobScheduler.RESULT_SUCCESS)
        latency
    }
    companion object {
        /**
         * The install job, expedited where Android allows it. Review of #101: Android refuses a new expedited job once the
         * app's expedited quota is spent (only a pending one is demoted), which left a copy's apps with no install job at
         * all; then the same job goes in, not expedited. Whether one was scheduled.
         */
        internal fun scheduleInstallJob(job: JobInfo.Builder, schedule: (JobInfo) -> Int): Boolean =
            schedule(job.setExpedited(true).build()) == JobScheduler.RESULT_SUCCESS ||
                schedule(job.setExpedited(false).build()) == JobScheduler.RESULT_SUCCESS
        const val ACTION_CHANGED = "org.microg.vending.migration.CHANGED"
        internal val LOCK = Any()
        /** Apps whose install request is with Aurora now ("session:package"); in-process, like the job and the service. */
        private val IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** Apps whose post-install app-data restore RPC is currently in flight ("session:package"). */
        private val RESTORE_IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** Switch ejj.java:138-159 restore_source codes for local Android-to-Android cable (1) and Wi-Fi (2) D2D transfers. */
        private val ANDROID_D2D_RESTORE_SOURCES = setOf(1, 2)
        /** Switch ejj.java:138-142 restore_source codes for iOS cable (5) and iOS Wi-Fi (6) transfers. */
        private val IOS_RESTORE_SOURCES = setOf(5, 6)
        private val APP_DATA_RESTORE_TERMINAL = setOf("restored", "no_data", "unsupported", "stale_session")
        private const val MAX_APP_DATA_RESTORE_ATTEMPTS = 20
        private const val MAX_APP_DATA_RESTORE_BUSY_MS = 6L * 60L * 60_000L
        private fun currentBootCount(context: Context): Int =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)
        /** Per app, how often its records changed in this process: a status asked of Aurora before a change is stale. */
        private val REVISIONS = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private fun key(id: String, name: String) = "$id:$name"
        private fun revision(id: String, name: String) = REVISIONS[key(id, name)] ?: 0L
        private fun bump(id: String, name: String) { REVISIONS.merge(key(id, name), 1L, Long::plus) }
        internal const val JOB = 700714
        internal const val DEFER_WAKE_JOB = 700715
        /** Fail-open wall-clock cap on bulk deferral so apps can never strand indefinitely (CYC-3422). */
        internal const val MAX_DEFER_BULK_MS = 12L * 60L * 60_000L
        /** Renewal cadence for Aurora's 15-minute MigrationUpdateHold lease during an idle deferred copy. */
        internal const val DEFER_RENEW_INTERVAL_MS = 10L * 60_000L
        internal const val DEFER_WAKE_FLEX_MS = 2L * 60_000L
        private const val MIN_DEFER_WAKE_MS = 1_000L
        private const val RATE_LIMITED = "rate_limited"
        private const val MAX_RATE_LIMITED = 3
        /** How often a long pass checks its session's results (and queues refused apps for the Google account). */
        internal const val SUMMARY_EVERY_MS = 60_000L
        /** How often a summary at a transfer boundary asks again about apps that changed while it asked Aurora. */
        private const val FREEZE_ATTEMPTS = 3
        /** At most this many sign-in apps go first per call ([prioritize]). */
        private const val MAX_PRIORITY = 50
        /** Maximum total ordered priority packages retained for one session. */
        internal const val MAX_PRIORITY_TOTAL = 500
        /** Maximum actionable priority packages forwarded to Aurora per op 8 call (matches Aurora DownloadPriority.MAX_TOTAL). */
        internal const val MAX_STORE_PRIORITY = 200
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        /** The anonymous session's own limits: Play throttling it, not listing the app in its country, or no session. */
        private val ANONYMOUS_FAILURES = setOf(RATE_LIMITED, "not_found", "app_not_supported", "anonymous_sign_in_required", "not_signed_in", "session_expired")
        /** The store had no usable session: says nothing about the app, so it is tried again like a rate limit. */
        private val SESSION_FAILURES = setOf("anonymous_sign_in_required", "not_signed_in", "session_expired")
        /** Aurora's codes for Play refusing the anonymous session a download it had queued (DownloadFailure). */
        private val DOWNLOAD_REFUSALS = setOf("app_not_purchased", RATE_LIMITED)
        private val ACTIVE = setOf("queued", "purchasing", "downloading", "verifying", "completed", "installing")
        private val TERMINAL = setOf("failed", "cancelled", "unavailable", "removed", "owner_action_required")
        fun unavailable(name: String, reason: String) = Bundle().apply {
            putString("package_name", name); putString("title", name); putInt("availability", 2)
            putString("status", "unavailable"); putString("error", reason); putBoolean("installed", false)
        }
    }
}

class MigrationInstallJob : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val jobCancellation = java.util.concurrent.ConcurrentHashMap<Int, AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false)
        jobCancellation.put(params.jobId, cancelled)?.set(true)
        activeRuns.incrementAndGet()
        executor.execute {
            try {
                runJob(params.jobId, cancelled) { reschedule -> runCatching { jobFinished(params, reschedule) } }
            } finally {
                jobCancellation.remove(params.jobId, cancelled)
                activeRuns.decrementAndGet()
            }
        }
        return true
    }
    internal fun registerJobForTests(jobId: Int): AtomicBoolean =
        AtomicBoolean(false).also { jobCancellation.put(jobId, it)?.set(true) }
    internal fun stopJobById(jobId: Int): Boolean {
        jobCancellation[jobId]?.set(true)
        return true
    }
    internal fun runJob(
        jobId: Int,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        queue: MigrationInstallQueue = MigrationInstallQueue(this),
        deadlineNanos: Long = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(8),
        onFinished: (Boolean) -> Unit,
    ) {
        var retry = false
        try {
            do {
                retry = false
                if (!queue.dispatchRound(cancelled = { cancelled.get() }, processRestores = false)) retry = true
                if (cancelled.get()) return
                val states = mutableMapOf<String, Bundle>()
                for (id in queue.sessions()) {
                    // The job's own loop goes on by itself: scheduling the same job here would stop it.
                    val state = queue.summary(id, reschedule = false).also { states[id] = it }
                    queue.changed(id)
                    MigrationNotifications.selectionOpen(this, id, state)
                    if (state.getBoolean("complete")) {
                        queue.releaseCompletedPause(id)
                        MigrationNotifications.completed(this, id, state)
                    } else if (queue.selected(id).isNotEmpty()) {
                        // Settled chunks do not prove the selection closed. Preserve the lease and
                        // receipts without polling forever; the next chunk/explicit close schedules work.
                        if (state.getBoolean("has_active_work", !state.getBoolean("installs_settled"))) retry = true
                        queue.renewPause(id)
                    } else if (queue.isBulkDeferred(id)) {
                        queue.renewPause(id)
                    }
                }
                MigrationSetupReceivers.publish(queue, states)
                if (!queue.processPendingAppDataRestores(cancelled = { cancelled.get() })) retry = true
                if (queue.pendingAppDataRestores().isNotEmpty()) retry = true
                if (retry && !cancelled.get() && System.nanoTime() < deadlineNanos) {
                    Thread.sleep(if (queue.throttled) 30_000 else 2000)
                }
            } while (retry && !cancelled.get() && System.nanoTime() < deadlineNanos)
        } catch (_: Exception) {
            retry = true
        }
        if (!cancelled.get()) {
            val fromDeferredWake = jobId == MigrationInstallQueue.DEFER_WAKE_JOB
            // Sample clock once so expiry cannot slip between dispatchOrder() and syncDeferredWake().
            val finishNowMs = queue.clock()
            val readyNow = retry || queue.dispatchOrder(finishNowMs).isNotEmpty()
            val reschedule = if (readyNow) {
                if (fromDeferredWake) {
                    // Hand back to the primary install job first; if scheduling fails, retry this wake with backoff.
                    runCatching { queue.scheduleImmediate() }.isFailure
                } else {
                    true
                }
            } else {
                // Secure the next delayed wake (or cancel when settled) BEFORE finishing; on failure, back off & retry.
                runCatching { queue.syncDeferredWake(finishNowMs) }.isFailure
            }
            onFinished(reschedule)
        }
    }
    override fun onStopJob(params: JobParameters): Boolean = stopJobById(params.jobId)
    override fun onDestroy() {
        jobCancellation.values.forEach { it.set(true) }
        jobCancellation.clear()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private val activeRuns = java.util.concurrent.atomic.AtomicInteger(0)
        internal fun isRunning(): Boolean = activeRuns.get() > 0
    }
}
