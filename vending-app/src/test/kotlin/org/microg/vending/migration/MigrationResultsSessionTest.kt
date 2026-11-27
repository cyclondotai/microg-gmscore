/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.app.job.JobScheduler
import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CYC-3525: the Transferred apps screen opened on an empty transfer and said "No apps were selected" while Aurora was
 * asked about every app. Switch's Pause left Transfer Phone's hand-off session behind empty; the screen now reads the
 * saved receipt first and skips empty transfers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationResultsSessionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val run = "00000000-0000-0000-0000-000000003525"

    @Before fun clear() {
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancelAll()
    }

    private fun doc(name: String, title: String? = null) = Bundle().apply {
        putString("package_name", name); putLong("version_code", 12)
        title?.let { putString("title", it) }
    }
    private fun state(name: String, status: String, installed: Boolean = false) = Bundle().apply {
        putString("package_name", name); putString("status", status); putLong("version_code", 12)
        putBoolean("installed", installed); putBoolean("package_present", installed)
        putLong("installed_version_code", if (installed) 12 else 0)
    }
    private fun rows(summary: Bundle) = summary.getParcelableArray("cyclon_app_results")!!.map { it as Bundle }

    // ---------------------------------------------------------------- Switch's Pause reuses the empty hand-off session

    @Test fun pauseReusesTheHandoffSessionTransferPhoneJustBeganInsteadOfStrandingIt() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        val begun = queue.currentSessionId()!!
        assertEquals(begun, queue.startPausedTransfer())
        assertEquals(listOf(begun), queue.sessions())
        assertEquals(begun, queue.catalogSession())
        queue.select(listOf(doc("org.example.app")))
        assertEquals(begun, queue.handoff(run).getString("session_id"))
        assertEquals(listOf("org.example.app"), queue.handoff(run).getStringArrayList("packages"))
        // A second Pause after selection keeps the same session too.
        assertEquals(begun, queue.startPausedTransfer())
        assertEquals(listOf(begun), queue.sessions())
    }

    @Test fun pauseReuseKeepsADeferredCopyDeferredAndRenewsItsCap() {
        var now = 1_000L
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }.apply { clock = { now } }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val begun = queue.currentSessionId()!!
        now = 61_000L
        assertEquals(begun, queue.startPausedTransfer())
        assertTrue(queue.isBulkDeferred(begun, now))
        val prefs = context.getSharedPreferences("migration-installs", 0)
        assertEquals(61_000L, prefs.getLong("$begun:defer_bulk_at", 0))
    }

    @Test fun pauseStillStartsANewTransferWhenTheHandoffSessionAlreadyListsLeftOutApps() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        val begun = queue.catalogSession()
        queue.recordCatalog(listOf(MigrationInstallQueue.unavailable("org.example.gone", "not_found")))
        val paused = queue.startPausedTransfer()
        assertNotEquals(begun, paused)
        assertEquals(paused, queue.handoff(run).getString("session_id"))
    }

    @Test fun pauseWithoutAHandoffStillStartsANewTransferEachTime() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        val first = queue.startPausedTransfer()
        assertNotEquals(first, queue.startPausedTransfer())
    }

    @Test fun pauseAfterTheHandoffEndedStartsANewTransfer() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        val begun = queue.currentSessionId()!!
        queue.endHandoff(run, "cancelled")
        assertNotEquals(begun, queue.startPausedTransfer())
    }

    // ---------------------------------------------------------------- which transfer the screen opens

    @Test fun resultsOpenOnTheLatestTransferWithAppsAndHideEmptyOnes() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        val real = queue.beginTransfer()
        queue.select(listOf(doc("org.example.app")))
        queue.finishSelection(real)
        val empty = queue.beginTransfer()
        assertEquals(empty, queue.currentSessionId())
        assertFalse(queue.hasApps(empty))
        assertTrue(queue.hasApps(real))
        assertEquals(real, queue.resultsSession())
        assertEquals(listOf(real), queue.visibleSessions())
        assertEquals(1, queue.appCount(real))
        // An explicit request (a notification for that transfer) is honoured and listed.
        assertEquals(empty, queue.resultsSession(empty))
        assertEquals(listOf(empty, real), queue.visibleSessions(empty))
        // An unknown request falls back to the default pick.
        assertEquals(real, queue.resultsSession("not-a-transfer"))
    }

    @Test fun aTransferWithOnlyLeftOutAppsStillCountsAsHavingApps() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        val id = queue.beginTransfer()
        queue.recordCatalog(listOf(MigrationInstallQueue.unavailable("org.example.gone", "not_found")))
        assertTrue(queue.hasApps(id))
        assertEquals(1, queue.appCount(id))
        assertEquals(id, queue.resultsSession())
    }

    @Test fun withNoTransferWithAppsTheCurrentOneOpens() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertNull(queue.resultsSession())
        val id = queue.beginTransfer()
        assertEquals(id, queue.resultsSession())
        assertEquals(emptyList<String>(), queue.visibleSessions())
        assertEquals(listOf(id), queue.visibleSessions(id))
    }

    // ---------------------------------------------------------------- the saved receipt

    @Test fun cachedSummaryNeverAsksAuroraAndMatchesTheLiveReceiptOnceSaved() {
        var asked = 0
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 3) asked++
            when (name) {
                "org.example.done" -> state(name, "installed", installed = true)
                "org.example.failed" -> state(name, "failed").apply { putString("error", "not_found") }
                else -> state(name, "downloading")
            }
        }
        val id = queue.beginTransfer()
        queue.select(listOf(doc("org.example.done", "Done"), doc("org.example.failed", "Failed"), doc("org.example.busy", "Busy")))
        queue.recordCatalog(listOf(MigrationInstallQueue.unavailable("org.example.gone", "not_found").apply { putString("title", "Gone") },
            MigrationInstallQueue.unavailable("org.example.late", "lookup_timeout").apply { putBoolean("recoverable", true) }))
        listOf("org.example.done", "org.example.failed", "org.example.busy").forEach { queue.dispatch(it, id) }

        val live = queue.summary(id, reschedule = false)
        asked = 0
        val cached = queue.cachedSummary(id)
        assertEquals(0, asked)
        assertEquals(id, cached.getString("session_id"))
        for (key in listOf("transfer_outcome", "cyclon_hold_state")) assertEquals(key, live.getString(key), cached.getString(key))
        for (key in listOf("all_installed", "selection_closed", "installs_settled", "bulk_deferred", "has_active_work", "complete")) {
            assertEquals(key, live.getBoolean(key), cached.getBoolean(key))
        }
        assertNotNull(cached.getBundle("setup_options"))
        val liveRows = rows(live).associateBy { it.getString("package_name") }
        val cachedRows = rows(cached)
        assertEquals(liveRows.keys.toList(), cachedRows.map { it.getString("package_name") })
        for (row in cachedRows) {
            val name = row.getString("package_name")!!
            val expected = liveRows.getValue(name)
            for (key in listOf("status", "title")) assertEquals("$name $key", expected.getString(key), row.getString(key))
            for (key in listOf("installed", "terminal", "excluded")) assertEquals("$name $key", expected.getBoolean(key), row.getBoolean(key))
            for (key in listOf("icon_url", "developer_name", "error", "errorCode")) assertTrue("$name $key", row.containsKey(key))
            assertTrue(row.containsKey("recoverable") && row.containsKey("install_size"))
        }
        assertTrue(cachedRows.single { it.getString("package_name") == "org.example.late" }.getBoolean("recoverable"))
        assertFalse(cachedRows.single { it.getString("package_name") == "org.example.gone" }.getBoolean("recoverable"))
        assertEquals("unavailable", cachedRows.single { it.getString("package_name") == "org.example.gone" }.getString("status"))
    }

    @Test fun cachedSummaryShowsARetriedAppAsWaitingNotAsItsPreviousFailure() {
        var failing = true
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2 || !failing) state(name, "queued") else state(name, "failed").apply { putString("error", "not_signed_in") }
        }
        val id = queue.beginTransfer()
        queue.select(listOf(doc("org.example.app")))
        queue.dispatch("org.example.app", id)
        queue.summary(id, reschedule = false)
        val failed = rows(queue.cachedSummary(id)).single()
        assertTrue(failed.getBoolean("terminal"))
        assertEquals("failed", failed.getString("status"))
        assertEquals(MigrationResultText.retryOutcome(listOf(failed.apply { putString("package_name", "org.example.app") }))!!
            .startsWith("1 app still couldn't be installed"), true)

        failing = false
        queue.retry(id, "org.example.app")
        val retried = rows(queue.cachedSummary(id)).single()
        assertEquals("pending", retried.getString("status"))
        assertFalse(retried.getBoolean("terminal"))
        assertNull(retried.getString("error"))
        assertNull(MigrationResultText.retryOutcome(listOf(retried)))
        assertTrue(queue.cachedSummary(id).getBoolean("has_active_work"))
    }

    @Test fun cachedSummaryOfAnUnknownTransferIsEmpty() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        val receipt = queue.cachedSummary("missing")
        assertTrue(rows(receipt).isEmpty())
        assertFalse(receipt.getBoolean("selection_closed"))
        assertFalse(receipt.getBoolean("has_active_work"))
    }

    @Test fun handoffListsIconUrlsBesideTitles() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        queue.startPausedTransfer()
        queue.select(listOf(doc("org.example.a", "A").apply { putString("icon_url", "https://example.org/a.png") }, doc("org.example.b", "B")))
        val receipt = queue.handoff(run)
        assertEquals(listOf("org.example.a", "org.example.b"), receipt.getStringArrayList("packages"))
        assertEquals(listOf("https://example.org/a.png", ""), receipt.getStringArrayList("icon_urls"))
    }

    // ---------------------------------------------------------------- the screen's sections

    private fun row(name: String, title: String? = null, status: String? = null, error: String? = null, installed: Boolean = false,
                    terminal: Boolean = true, excluded: Boolean = false, recoverable: Boolean = false) = Bundle().apply {
        putString("package_name", name); putString("title", title); putString("status", status); putString("error", error)
        putBoolean("installed", installed); putBoolean("terminal", terminal); putBoolean("excluded", excluded)
        putBoolean("recoverable", recoverable)
    }

    @Test fun rowsGroupIntoSectionsSortedByDisplayNameNotPackage() {
        val rows = listOf(
            row("com.zeta.app", "alpha", "installed", installed = true),
            row("com.alpha.app", "Zulu", "installed", installed = true),
            row("org.mid.app", "Mike", "installed", installed = true),
            row("org.nameless", null, "installed", installed = true),
            row("com.busy", "Busy", "downloading", terminal = false),
            row("com.wait", "Another", "queued", terminal = false),
            row("com.broke", "Broken", "failed", "not_found"),
            row("com.owner", "Approve me", "owner_action_required", "installation_confirmation_required"),
            row("com.full", "Full", "failed", "insufficient_storage"),
            row("com.late", "Late", "unavailable", "lookup_timeout", excluded = true, recoverable = true),
            row("com.retry.listed", "Listed retry", "unavailable", "store_unavailable", excluded = true),
            row("com.gone", "Gone", "unavailable", "not_found", excluded = true),
            row("com.paid", "Paid", "unavailable", "paid", excluded = true),
        )
        val labels = mapOf("org.nameless" to "Nameless")
        val groups = MigrationResultGroups.group(rows, retryable = setOf("com.retry.listed")) { MigrationResultGroups.label(it, labels) }
        fun names(section: MigrationResultGroups.Section) = groups[section].orEmpty().map { MigrationResultGroups.label(it, labels) }
        assertEquals(listOf("alpha", "Mike", "Nameless", "Zulu"), names(MigrationResultGroups.Section.INSTALLED))
        assertEquals(listOf("Another", "Busy"), names(MigrationResultGroups.Section.INSTALLING))
        assertEquals(listOf("Approve me", "Broken", "Full", "Late", "Listed retry"), names(MigrationResultGroups.Section.ATTENTION))
        assertEquals(listOf("Gone", "Paid"), names(MigrationResultGroups.Section.NOT_ON_PLAY))

        val counts = MigrationResultGroups.counts(groups)
        assertEquals(MigrationResultGroups.Counts(installed = 4, installing = 2, attention = 5, notOnPlay = 2), counts)
        assertEquals(11, counts.total)
        assertEquals(MigrationResultGroups.Reasons(storage = 1, owner = 1, other = 3), MigrationResultGroups.reasons(groups))
        assertEquals("4 of 11 installed", MigrationResultGroups.headline(counts, loading = false))
        assertTrue(MigrationResultGroups.listedSomewhere(groups.getValue(MigrationResultGroups.Section.NOT_ON_PLAY)[1]))
        assertFalse(MigrationResultGroups.listedSomewhere(groups.getValue(MigrationResultGroups.Section.NOT_ON_PLAY)[0]))
    }

    @Test fun packageNamesAreNeverShownAsTitlesWhenABetterNameIsKnown() {
        assertNull(MigrationResultGroups.realTitle("com.example.app", "com.example.app"))
        assertNull(MigrationResultGroups.realTitle("org.other.thing", "com.example.app"))
        assertNull(MigrationResultGroups.realTitle("  ", "com.example.app"))
        assertEquals("Signal", MigrationResultGroups.realTitle(" Signal ", "org.thoughtcrime.securesms"))
        assertEquals("Launcher Label", MigrationResultGroups.label(row("com.example.app", "com.example.app"), mapOf("com.example.app" to "Launcher Label")))
        assertEquals("com.example.app", MigrationResultGroups.label(row("com.example.app"), emptyMap()))
        assertEquals("S", MigrationResultGroups.initial("signal"))
        assertEquals("9", MigrationResultGroups.initial(" 9GAG"))
    }

    @Test fun anAppStillBeingRetriedReadsAsWaitingWhileSettledFailuresKeepTheirReason() {
        val retried = row("com.retry", "Retry", "failed", "not_signed_in", terminal = false)
        val shown = MigrationResultGroups.normalize(retried)
        assertEquals("pending", shown.getString("status"))
        assertNull(shown.getString("error"))
        assertEquals("Waiting for App Store", MigrationResultText.description(shown))
        assertEquals("failed", retried.getString("status"))
        val settled = row("com.failed", "Failed", "failed", "not_found")
        assertSame(settled, MigrationResultGroups.normalize(settled))
        assertTrue(MigrationResultGroups.active(row("com.busy", status = "downloading", terminal = false)))
        assertFalse(MigrationResultGroups.active(row("com.wait", status = "queued", terminal = false)))
    }

    @Test fun headlinesNeverClaimAnEmptyTransferWhileLoading() {
        val none = MigrationResultGroups.Counts(0, 0, 0, 0)
        assertEquals("Checking your apps", MigrationResultGroups.headline(none, loading = true))
        assertNull(MigrationResultGroups.subline(none, loading = true))
        assertEquals("No apps in this transfer", MigrationResultGroups.headline(none, loading = false))
        assertEquals("No apps were selected for this transfer.", MigrationResultGroups.subline(none, loading = false))
        assertEquals("All 15 apps installed", MigrationResultGroups.headline(MigrationResultGroups.Counts(15, 0, 0, 2), loading = false))
        assertEquals("Your app is installed", MigrationResultGroups.headline(MigrationResultGroups.Counts(1, 0, 0, 0), loading = false))
        assertEquals("12 of 15 installed", MigrationResultGroups.headline(MigrationResultGroups.Counts(12, 2, 1, 0), loading = false))
        assertEquals("These apps aren’t on Google Play", MigrationResultGroups.headline(MigrationResultGroups.Counts(0, 0, 0, 3), loading = false))
        assertTrue(MigrationResultGroups.subline(MigrationResultGroups.Counts(12, 2, 1, 0), loading = false)!!.startsWith("2 still installing"))
        assertEquals("3 apps need your attention.", MigrationResultGroups.subline(MigrationResultGroups.Counts(12, 0, 3, 0), loading = false))
    }
}
