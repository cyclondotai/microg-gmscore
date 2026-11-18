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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Durable, separate receipts for each transfer. A status query never authorizes another install. */
internal class MigrationInstallQueue(private val context: Context,
    private val controlCall: ((Int, Bundle) -> Bundle)? = null,
    private val storeCall: ((Int, String) -> Bundle)? = null) {
    /** Tests: the options each faked [storeCall] request would carry to Aurora. */
    internal var sentOptions: ((Int, Bundle?) -> Unit)? = null
    private val prefs get() = context.getSharedPreferences("migration-installs", Context.MODE_PRIVATE)
    /** Play answered 429 since the job last cleared this; stop the round and wait before asking again. */
    @Volatile var throttled = false
    /** Pending installs across sessions. Apps Play refused most recently go last, so one cannot block the rest. */
    fun dispatchOrder(): List<Pair<String, String>> = sessions().flatMap { id -> pending(id).sorted().map { id to it } }
        .sortedBy { (id, name) -> prefs.getLong("$id:$name:throttled_at", 0) }
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
    fun beginTransfer(): String = synchronized(LOCK) { newSession() }

    private fun newSession(run: String? = null): String {
        val id = UUID.randomUUID().toString()
        val edit = prefs.edit().putString("current_session", id).putString("sessions", (listOf(id) + sessions()).joinToString(","))
        val handoffSession = prefs.getString("handoff_session", null)
        if (run != null) {
            edit.putString("handoff_run", run).putString("handoff_session", id).remove("handoff_outcome")
        } else if (prefs.contains("handoff_run") && !prefs.contains("handoff_outcome") &&
            (handoffSession == null || !prefs.getBoolean("$handoffSession:selected", false))) {
            edit.putString("handoff_session", id)
        }
        check(edit.commit())
        return id
    }

    fun beginHandoff(run: String, resumeOnly: Boolean): Bundle = synchronized(LOCK) {
        if (prefs.getString("handoff_run", null) != run) {
            if (resumeOnly) return Bundle().apply { putBoolean("accepted", false) }
            newSession(run)
        }
        Bundle().apply { putBoolean("accepted", !prefs.contains("handoff_outcome")) }
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
            val selected = (snapshot["$id:handoff_packages"] as? Set<*>)?.filterIsInstance<String>().orEmpty().sorted()
            val names = selected.drop(offset).take(64)
            putInt("total", selected.size)
            if (offset + names.size < selected.size) putInt("next_offset", offset + names.size)
            putStringArrayList("packages", ArrayList(names))
            putStringArrayList("titles", ArrayList(names.map { name -> (snapshot["$id:$name:title"] as? String)?.take(80) ?: name }))
            val pending = snapshot["$id:pending"] as? Set<*> ?: emptySet<String>()
            putStringArrayList("statuses", ArrayList(names.map { name ->
                val status = (snapshot["$id:$name:status"] as? String).orEmpty()
                when {
                    name in pending -> "pending"
                    // summary() may have lost its Aurora connection while checking a dispatched app.
                    status == "unavailable" && snapshot["$id:$name:error"] == "store_unavailable" -> "queued"
                    else -> status
                }
            }))
        }
    }

    fun endHandoff(run: String, outcome: String): Bundle = synchronized(LOCK) {
        if (prefs.getString("handoff_run", null) != run) return Bundle().apply { putBoolean("accepted", false) }
        if (!prefs.contains("handoff_outcome")) {
            val id = prefs.getString("handoff_session", null)
            if (id != null) {
                if (!transferEnded(outcome, id, recordHandoff = true).getBoolean("accepted")) {
                    return Bundle().apply { putBoolean("accepted", false) }
                }
            } else {
                check(prefs.edit().putString("handoff_outcome", outcome).commit())
            }
        }
        handoff(run).apply { putBoolean("accepted", true) }
    }
    fun startPausedTransfer(): String = synchronized(LOCK) {
        currentSessionId()?.let { if (prefs.getBoolean("$it:pause_requested", false)) resume(it) }
        beginTransfer()
    }
    fun catalogSession(): String = synchronized(LOCK) {
        val id = currentSessionId()
        if (id == null) beginTransfer() else if (prefs.getBoolean("$id:selected", false)) {
            val held = prefs.getBoolean("$id:pause_requested", false)
            val next = beginTransfer()
            if (held) { resume(id); pause(next) }
            next
        } else id
    }
    private fun session(): String = currentSessionId() ?: beginTransfer()
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
        if (result.getBoolean("success")) check(prefs.edit().putBoolean("$id:pause_requested", true).commit())
        return result
    }
    fun resume(id: String): Bundle {
        check(prefs.edit().putBoolean("$id:pause_requested", false).commit())
        return control(5, options(id))
    }
    fun renewPause(id: String) { if (prefs.getBoolean("$id:pause_requested", false)) control(4, options(id)) }
    fun releaseCompletedPause(id: String) { if (prefs.getBoolean("$id:pause_requested", false)) resume(id) }
    fun recordOptions(options: Bundle) = synchronized(LOCK) {
        val id = session(); val edit = prefs.edit()
        for (key in listOf("documents_type", "restore_source", "disable_pre_archiving")) {
            if (options.containsKey(key)) edit.putString("$id:option:$key", options.get(key).toString())
        }
        check(edit.commit())
    }
    fun recordCatalog(documents: List<Bundle>) = synchronized(LOCK) {
        val id = session(); val edit = prefs.edit()
        val excluded = documents.filter { it.getInt("availability") != 1 }
        val names = documents.map { it.getString("package_name")!! }.toSet()
        edit.putStringSet("$id:excluded", (prefs.getStringSet("$id:excluded", emptySet()).orEmpty() - names) + excluded.map { it.getString("package_name")!! })
        for (doc in documents) {
            metadata(edit, id, doc)
            edit.putBoolean("$id:${doc.getString("package_name")}:recoverable", doc.getInt("availability") != 1 && doc.getBoolean("recoverable"))
        }
        check(edit.commit())
    }
    private fun metadata(edit: android.content.SharedPreferences.Editor, id: String, doc: Bundle) {
        val name = requireNotNull(doc.getString("package_name"))
        for (key in listOf("title", "icon_url", "developer_name", "error", "status")) {
            doc.getString(key)?.let { edit.putString("$id:$name:$key", it) }
        }
        if (doc.containsKey("version_code")) edit.putLong("$id:$name:expectedVersion", doc.getLong("version_code"))
    }
    fun select(documents: List<Bundle>) = synchronized(LOCK) {
        check(storeReady()) { "Update App Store before transferring apps" }
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
        for (doc in documents) metadata(edit, id, doc)
        check(edit.commit()); changed(id); schedule()
    }
    fun finishSelection(id: String) = synchronized(LOCK) {
        require(id in sessions())
        if (!prefs.getBoolean("$id:selection_closed", false)) {
            check(prefs.edit().putBoolean("$id:selected", true).putBoolean("$id:selection_closed", true).commit())
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
    fun save(name: String, result: Bundle, dispatched: Boolean = false, id: String = session()) = synchronized(LOCK) {
        bump(id, name)
        val edit = prefs.edit()
        for (key in listOf("status", "error")) edit.putString("$id:$name:$key", result.getString(key) ?: if (key == "error") result.getString("errorCode") else "unknown")
        edit.putBoolean("$id:$name:installed", result.getBoolean("installed"))
        edit.putBoolean("$id:$name:terminal", result.getBoolean("terminal"))
        edit.putLong("$id:$name:installedVersion", result.getLong("installed_version_code"))
        if (result.getBoolean("installed") && result.getLong("installed_version_code") >= expectedVersion(id, name))
            edit.putBoolean("$id:$name:ever_installed", true)
        if (dispatched) edit.putStringSet("$id:pending", pending(id) - name).putBoolean("$id:$name:uncertain", false)
            .putBoolean("$id:$name:acknowledged", true).putBoolean("$id:$name:owner_retry", false)
        check(edit.commit())
    }
    fun retryDocuments(id: String = currentSessionId().orEmpty()): List<Bundle> = (selected(id).filter { name ->
        prefs.getString("$id:$name:status", null) in setOf("failed", "cancelled", "unavailable", "owner_action_required") &&
            !prefs.getBoolean("$id:$name:ever_installed", false)
    } + recoverableExclusions(id)).map { name -> document(id, name) }
    /** Apps Switch left out because the store could not check them in time; the owner may still add them here. */
    private fun recoverableExclusions(id: String) = prefs.getStringSet("$id:excluded", emptySet()).orEmpty()
        .filter { it !in selected(id) && prefs.getBoolean("$id:$it:recoverable", false) }.sorted()
    /** Whether Aurora can install with the phone's Google account, the fallback when the anonymous session fails. */
    fun ownerAccountSupported(): Boolean = control(7, Bundle()).getBoolean("owner_account_fallback")
    /** An owner Retry starts again with the anonymous session; the Google account fallback applies again after it. */
    fun retry(id: String, packageName: String? = null) = synchronized(LOCK) {
        require(id in sessions())
        val names = retryDocuments(id).map { it.getString("package_name")!! }.filter { packageName == null || it == packageName }
        if (names.isNotEmpty()) control(6, options(id).apply { putBoolean("force_refresh", true) })
        // A left-out app joins the selection now; the install looks it up again, as for any selected app.
        val added = recoverableExclusions(id).filter { it in names }
        val edit = prefs.edit().putStringSet("$id:pending", pending(id) + names)
        if (added.isNotEmpty()) edit.putStringSet("$id:packages", selected(id) + added)
            .putStringSet("$id:excluded", prefs.getStringSet("$id:excluded", emptySet()).orEmpty() - added.toSet())
        for (name in names) bump(id, name)
        for (name in names) edit.putInt("$id:$name:retry_gen", prefs.getInt("$id:$name:retry_gen", 0) + 1)
        for (name in names) edit.putBoolean("$id:$name:terminal", false).putBoolean("$id:$name:uncertain", false)
            .putBoolean("$id:$name:owner_retry", true).putBoolean("$id:$name:owner_account", false)
            .putInt("$id:$name:throttle_count", 0)
        check(edit.commit())
        changed(id); schedule()
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
    /**
     * Every app's state, asked of Aurora before taking [LOCK] (one call per open app): Switch's next chunk and its status
     * calls never wait for these round trips, only for the bookkeeping after them.
     */
    fun summary(id: String = currentSessionId().orEmpty(), reschedule: Boolean = true): Bundle {
        val revs = synchronized(LOCK) {
            val pending = pending(id)
            selected(id).filter { !(prefs.getBoolean("$id:$it:terminal", false) && it !in pending) }.associateWith { revision(id, it) }
        }
        val asked = revs.keys.associateWith { bridge(3, it) }
        val refused = asked.values.any { it.getString("status") == "failed" && it.getString("errorCode") in DOWNLOAD_REFUSALS }
        // Null when Aurora could not be asked: the refused download then waits for a later summary, not a failure.
        val fallback = if (refused) control(7, Bundle()).takeUnless { it.getString("error") == "store_unavailable" }?.getBoolean("owner_account_fallback") else null
        return synchronized(LOCK) { summarize(id, asked, revs, refused, fallback, reschedule) }
    }
    private fun summarize(id: String, asked: Map<String, Bundle>, revs: Map<String, Long>, fallbackAsked: Boolean, fallback: Boolean?,
                          reschedule: Boolean): Bundle {
        val ownerFallback by lazy { if (fallbackAsked) fallback else control(7, Bundle()).takeUnless { it.getString("error") == "store_unavailable" }?.getBoolean("owner_account_fallback") }
        val results = selected(id).sorted().map { name ->
            val saved = document(id, name)
            if (saved.getBoolean("terminal") && name !in pending(id)) return@map saved
            // Aurora's answer is stale when the app changed since it was asked, or an install request for it is still
            // waiting on Aurora: keep what was saved (open while in flight) and check it next time. An app that became
            // open after the others were asked is checked next time too, so Aurora is never called under the lock.
            val current = asked[name]
            if (current == null || key(id, name) in IN_FLIGHT || revision(id, name) != revs[name]) {
                return@map saved.apply { if (name in pending(id) || key(id, name) in IN_FLIGHT) putBoolean("terminal", false) }
            }
            var result = saved.apply {
                if (current.getString("status") != "none") { remove("error"); remove("errorCode") }
                if (current.getString("title") == name) current.remove("title")
                if (current.getString("icon_url").isNullOrEmpty()) current.remove("icon_url")
                putAll(current)
            }
            val acknowledged = prefs.getBoolean("$id:$name:acknowledged", name !in pending(id) && prefs.contains("$id:$name:status"))
            val ownerRetry = prefs.getBoolean("$id:$name:owner_retry", false)
            val status = result.getString("status")
            if (status == "failed" && result.getString("errorCode") in DOWNLOAD_REFUSALS && name !in pending(id) &&
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
            } else if (status == "installed" && !satisfies(name, result, id) && name !in pending(id)) {
                result = ownerReview(result, "installation_verification_required")
            }
            if (!satisfies(name, result, id)) result.putBoolean("installed", false)
            val terminal = result.getBoolean("installed") || (name !in pending(id) &&
                result.getString("status") in TERMINAL && result.getString("error") != "store_unavailable")
            result.putBoolean("terminal", terminal)
            save(name, result, id = id)
            result
        } + prefs.getStringSet("$id:excluded", emptySet()).orEmpty().filter { it !in selected(id) }.sorted().map { name ->
            document(id, name).apply { putBoolean("terminal", true); putBoolean("excluded", true) }
        }
        return Bundle().apply {
            putString("session_id", id); putParcelableArray("cyclon_app_results", results.toTypedArray())
            putString("transfer_outcome", prefs.getString("$id:transfer_outcome", null))
            putBoolean("all_installed", results.all { it.getBoolean("installed") })
            putBoolean("selection_closed", prefs.getBoolean("$id:selection_closed", false))
            putBoolean("installs_settled", pending(id).isEmpty() && results.all { it.getBoolean("terminal") })
            putBoolean("complete", getBoolean("selection_closed") && getBoolean("installs_settled"))
            putBundle("setup_options", options(id))
            putString("cyclon_hold_state", if (getBoolean("complete")) "complete" else "installing")
        }
    }
    /**
     * One pass over every pending install; false if any must be tried again. A pass over a whole copy takes as long as the
     * copy, so the session's results are also checked every [SUMMARY_EVERY_MS] on the way: that check queues Play's
     * refusals of the anonymous session once more for the Google account (11-17 founders-02: eight refused apps would
     * otherwise wait for the end of a 287-app pass).
     */
    fun dispatchRound(cancelled: () -> Boolean = { false }, clock: () -> Long = { System.currentTimeMillis() }): Boolean {
        throttled = false
        var settled = true
        var unfrozen: String? = null
        var checkedAt = clock()
        try {
            val order = dispatchOrder()
            for ((index, entry) in order.withIndex()) {
                val (id, name) = entry
                if (cancelled()) return false
                unfrozen = id
                if (!dispatch(name, id)) settled = false
                if (throttled) return false // Play limits the store, not one session: pause every install.
                // Freeze this session's receipts before another session can touch the same Aurora row.
                if (order.getOrNull(index + 1)?.first != id) { summary(id, reschedule = false); unfrozen = null; checkedAt = clock() }
                else if (clock() - checkedAt >= SUMMARY_EVERY_MS) { summary(id, reschedule = false); checkedAt = clock() }
            }
            return settled
        } finally {
            // A stop or 429 can end the round mid-session; the next round may start with another session.
            unfrozen?.let { summary(it, reschedule = false) }
        }
    }
    /**
     * Hands one pending app to Aurora. Aurora is asked and called outside [LOCK]: only the bookkeeping around those calls
     * holds it, so Switch's next chunk ([select]) never waits behind an install (11-17 founders-02: chunks took up to 32 s).
     * The job runs dispatches one at a time; [retry] and [select] may change the app's records meanwhile, so each step
     * reads them again under the lock.
     */
    fun dispatch(name: String, id: String = session()): Boolean {
        val asked = synchronized(LOCK) { if (name !in pending(id)) return true; revision(id, name) }
        val current = bridge(3, name)
        if (current.getString("error") == "store_unavailable") return false
        val request = synchronized(LOCK) {
            // The app changed while Aurora was asked (a summary saved it, the owner retried): decide in the next round.
            if (revision(id, name) != asked) return false
            decide(name, id, current)?.also { if (it.done == null) IN_FLIGHT += key(id, name); bump(id, name) }
        } ?: return true
        if (request.done != null) return request.done
        try {
            val result = bridge(2, name, request.options)
            // Aurora's answers that need another Aurora call, asked before taking the lock again.
            val supported = if (result.getString("error") in ANONYMOUS_FAILURES && !request.ownerAccount) ownerAccountSupported() else false
            // Without a store session, ask Aurora to prepare one before the next round (unless the Google account takes over).
            if (result.getString("error") in SESSION_FAILURES && !supported) control(6)
            return synchronized(LOCK) { settle(name, id, result, request, supported) }
        } finally { synchronized(LOCK) { IN_FLIGHT -= key(id, name); bump(id, name) } }
    }
    private class Request(val options: Bundle, val ownerRetry: Boolean, val ownerAccount: Boolean, val done: Boolean? = null, val retryGen: Int = 0)
    /** What to do with [current]: null when nothing is left to do, a [Request] to send, or one whose [Request.done] is the answer. */
    private fun decide(name: String, id: String, current: Bundle): Request? {
        if (name !in pending(id)) return null
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
        } else if (ownerRetry && current.getString("status") == "awaiting_install" && !control(7, Bundle()).getBoolean("owner_approved_retry")) {
            ownerReview(current, "installation_confirmation_required") // Legacy Aurora: use its owner-facing install UI.
        } else if (needsEnqueue(name, current, id) || (ownerRetry && current.getString("status") == "awaiting_install")) {
            // Consume this single owner approval atomically before IPC. A lost reply must
            // return to review; the captured ownerRetry value applies only to this request.
            check(prefs.edit().putBoolean("$id:$name:uncertain", true)
                .putBoolean("$id:$name:owner_retry", false).commit())
            val ownerAccount = prefs.getBoolean("$id:$name:owner_account", false)
            return Request(options(id).apply {
                // The Google account gets whatever version Play offers it; the reply's version_code becomes the floor.
                putLong("minimum_version_code", if (ownerAccount) 0 else expectedVersion(id, name))
                putBoolean("owner_approved_retry", ownerRetry)
                putBoolean("owner_account_approved", ownerAccount)
                // summary() queues a download Play refuses the anonymous session once more for the Google account.
                putBoolean("owner_retry_on_refusal", !ownerAccount)
            }, ownerRetry, ownerAccount, retryGen = prefs.getInt("$id:$name:retry_gen", 0))
        } else current
        return finished(record(name, id, result, requestedInstall = false, ownerRetry = ownerRetry))
    }
    /** The bookkeeping after Aurora's answer to an install request ([dispatch]). */
    private fun settle(name: String, id: String, answer: Bundle, request: Request, supported: Boolean): Boolean {
        // The owner tapped Retry again while this request was with Aurora: the newer approval stays pending, untouched.
        if (prefs.getInt("$id:$name:retry_gen", 0) != request.retryGen) return false
        var result = answer
        val ownerRetry = request.ownerRetry
        if (result.getString("error") == "store_unavailable") return false
        if (prefs.getBoolean("$id:$name:owner_account", false) && result.getLong("version_code") > 0) {
            check(prefs.edit().putLong("$id:$name:ownerVersion", result.getLong("version_code")).commit())
        }
        if (result.getString("error") in ANONYMOUS_FAILURES &&
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
        save(name, result, dispatched = !retainTarget, id = id)
        if (retainTarget) check(prefs.edit().putBoolean("$id:$name:acknowledged", true).putBoolean("$id:$name:uncertain", false).commit())
        changed(id)
        return !retainTarget
    }
    fun bridge(operation: Int, name: String, options: Bundle? = null): Bundle = try {
        storeCall?.let { sentOptions?.invoke(operation, options); it(operation, name) }
            ?: AuroraMigrationClient(context).use { it.call(operation, name, options) }
    } catch (_: Exception) { unavailable(name, "store_unavailable") }
    fun control(operation: Int, options: Bundle = options(session())): Bundle = try {
        controlCall?.invoke(operation, options) ?: AuroraMigrationClient(context).use { it.control(operation, options) }
    } catch (_: Exception) { Bundle().apply { putBoolean("success", false); putBoolean("recoverable", true); putString("error", "store_unavailable") } }
    fun changed(id: String) { context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName).putExtra("session_id", id)) }
    private fun schedule() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        check(scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, MigrationInstallJob::class.java))
            .setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS)
    }
    companion object {
        const val ACTION_CHANGED = "org.microg.vending.migration.CHANGED"
        internal val LOCK = Any()
        /** Apps whose install request is with Aurora now ("session:package"); in-process, like the job and the service. */
        private val IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        /** Per app, how often its records changed in this process: a status asked of Aurora before a change is stale. */
        private val REVISIONS = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private fun key(id: String, name: String) = "$id:$name"
        private fun revision(id: String, name: String) = REVISIONS[key(id, name)] ?: 0L
        private fun bump(id: String, name: String) { REVISIONS.merge(key(id, name), 1L, Long::plus) }
        private const val JOB = 700714
        private const val RATE_LIMITED = "rate_limited"
        private const val MAX_RATE_LIMITED = 3
        /** How often a long pass checks its session's results (and queues refused apps for the Google account). */
        internal const val SUMMARY_EVERY_MS = 60_000L
        /** The anonymous session's own limits: Play throttling it, not listing the app in its country, or no session. */
        private val ANONYMOUS_FAILURES = setOf(RATE_LIMITED, "not_found", "anonymous_sign_in_required", "not_signed_in", "session_expired")
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
    private var stopped = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val cancelled = AtomicBoolean(false).also { stopped = it }
        executor.execute {
            val queue = MigrationInstallQueue(this)
            var retry = false
            try {
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(8)
                do {
                    retry = false
                    if (!queue.dispatchRound(cancelled = { cancelled.get() })) retry = true
                    if (cancelled.get()) return@execute
                    for (id in queue.sessions()) {
                        // The job's own loop goes on by itself: scheduling the same job here would stop it.
                        val state = queue.summary(id, reschedule = false)
                        queue.changed(id)
                        MigrationNotifications.selectionOpen(this, id, state)
                        if (state.getBoolean("complete")) {
                            queue.releaseCompletedPause(id)
                            MigrationNotifications.completed(this, id, state)
                        } else if (queue.selected(id).isNotEmpty()) {
                            // Settled chunks do not prove the selection closed. Preserve the lease and
                            // receipts without polling forever; the next chunk/explicit close schedules work.
                            if (!state.getBoolean("installs_settled")) retry = true
                            queue.renewPause(id)
                        }
                    }
                    MigrationSetupReceivers.publish(queue)
                    if (retry && !cancelled.get()) Thread.sleep(if (queue.throttled) 30_000 else 2000)
                } while (retry && !cancelled.get() && System.nanoTime() < deadline)
            } catch (_: Exception) { retry = true }
            if (!cancelled.get()) runCatching { jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped.set(true); return true }
    override fun onDestroy() { stopped.set(true); executor.shutdownNow(); super.onDestroy() }
}
