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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** CYC-2499 (11-17 founders-02): Setup's sign-in apps and Google-account retries go to Aurora before the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationPriorityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val run = "0f8fad5b-d9cb-469f-a165-70867728950e"
    @Before fun clear() {
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancelAll()
    }
    private fun doc(name: String) = Bundle().apply { putString("package_name", name); putLong("version_code", 12) }
    private fun state(name: String, status: String) = Bundle().apply { putString("package_name", name); putString("status", status); putLong("version_code", 12) }

    @Test fun setupsSignInAppsGoFirstAndAPriorityMidRoundGoesNext() {
        val handed = mutableListOf<String>()
        lateinit var queue: MigrationInstallQueue
        queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) {
                handed += name
                // Setup recognizes Duo while the round runs: it goes next, not after the round.
                if (handed.size == 2) assertEquals(1, queue.prioritize(run, listOf("com.duosecurity.duomobile")).getInt("prioritized"))
                state(name, "queued")
            } else state(name, "none")
        }
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf("ai.x.grok", "app.cashbackplus", "ch.sbb.mobile.android.b2c", "com.duosecurity.duomobile",
            "com.google.android.apps.authenticator2", "org.example.app").map(::doc))
        val first = queue.prioritize(run, listOf("com.google.android.apps.authenticator2", "not a package"))
        assertTrue(first.getBoolean("accepted")); assertEquals(1, first.getInt("prioritized"))
        queue.dispatchRound()
        assertEquals(listOf("com.google.android.apps.authenticator2", "ai.x.grok", "com.duosecurity.duomobile", "app.cashbackplus",
            "ch.sbb.mobile.android.b2c", "org.example.app"), handed)
    }

    @Test fun anotherRunsPriorityIsRefused() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf(doc("org.example.app")))
        val other = queue.prioritize("1b4e28ba-2fa1-11d2-883f-0016d3cca427", listOf("org.example.app"))
        assertTrue(other.getBoolean("supported")); assertFalse(other.getBoolean("accepted"))
    }

    @Test fun aRefusalQueuedForTheGoogleAccountGoesNextInTheSameRound() {
        val handed = mutableListOf<String>()
        var clock = 0L
        val queue = MigrationInstallQueue(context, controlCall = { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", true) } }) { operation, name ->
            when {
                operation == 2 -> { handed += name; clock += 40_000; state(name, "queued") }
                name == "a.refused.app" && handed.contains(name) -> state(name, "failed").apply { putString("errorCode", "app_not_purchased") }
                else -> state(name, "none")
            }
        }
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf("a.refused.app", "b.example.app", "c.example.app", "d.example.app", "e.example.app").map(::doc))
        queue.dispatchRound(clock = { clock })
        // Refused at its first hand-off; the minute's check queues it for the Google account, and it goes right after.
        assertEquals("a.refused.app", handed[0])
        assertEquals(2, handed.count { it == "a.refused.app" })
        assertTrue("retried before the round's end", handed.lastIndexOf("a.refused.app") < handed.size - 1)
    }

    /** 11-17 founders-02: Aurora answered the install request itself with Play's refusal (Stu, SamTrans, C&A). */
    @Test fun aRefusalInTheInstallReplyGoesToTheGoogleAccountInTheSameRound() {
        val requests = mutableListOf<Pair<String, Boolean>>()
        var options: Bundle? = null
        val queue = MigrationInstallQueue(context, controlCall = { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", true) } }) { operation, name ->
            if (operation != 2) return@MigrationInstallQueue state(name, "none")
            val owner = options?.getBoolean("owner_account_approved") == true
            requests += name to owner
            if (name == "ch.stu" && !owner) state(name, "failed").apply { putString("error", "failed"); putString("errorCode", "app_not_purchased") }
            else state(name, "queued")
        }.apply { sentOptions = { operation, sent -> if (operation == 2) options = sent } }
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf("ch.stu", "x.example.one", "y.example.two").map(::doc))
        queue.dispatchRound()
        assertEquals(listOf("ch.stu" to false, "ch.stu" to true, "x.example.one" to false, "y.example.two" to false), requests)
        assertTrue(queue.pending().isEmpty())
    }

    /** Refusals saved as failed by an earlier FakeStore are queued for the Google account by the next summary. */
    @Test fun aRefusalAlreadySavedAsFailedIsQueuedForTheGoogleAccount() {
        var refused = true
        val queue = MigrationInstallQueue(context, controlCall = { _, _ -> Bundle().apply { putBoolean("owner_account_fallback", true) } }) { _, name ->
            if (refused) state(name, "failed").apply { putString("errorCode", "app_not_purchased") } else state(name, "queued")
        }
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf(doc("ch.stu")))
        val id = queue.currentSessionId()!!
        queue.save("ch.stu", state("ch.stu", "failed").apply { putString("errorCode", "app_not_purchased"); putBoolean("terminal", true) }, dispatched = true, id = id)
        assertTrue(queue.pending(id).isEmpty())
        queue.summary(id, reschedule = false)
        assertEquals(setOf("ch.stu"), queue.pending(id))
        refused = false
        queue.dispatchRound()
        assertTrue(queue.pending(id).isEmpty())
    }

    /** 11-18 founders-02: the default-priority job was held back at thermal status 2; the expedited one is not. */
    @Test fun theInstallJobIsExpedited() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf(doc("org.example.app")))
        val scheduler = context.getSystemService(android.content.Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val job = scheduler.allPendingJobs.single()
        assertTrue(job.isExpedited)
        assertTrue(job.isPersisted)
    }

    /** Review of #101: out of expedited quota Android refuses a new expedited job; the same job goes in, not expedited. */
    @Test fun anInstallJobAndroidRefusesToExpediteIsScheduledAsARegularOne() {
        val tried = mutableListOf<Boolean>()
        val builder = android.app.job.JobInfo.Builder(700714, android.content.ComponentName(context, MigrationInstallJob::class.java)).setPersisted(true)
        val ok = MigrationInstallQueue.scheduleInstallJob(builder) { job ->
            tried += job.isExpedited
            if (job.isExpedited) android.app.job.JobScheduler.RESULT_FAILURE else android.app.job.JobScheduler.RESULT_SUCCESS
        }
        assertTrue(ok)
        assertEquals(listOf(true, false), tried)
    }

    /**
     * CYC-2643: 30 apps where the first 10 queued are non-sign-in apps (a.app01..a.app10; a.app01 actively downloading,
     * a.app02..a.app10 queued) and sign-in apps are later alphabetically (z.onepassword, y.authenticator, x.mail).
     * When priority arrives after 10 apps are already queued, the prioritized apps dispatch next in the exact requested
     * order (z.onepassword, y.authenticator, x.mail — NOT alphabetical x, y, z) and Aurora receives the priority list.
     */
    @Test fun cyc2643ThirtyAppsPriorityAfterTenQueuedDispatchesRequestedOrderImmediatelyNext() {
        val apps = (1..27).map { "a.example.app%02d".format(it) } + listOf(
            "x.example.mail",
            "y.example.authenticator",
            "z.example.onepassword",
        )
        assertEquals(30, apps.size)
        // Deliberately reverse-alphabetical priority order: password manager -> authenticator -> mail.
        val requestedOrder = listOf(
            "z.example.onepassword",
            "y.example.authenticator",
            "x.example.mail",
        )
        val handed = mutableListOf<String>()
        val priorityOptions = mutableListOf<Triple<String, Boolean, Int>>()
        var pendingOptions: Bundle? = null
        val storePriorities = mutableListOf<List<String>>()
        lateinit var queue: MigrationInstallQueue
        queue = MigrationInstallQueue(
            context,
            controlCall = { operation, options ->
                when (operation) {
                    7 -> Bundle().apply { putBoolean("prioritize_downloads", true) }
                    8 -> {
                        storePriorities += options.getStringArrayList("packages").orEmpty()
                        Bundle().apply { putBoolean("success", true) }
                    }
                    else -> Bundle()
                }
            },
            storeCall = { operation, name ->
                if (operation == 2) {
                    handed += name
                    pendingOptions?.let { opts ->
                        if (opts.getBoolean("priority", false)) {
                            priorityOptions += Triple(name, true, opts.getInt("priority_rank", -1))
                        }
                    }
                    val status = if (handed.size == 1) "downloading" else "queued"
                    if (handed.size == 10) {
                        val reply = queue.prioritize(run, requestedOrder)
                        assertTrue(reply.getBoolean("accepted"))
                        assertEquals(3, reply.getInt("prioritized"))
                    }
                    state(name, status)
                } else state(name, "none")
            },
        ).apply {
            sentOptions = { operation, options -> if (operation == 2) pendingOptions = options?.let(::Bundle) }
        }

        queue.beginHandoff(run, resumeOnly = false)
        queue.select(apps.map(::doc))
        queue.dispatchRound()

        assertEquals(30, handed.size)
        // Apps 1..10 were queued before priority arrived.
        assertEquals((1..10).map { "a.example.app%02d".format(it) }, handed.subList(0, 10))
        // Immediately next (indices 10, 11, 12): the 3 prioritized apps in exact requested priority order!
        assertEquals(requestedOrder, handed.subList(10, 13))
        // Remaining 17 non-priority apps follow after.
        assertEquals((11..27).map { "a.example.app%02d".format(it) }, handed.subList(13, 30))
        // Each prioritized install request carried its priority flag and rank, and Aurora received the ordered list.
        assertEquals(
            listOf(
                Triple("z.example.onepassword", true, 0),
                Triple("y.example.authenticator", true, 1),
                Triple("x.example.mail", true, 2),
            ),
            priorityOptions,
        )
        assertEquals(listOf(requestedOrder), storePriorities)
    }

    /** CYC-2643: an actively downloading app is not counted as re-queued, while an already-queued app is prioritized. */
    @Test fun activeDownloadingAppIsNotDisturbedWhileAlreadyQueuedAppIsPrioritized() {
        val storePriorities = mutableListOf<List<String>>()
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, options ->
                when (operation) {
                    7 -> Bundle().apply { putBoolean("prioritize_downloads", true) }
                    8 -> {
                        storePriorities += options.getStringArrayList("packages").orEmpty()
                        Bundle().apply { putBoolean("success", true) }
                    }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "queued") },
        )
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf("a.active.app", "b.queued.app", "c.pending.app").map(::doc))
        val id = queue.currentSessionId()!!
        // a.active.app is actively downloading; b.queued.app is queued in Aurora; c.pending.app is still pending.
        queue.save("a.active.app", state("a.active.app", "downloading"), dispatched = true, id = id)
        queue.save("b.queued.app", state("b.queued.app", "queued"), dispatched = true, id = id)

        val reply = queue.prioritize(run, listOf("b.queued.app", "c.pending.app", "a.active.app"))
        assertTrue(reply.getBoolean("accepted"))
        // b.queued.app (queued, not active) + c.pending.app (pending) count; a.active.app (downloading) does not.
        assertEquals(2, reply.getInt("prioritized"))

        queue.dispatchRound()
        assertEquals(listOf(listOf("b.queued.app", "c.pending.app", "a.active.app")), storePriorities)
    }

    /** CYC-2643: when Aurora does not advertise prioritize_downloads (op 8), prioritize counts only pending apps. */
    @Test fun prioritizeCountsOnlyPendingAppsWhenStoreLacksBinderPrioritizeOp() {
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, _ ->
                if (operation == 7) Bundle().apply { putBoolean("prioritize_downloads", false) } else Bundle()
            },
            storeCall = { _, name -> state(name, "queued") },
        )
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf("b.queued.app", "c.pending.app").map(::doc))
        val id = queue.currentSessionId()!!
        queue.save("b.queued.app", state("b.queued.app", "queued"), dispatched = true, id = id)

        val reply = queue.prioritize(run, listOf("b.queued.app", "c.pending.app"))
        assertTrue(reply.getBoolean("accepted"))
        assertFalse(reply.getBoolean("store_prioritize_supported"))
        assertEquals(1, reply.getInt("prioritized"))
    }

    /** CYC-2643: failed op 8 reorder (success=false) leaves priority_dirty set so the next round retries it. */
    @Test fun failedStorePriorityReorderStaysDirtyAndRetriesOnNextRound() {
        var allowSuccess = false
        var attempts = 0
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, _ ->
                when (operation) {
                    7 -> Bundle().apply { putBoolean("prioritize_downloads", true) }
                    8 -> {
                        attempts++
                        Bundle().apply {
                            putBoolean("success", allowSuccess)
                            if (!allowSuccess) putString("error", "busy")
                        }
                    }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "queued") },
        )
        queue.beginHandoff(run, resumeOnly = false)
        queue.select(listOf("b.queued.app", "c.pending.app").map(::doc))
        queue.prioritize(run, listOf("c.pending.app", "b.queued.app"))

        queue.dispatchRound()
        assertTrue("first round should attempt op 8 and fail", attempts >= 1)
        val afterFirst = attempts
        allowSuccess = true
        queue.dispatchRound()
        assertEquals("second round must retry op 8 once and succeed", afterFirst + 1, attempts)
        val afterSecond = attempts
        queue.dispatchRound()
        assertEquals("once op 8 returns success=true, priority_dirty clears and stops retrying", afterSecond, attempts)
    }

    @Test fun deferBulkHoldsUnprioritizedAppsUntilReleasedThenDispatchesSmallestFirst() {
        val handed = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
        }
        fun sizedDoc(name: String, size: Long) = doc(name).apply { if (size > 0L) putLong("install_size", size) }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        queue.select(listOf(
            sizedDoc("com.big.app", 500_000_000L),
            sizedDoc("com.medium.app", 150_000_000L),
            sizedDoc("com.small.app", 20_000_000L),
            sizedDoc("com.unknown.app", 0L),
            sizedDoc("com.signin.one", 90_000_000L),
            sizedDoc("com.signin.two", 110_000_000L),
        ))

        // Full roster + install_sizes + bulk_deferred visible immediately in handoff().
        val receipt = queue.handoff(run)
        assertTrue(receipt.getBoolean("bulk_deferred"))
        assertEquals(6, receipt.getInt("total"))
        val pkgs = receipt.getStringArrayList("packages")!!
        val sizes = receipt.getLongArray("install_sizes")!!
        assertEquals(pkgs.size, sizes.size)
        assertEquals(500_000_000L, sizes[pkgs.indexOf("com.big.app")])
        assertEquals(0L, sizes[pkgs.indexOf("com.unknown.app")])

        // Nothing dispatches while gated and unprioritized; summary reports no active work.
        assertTrue(queue.dispatchOrder().isEmpty())
        queue.dispatchRound()
        assertTrue(handed.isEmpty())
        val idleSummary = queue.summary()
        assertTrue(idleSummary.getBoolean("bulk_deferred"))
        assertFalse(idleSummary.getBoolean("has_active_work"))
        assertFalse(idleSummary.getBoolean("complete"))

        // Sliding window: first prioritize signin.one, then signin.two — only those dispatch.
        assertEquals(1, queue.prioritize(run, listOf("com.signin.one")).getInt("prioritized"))
        queue.dispatchRound()
        assertEquals(listOf("com.signin.one"), handed)
        assertEquals(1, queue.prioritize(run, listOf("com.signin.two")).getInt("prioritized"))
        queue.dispatchRound()
        assertEquals(listOf("com.signin.one", "com.signin.two"), handed)

        // Release bulk: remaining apps dispatch smallest-first, with unknown size last.
        val released = queue.releaseBulkHandoff(run)
        assertTrue(released.getBoolean("supported"))
        assertTrue(released.getBoolean("accepted"))
        assertFalse(released.getBoolean("bulk_deferred"))
        assertFalse(queue.handoff(run).getBoolean("bulk_deferred"))

        queue.dispatchRound()
        assertEquals(
            listOf("com.signin.one", "com.signin.two", "com.small.app", "com.medium.app", "com.big.app", "com.unknown.app"),
            handed,
        )
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun endHandoffAutomaticallyReleasesDeferredBulkAppsSmallestFirst() {
        val handed = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
        }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        queue.select(listOf(
            doc("com.large.game").apply { putLong("install_size", 400_000_000L) },
            doc("com.tiny.util").apply { putLong("install_size", 5_000_000L) },
        ))
        assertTrue(queue.dispatchOrder().isEmpty())

        val ended = queue.endHandoff(run, "success")
        assertTrue(ended.getBoolean("accepted"))
        assertFalse(ended.getBoolean("bulk_deferred"))

        queue.dispatchRound()
        assertEquals(listOf("com.tiny.util", "com.large.game"), handed)
    }

    /** R1 + R4: Switch Pause (op 13) rebinds handoff_session A -> B without dropping defer_bulk or rolling-window order. */
    @Test fun r1DeferBulkSurvivesSwitchPauseRebindAndRollingWindowOrder() {
        val handed = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
        }
        fun sizedDoc(name: String, size: Long) = doc(name).apply { if (size > 0L) putLong("install_size", size) }

        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionA = queue.currentSessionId()!!
        val sessionB = queue.startPausedTransfer()
        assertNotEquals(sessionA, sessionB)
        assertEquals(sessionB, queue.catalogSession())

        queue.select(listOf(
            sizedDoc("com.big.app", 500_000_000L),
            sizedDoc("com.medium.app", 150_000_000L),
            sizedDoc("com.small.app", 20_000_000L),
            sizedDoc("com.unknown.app", 0L),
            sizedDoc("com.signin.a", 80_000_000L),
            sizedDoc("com.signin.b", 70_000_000L),
            sizedDoc("com.signin.c", 60_000_000L),
            sizedDoc("com.signin.d", 50_000_000L),
            sizedDoc("com.signin.e", 40_000_000L),
            sizedDoc("com.signin.f", 30_000_000L),
        ))

        val receipt = queue.handoff(run)
        assertEquals(sessionB, receipt.getString("session_id"))
        assertTrue(receipt.getBoolean("bulk_deferred"))
        assertTrue(queue.isBulkDeferred(sessionB))
        assertFalse(queue.isBulkDeferred(sessionA))
        assertTrue(queue.dispatchOrder().isEmpty())

        // Rolling ≤5 lookahead: admit [a,b,c,d,e], dispatch a, then send full ordered window [b,c,d,e,f].
        assertEquals(5, queue.prioritize(run, listOf("com.signin.a", "com.signin.b", "com.signin.c", "com.signin.d", "com.signin.e")).getInt("prioritized"))
        assertEquals(
            listOf("com.signin.a", "com.signin.b", "com.signin.c", "com.signin.d", "com.signin.e"),
            queue.dispatchOrder().map { it.second },
        )
        assertTrue(queue.dispatch("com.signin.a", sessionB))
        assertEquals(5, queue.prioritize(run, listOf("com.signin.b", "com.signin.c", "com.signin.d", "com.signin.e", "com.signin.f")).getInt("prioritized"))
        assertEquals(
            listOf("com.signin.b", "com.signin.c", "com.signin.d", "com.signin.e", "com.signin.f"),
            queue.dispatchOrder().map { it.second },
        )
        queue.dispatchRound()
        assertEquals(
            listOf("com.signin.a", "com.signin.b", "com.signin.c", "com.signin.d", "com.signin.e", "com.signin.f"),
            handed,
        )

        // Release bulk: remaining apps dispatch smallest-first because defer_opted_in survived the Pause rebind.
        val released = queue.releaseBulkHandoff(run)
        assertTrue(released.getBoolean("accepted"))
        assertFalse(released.getBoolean("bulk_deferred"))
        queue.dispatchRound()
        assertEquals(
            listOf("com.small.app", "com.medium.app", "com.big.app", "com.unknown.app"),
            handed.drop(6),
        )
    }

    /** R1: priorities staged before Switch op 13 Pause carry atomically across the handoff_session rebind. */
    @Test fun r1StagedPrioritiesBeforeSwitchPauseCarryAcrossRebind() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        assertTrue(queue.prioritize(run, listOf("com.staged.two", "com.staged.one")).getBoolean("accepted"))

        val sessionB = queue.startPausedTransfer()
        queue.select(listOf(
            doc("com.bulk.app").apply { putLong("install_size", 10_000_000L) },
            doc("com.staged.one").apply { putLong("install_size", 90_000_000L) },
            doc("com.staged.two").apply { putLong("install_size", 80_000_000L) },
        ))

        assertTrue(queue.isBulkDeferred(sessionB))
        assertEquals(
            listOf(sessionB to "com.staged.two", sessionB to "com.staged.one"),
            queue.dispatchOrder(),
        )
    }

    /** R2: stale endHandoff when a newer session C exists still clears B's deferral and schedules B. */
    @Test fun r2StaleEndHandoffUnblocksDeferredHandoffSessionAndSchedulesIt() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionB = queue.startPausedTransfer()
        queue.select(listOf(
            doc("com.large.app").apply { putLong("install_size", 400_000_000L) },
            doc("com.small.app").apply { putLong("install_size", 10_000_000L) },
        ))
        assertTrue(queue.isBulkDeferred(sessionB))
        assertTrue(queue.dispatchOrder().isEmpty())

        // An independent transfer creates newer session C while handoff_session stays on B.
        val sessionC = queue.beginTransfer()
        assertNotEquals(sessionB, sessionC)
        assertEquals(sessionC, queue.currentSessionId())
        scheduler.cancelAll()

        val ended = queue.endHandoff(run, "success")
        assertFalse("stale session must not close newer session C", ended.getBoolean("accepted"))
        assertFalse("handoff session B must no longer be deferred", queue.isBulkDeferred(sessionB))
        assertFalse(queue.handoff(run).getBoolean("bulk_deferred"))
        assertFalse(queue.summary(sessionC).getBoolean("selection_closed"))
        assertTrue(
            "pending apps on B must be scheduled immediately",
            scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB },
        )
        assertEquals(
            listOf(sessionB to "com.small.app", sessionB to "com.large.app"),
            queue.dispatchOrder(),
        )
    }

    /** Round-2 Sol P1: repeated Pause / catalog retries after selection reuse the bound deferred handoff session. */
    @Test fun repeatedPauseAndCatalogRetriesAfterSelectionReuseBoundDeferredHandoffSession() {
        val handed = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
        }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionB = queue.startPausedTransfer()
        queue.select(listOf(doc("com.first.big").apply { putLong("install_size", 400_000_000L) }))

        // Repeated Switch op 13 (Pause) and op 7/22 (catalog) after selection must stay on sessionB.
        assertEquals(sessionB, queue.startPausedTransfer())
        assertEquals(sessionB, queue.catalogSession())
        queue.select(listOf(doc("com.second.small").apply { putLong("install_size", 20_000_000L) }))

        assertEquals(sessionB, queue.currentSessionId())
        assertTrue(queue.isBulkDeferred(sessionB))
        val receipt = queue.handoff(run)
        assertEquals(sessionB, receipt.getString("session_id"))
        assertTrue(receipt.getBoolean("bulk_deferred"))
        assertEquals(listOf("com.first.big", "com.second.small"), receipt.getStringArrayList("packages"))
        assertTrue("neither app may dispatch while deferred", queue.dispatchOrder().isEmpty())

        assertTrue(queue.releaseBulkHandoff(run).getBoolean("accepted"))
        queue.dispatchRound()
        assertEquals(listOf("com.second.small", "com.first.big"), handed)
    }

    /** R3: starting a new beginHandoff(run2) unblocks the superseded run1 session and schedules it. */
    @Test fun r3SupersededHandoffRunUnblocksOldDeferredSessionAndSchedulesIt() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val run2 = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }

        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val session1 = queue.currentSessionId()!!
        queue.select(listOf(
            doc("com.old.large").apply { putLong("install_size", 300_000_000L) },
            doc("com.old.small").apply { putLong("install_size", 5_000_000L) },
        ))
        assertTrue(queue.isBulkDeferred(session1))
        assertTrue(queue.dispatchOrder().isEmpty())
        scheduler.cancelAll()

        assertTrue(queue.beginHandoff(run2, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val session2 = queue.currentSessionId()!!
        assertNotEquals(session1, session2)
        assertFalse(queue.isBulkDeferred(session1))
        assertTrue(queue.isBulkDeferred(session2))
        assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB })

        queue.select(listOf(doc("com.new.bulk").apply { putLong("install_size", 1_000_000L) }))
        assertEquals(
            listOf(session1 to "com.old.small", session1 to "com.old.large"),
            queue.dispatchOrder(),
        )
    }

    /** Crash/retry window: repeating releaseBulkHandoff while pending apps remain still schedules the job. */
    @Test fun releaseBulkHandoffRetryStillSchedulesPendingApps() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        queue.select(listOf(doc("com.bulk.app")))

        assertTrue(queue.releaseBulkHandoff(run).getBoolean("accepted"))
        assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB })

        // Simulate crash/restart losing the scheduled job after defer_bulk=false already committed.
        scheduler.cancelAll()
        val retry = queue.releaseBulkHandoff(run)
        assertTrue(retry.getBoolean("accepted"))
        assertFalse(retry.getBoolean("bulk_deferred"))
        assertTrue(
            "retry release must still schedule pending apps even when wasDeferred is false",
            scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB },
        )
    }

    /** Durable 12-hour fail-open cap refreshes on live heartbeats and never resurrects once expired. */
    @Test fun deferBulkFailsOpenAfterTwelveHoursAndRefreshesOnLiveHeartbeats() {
        val startMs = 1_700_000_000_000L
        var nowMs = startMs
        val handed = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
        }.apply { clock = { nowMs } }

        assertEquals(12L * 60L * 60_000L, MigrationInstallQueue.MAX_DEFER_BULK_MS)
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionId = queue.startPausedTransfer()
        queue.select(listOf(
            doc("com.big.app").apply { putLong("install_size", 500_000_000L) },
            doc("com.small.app").apply { putLong("install_size", 10_000_000L) },
            doc("com.signin.one").apply { putLong("install_size", 50_000_000L) },
        ))

        // Heartbeat 1: beginHandoff(resumeOnly=true) at +6h refreshes defer_bulk_at so +12h from start stays deferred.
        nowMs = startMs + 6L * 60L * 60_000L
        assertTrue(queue.beginHandoff(run, resumeOnly = true, deferBulk = true).getBoolean("accepted"))
        nowMs = startMs + 13L * 60L * 60_000L
        assertTrue("resume heartbeat must keep long cable copy deferred past original 12h", queue.isBulkDeferred(sessionId))

        // Heartbeat 2: prioritize() while still deferred at +13h refreshes defer_bulk_at again (new expiry: +25h).
        assertEquals(1, queue.prioritize(run, listOf("com.signin.one")).getInt("prioritized"))
        assertTrue(queue.dispatch("com.signin.one", sessionId))
        nowMs = startMs + 25L * 60L * 60_000L - 1L
        assertTrue(queue.isBulkDeferred(sessionId))
        assertTrue(queue.handoff(run).getBoolean("bulk_deferred"))
        assertTrue(queue.dispatchOrder().isEmpty())

        // Once 12h elapse since the last live heartbeat, deferral fails open smallest-first.
        nowMs = startMs + 25L * 60L * 60_000L
        assertFalse(queue.isBulkDeferred(sessionId))
        assertFalse(queue.handoff(run).getBoolean("bulk_deferred"))

        // Late heartbeats after expiry must not resurrect an expired deferral.
        assertTrue(queue.beginHandoff(run, resumeOnly = true, deferBulk = true).getBoolean("accepted"))
        assertFalse(queue.isBulkDeferred(sessionId))

        assertEquals(listOf(sessionId to "com.small.app", sessionId to "com.big.app"), queue.dispatchOrder())
        queue.dispatchRound()
        assertEquals(listOf("com.signin.one", "com.small.app", "com.big.app"), handed)
    }

    /** Idle deferred job renews Aurora's 15-min update hold via a ~10-min delayed wake until release or 3h cap. */
    @Test fun deferredIdleJobRenewsUpdateHoldAndSchedulesDelayedWakeUntilReleaseOrCap() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val startMs = 1_700_000_000_000L
        var nowMs = startMs
        val pauseRenewals = mutableListOf<String?>()
        val handed = mutableListOf<String>()
        val queuedInStore = mutableSetOf<String>()
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, options ->
                when (operation) {
                    4 -> {
                        pauseRenewals += options.getString("session_id")
                        Bundle().apply { putBoolean("success", true) }
                    }
                    5 -> Bundle().apply { putBoolean("success", true) }
                    7 -> Bundle().apply { putBoolean("automatic_update_hold", true) }
                    else -> Bundle()
                }
            },
            storeCall = { operation, name ->
                when {
                    operation == 2 -> { handed += name; queuedInStore += name; state(name, "queued") }
                    name in queuedInStore -> state(name, "queued")
                    else -> state(name, "none")
                }
            },
        ).apply { clock = { nowMs } }

        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionId = queue.startPausedTransfer()
        assertTrue(queue.pause(sessionId).getBoolean("success"))
        queue.select(listOf(
            doc("com.big.app").apply { putLong("install_size", 500_000_000L) },
            doc("com.small.app").apply { putLong("install_size", 10_000_000L) },
        ))
        pauseRenewals.clear()

        val controller = Robolectric.buildService(MigrationInstallJob::class.java).create()
        try {
            val job = controller.get()

            // Pass 1: idle deferred copy finishes without tight retry, renews update hold, and schedules 10-min wake.
            var rescheduled: Boolean? = null
            job.runJob(MigrationInstallQueue.JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertEquals(false, rescheduled)
            assertTrue(handed.isEmpty())
            assertEquals(listOf(sessionId), pauseRenewals)
            val wake1 = scheduler.allPendingJobs.single { it.id == MigrationInstallQueue.DEFER_WAKE_JOB }
            assertFalse(wake1.isExpedited)
            assertTrue(wake1.isPersisted)
            assertEquals(MigrationInstallQueue.DEFER_RENEW_INTERVAL_MS, wake1.minLatencyMillis)
            assertEquals(
                MigrationInstallQueue.DEFER_RENEW_INTERVAL_MS + MigrationInstallQueue.DEFER_WAKE_FLEX_MS,
                wake1.maxExecutionDelayMillis,
            )

            // Pass 2: 4 minutes before the 3h cap, the delayed wake is capped at the remaining 4 minutes.
            val fourMinutesMs = 4L * 60_000L
            nowMs = startMs + MigrationInstallQueue.MAX_DEFER_BULK_MS - fourMinutesMs
            pauseRenewals.clear()
            job.runJob(MigrationInstallQueue.DEFER_WAKE_JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertEquals(false, rescheduled)
            assertTrue(handed.isEmpty())
            assertEquals(listOf(sessionId), pauseRenewals)
            val wake2 = scheduler.allPendingJobs.single { it.id == MigrationInstallQueue.DEFER_WAKE_JOB }
            assertEquals(fourMinutesMs, wake2.minLatencyMillis)

            // Pass 3: releasing bulk cancels the delayed wake and schedules the primary expedited job.
            val released = queue.releaseBulkHandoff(run)
            assertTrue(released.getBoolean("accepted"))
            assertTrue(scheduler.allPendingJobs.none { it.id == MigrationInstallQueue.DEFER_WAKE_JOB })
            assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB })

            // Verify 3h cap wake on a fresh deferred run: once 3h elapses, DEFER_WAKE_JOB dispatches bulk & hands back to JOB.
            clear()
            handed.clear()
            queuedInStore.clear()
            nowMs = startMs
            assertTrue(queue.beginHandoff("2b4e28ba-2fa1-11d2-883f-0016d3cca428", resumeOnly = false, deferBulk = true).getBoolean("accepted"))
            val id2 = queue.startPausedTransfer()
            assertTrue(queue.pause(id2).getBoolean("success"))
            queue.select(listOf(
                doc("com.big.app").apply { putLong("install_size", 500_000_000L) },
                doc("com.small.app").apply { putLong("install_size", 10_000_000L) },
            ))
            job.runJob(MigrationInstallQueue.JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.DEFER_WAKE_JOB })

            nowMs = startMs + MigrationInstallQueue.MAX_DEFER_BULK_MS
            job.runJob(MigrationInstallQueue.DEFER_WAKE_JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertEquals(false, rescheduled)
            assertEquals(listOf("com.small.app", "com.big.app"), handed)
            assertTrue(scheduler.allPendingJobs.none { it.id == MigrationInstallQueue.DEFER_WAKE_JOB })
            assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB })
        } finally {
            controller.destroy()
        }
    }

    /** Priority cap prunes settled entries first and never evicts still-pending admitted packages back into limbo. */
    @Test fun priorityCapNeverEvictsStillPendingAdmittedPackages() {
        val queue = MigrationInstallQueue(context) { _, name -> state(name, "none") }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val id = queue.currentSessionId()!!

        val pendingApps = (0 until 20).map { "com.pending.app%03d".format(it) }
        val settledApps = (0 until 540).map { "com.settled.app%03d".format(it) }
        queue.select((pendingApps + settledApps).map(::doc))
        for (pkg in settledApps) {
            queue.save(pkg, state(pkg, "installed").apply {
                putBoolean("installed", true)
                putLong("installed_version_code", 12)
                putBoolean("terminal", true)
            }, dispatched = true, id = id)
        }

        // Admit the 20 pending apps first, then flood 540 settled apps (> MAX_PRIORITY_TOTAL = 500).
        assertEquals(20, queue.prioritize(run, pendingApps).getInt("prioritized"))
        settledApps.chunked(50).forEach { batch ->
            assertTrue(queue.prioritize(run, batch).getBoolean("accepted"))
        }

        val order = queue.priorityOrder(id)
        assertEquals(MigrationInstallQueue.MAX_PRIORITY_TOTAL, order.size)
        assertTrue("all still-pending admitted apps must survive cap pruning", order.containsAll(pendingApps))
        assertEquals(pendingApps, queue.dispatchOrder().map { it.second })

        // Even when > MAX_PRIORITY_TOTAL still-pending apps are admitted, none are evicted back to deferred limbo.
        clear()
        val manyPending = (0 until 520).map { "com.many.app%03d".format(it) }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val id2 = queue.currentSessionId()!!
        queue.select(manyPending.map(::doc))
        manyPending.chunked(50).forEach { batch ->
            assertTrue(queue.prioritize(run, batch).getBoolean("accepted"))
        }
        assertEquals(520, queue.priorityOrder(id2).size)
        assertEquals(520, queue.dispatchOrder().size)
    }

    /** syncStorePriority forwards only actionable packages (<=200) and keeps priority_dirty until overflow/truncation clears. */
    @Test fun syncStorePriorityForwardsOnlyActionableWithin200CapAndPreservesDirtyOnOverflow() {
        val prefs = context.getSharedPreferences("migration-installs", 0)
        val sentBatches = mutableListOf<List<String>>()
        var auroraCap = 200
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, options ->
                when (operation) {
                    7 -> Bundle().apply { putBoolean("prioritize_downloads", true) }
                    8 -> {
                        val pkgs = options.getStringArrayList("packages").orEmpty()
                        sentBatches += pkgs
                        Bundle().apply {
                            putBoolean("success", true)
                            putStringArrayList("packages", ArrayList(pkgs.take(auroraCap)))
                        }
                    }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "queued") },
        )

        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        val id = queue.currentSessionId()!!
        val settled = (0 until 20).map { "com.done.app%03d".format(it) }
        val active = (0 until 210).map { "com.live.app%03d".format(it) }
        queue.select((settled + active).map(::doc))
        for (pkg in settled) {
            queue.save(pkg, state(pkg, "installed").apply {
                putBoolean("installed", true)
                putLong("installed_version_code", 12)
                putBoolean("terminal", true)
            }, dispatched = true, id = id)
        }
        // Push settled apps to the front first, then active apps in reverse chunks so active[0..209] are ordered cleanly.
        queue.prioritize(run, settled)
        active.chunked(50).reversed().forEach { batch -> queue.prioritize(run, batch) }

        // Round 1: only actionable apps are forwarded (<= 200), no settled apps, single op 8 call, dirty stays true (10 overflow).
        queue.dispatchRound()
        assertEquals(1, sentBatches.size)
        assertEquals(200, sentBatches.single().size)
        assertEquals(active.take(200), sentBatches.single())
        assertTrue(sentBatches.single().none { it in settled })
        assertTrue("actionable overflow past 200 must keep priority_dirty set", prefs.getBoolean("$id:priority_dirty", false))

        // Mark 30 active apps settled so remaining 180 (including overflow 200..209) fit inside 200, but simulate Aurora truncating at 150.
        for (pkg in active.take(30)) {
            queue.save(pkg, state(pkg, "installed").apply {
                putBoolean("installed", true)
                putLong("installed_version_code", 12)
                putBoolean("terminal", true)
            }, dispatched = true, id = id)
        }
        sentBatches.clear()
        auroraCap = 150
        queue.dispatchRound()
        assertEquals(1, sentBatches.size)
        assertEquals(active.drop(30), sentBatches.single())
        assertTrue("Aurora truncating accepted packages must keep priority_dirty set", prefs.getBoolean("$id:priority_dirty", false))

        // Once Aurora accepts all 180 actionable packages, priority_dirty clears cleanly.
        sentBatches.clear()
        auroraCap = 200
        queue.dispatchRound()
        assertEquals(1, sentBatches.size)
        assertEquals(active.drop(30), sentBatches.single())
        assertFalse(prefs.getBoolean("$id:priority_dirty", false))
    }

    /** Smallest-first sizeRank applies only to deferral-opted sessions; non-deferred A2A/standalone keeps package order. */
    @Test fun smallestFirstAppliesOnlyToDeferralOptedSessions() {
        val handed = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
        }
        fun sizedDocs() = listOf(
            doc("com.a.huge").apply { putLong("install_size", 500_000_000L) },
            doc("com.b.medium").apply { putLong("install_size", 150_000_000L) },
            doc("com.c.tiny").apply { putLong("install_size", 10_000_000L) },
        )

        // Non-deferred session (e.g. Android-to-Android): preserves alphabetical package order despite install_size.
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = false).getBoolean("accepted"))
        queue.select(sizedDocs())
        queue.dispatchRound()
        assertEquals(listOf("com.a.huge", "com.b.medium", "com.c.tiny"), handed)

        // Deferral-opted session (iPhone): after release, bulk apps run smallest-first.
        clear()
        handed.clear()
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        queue.startPausedTransfer()
        queue.select(sizedDocs() + doc("com.d.unknown"))
        assertTrue(queue.releaseBulkHandoff(run).getBoolean("accepted"))
        queue.dispatchRound()
        assertEquals(listOf("com.c.tiny", "com.b.medium", "com.a.huge", "com.d.unknown"), handed)
    }

    /** summary() skips polling Aurora for unprioritized deferred pending apps until they are prioritized or released. */
    @Test fun summarySkipsAuroraPollsForDeferredUnprioritizedPendingApps() {
        val polled = mutableListOf<String>()
        val queue = MigrationInstallQueue(context) { operation, name ->
            if (operation == 3) polled += name
            state(name, "none")
        }
        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val id = queue.startPausedTransfer()
        val bulkApps = (1..20).map { "com.bulk.app%02d".format(it) }
        queue.select((listOf("com.signin.one") + bulkApps).map(::doc))

        // Idle deferred: zero Aurora op 3 polls, even with freeze=true.
        val idle = queue.summary(id, reschedule = false, freeze = true)
        assertTrue(idle.getBoolean("bulk_deferred"))
        assertFalse(idle.getBoolean("has_active_work"))
        assertTrue("expected zero polls for idle deferred apps, got $polled", polled.isEmpty())

        // With 1 prioritized app: only that app is polled, never the 20 deferred bulk apps.
        assertEquals(1, queue.prioritize(run, listOf("com.signin.one")).getInt("prioritized"))
        polled.clear()
        val active = queue.summary(id, reschedule = false, freeze = true)
        assertTrue(active.getBoolean("bulk_deferred"))
        assertTrue(active.getBoolean("has_active_work"))
        assertEquals(listOf("com.signin.one"), polled)

        // After bulk release: all 21 apps are polled normally.
        assertTrue(queue.releaseBulkHandoff(run).getBoolean("accepted"))
        polled.clear()
        queue.summary(id, reschedule = false)
        assertEquals(21, polled.toSet().size)
    }

    /** Round-2 Sol P2 #3 & #4: schedule before cancel fallback wake; secure next wake before onFinished(false) & handle mid-pass expiry. */
    @Test fun safeWakeScheduleOrderingPreservesFallbackAndRetriesOnScheduleFailure() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val startMs = 1_700_000_000_000L
        var nowMs = startMs
        var expireOnRenew = false
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, _ ->
                when (operation) {
                    4 -> {
                        if (expireOnRenew) nowMs = startMs + MigrationInstallQueue.MAX_DEFER_BULK_MS
                        Bundle().apply { putBoolean("success", true) }
                    }
                    5 -> Bundle().apply { putBoolean("success", true) }
                    7 -> Bundle().apply { putBoolean("automatic_update_hold", true) }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "none") },
        ).apply { clock = { nowMs } }

        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionId = queue.startPausedTransfer()
        assertTrue(queue.pause(sessionId).getBoolean("success"))
        queue.select(listOf(doc("com.bulk.app")))
        assertNotNull(queue.syncDeferredWake())
        assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.DEFER_WAKE_JOB })

        // Part A: if scheduleImmediate() fails during releaseBulkHandoff, DEFER_WAKE_JOB must stay intact as fallback.
        queue.jobSchedulerHook = { s, info ->
            if (info.id == MigrationInstallQueue.JOB) JobScheduler.RESULT_FAILURE else s.schedule(info)
        }
        assertTrue(runCatching { queue.releaseBulkHandoff(run) }.isFailure)
        assertTrue(
            "fallback wake must not be cancelled when primary job scheduling fails",
            scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.DEFER_WAKE_JOB },
        )
        queue.jobSchedulerHook = null
        assertTrue(queue.releaseBulkHandoff(run).getBoolean("accepted"))
        assertTrue(scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB })
        assertTrue(scheduler.allPendingJobs.none { it.id == MigrationInstallQueue.DEFER_WAKE_JOB })

        val controller = Robolectric.buildService(MigrationInstallJob::class.java).create()
        try {
            val job = controller.get()

            // Part B: runJob secures DEFER_WAKE_JOB strictly BEFORE calling onFinished(false).
            clear()
            nowMs = startMs
            assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
            val id2 = queue.startPausedTransfer()
            assertTrue(queue.pause(id2).getBoolean("success"))
            queue.select(listOf(doc("com.bulk.app")))
            scheduler.cancelAll()
            val events = mutableListOf<String>()
            queue.jobSchedulerHook = { s, info ->
                if (info.id == MigrationInstallQueue.DEFER_WAKE_JOB) events += "schedule_wake"
                s.schedule(info)
            }
            job.runJob(MigrationInstallQueue.JOB, queue = queue, deadlineNanos = System.nanoTime()) { reschedule ->
                events += "finished:$reschedule"
            }
            assertEquals(listOf("schedule_wake", "finished:false"), events)

            // If scheduling the next wake fails, runJob passes onFinished(true) so JobScheduler retries with backoff.
            queue.jobSchedulerHook = { s, info ->
                if (info.id == MigrationInstallQueue.DEFER_WAKE_JOB) JobScheduler.RESULT_FAILURE else s.schedule(info)
            }
            var rescheduled: Boolean? = null
            job.runJob(MigrationInstallQueue.JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertEquals(true, rescheduled)

            // Part C: deferral expires between summary() and post-loop wake check (during renewPause).
            queue.jobSchedulerHook = null
            scheduler.cancelAll()
            nowMs = startMs
            expireOnRenew = true
            job.runJob(MigrationInstallQueue.DEFER_WAKE_JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertEquals(false, rescheduled)
            assertTrue("expired deferral must hand off to primary JOB", scheduler.allPendingJobs.any { it.id == MigrationInstallQueue.JOB })
            assertTrue(scheduler.allPendingJobs.none { it.id == MigrationInstallQueue.DEFER_WAKE_JOB })

            // If primary JOB scheduling fails during that handoff, DEFER_WAKE_JOB returns onFinished(true).
            clear()
            nowMs = startMs
            expireOnRenew = false
            assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
            val id3 = queue.startPausedTransfer()
            assertTrue(queue.pause(id3).getBoolean("success"))
            queue.select(listOf(doc("com.bulk.app")))
            expireOnRenew = true
            queue.jobSchedulerHook = { s, info ->
                if (info.id == MigrationInstallQueue.JOB) JobScheduler.RESULT_FAILURE else s.schedule(info)
            }
            job.runJob(MigrationInstallQueue.DEFER_WAKE_JOB, queue = queue, deadlineNanos = System.nanoTime()) { rescheduled = it }
            assertEquals(true, rescheduled)
        } finally {
            queue.jobSchedulerHook = null
            controller.destroy()
        }
    }

    /** Round-2 Sol P2 #6: syncStorePriority reserves Aurora's 200-slot priority window for already-dispatched apps. */
    @Test fun syncStorePriorityReserves200SlotWindowForAlreadyDispatchedApps() {
        val prefs = context.getSharedPreferences("migration-installs", 0)
        val sentBatches = mutableListOf<List<String>>()
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, options ->
                when (operation) {
                    7 -> Bundle().apply { putBoolean("prioritize_downloads", true) }
                    8 -> {
                        val pkgs = options.getStringArrayList("packages").orEmpty()
                        sentBatches += pkgs
                        Bundle().apply {
                            putBoolean("success", true)
                            putStringArrayList("packages", ArrayList(pkgs.take(200)))
                        }
                    }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "queued") },
        )

        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        val id = queue.currentSessionId()!!
        val queued = (0 until 10).map { "com.queued.app%02d".format(it) }
        val pendingHints = (0 until 205).map { "com.pending.app%03d".format(it) }
        queue.select((queued + pendingHints).map(::doc))
        for (pkg in queued) {
            queue.save(pkg, state(pkg, "queued"), dispatched = true, id = id)
        }

        // Prioritize queued apps first, then prepend 205 not-yet-dispatched pending hints ahead of them (total 215 > 200).
        queue.prioritize(run, queued)
        pendingHints.chunked(50).reversed().forEach { batch -> queue.prioritize(run, batch) }

        // Stop right after initial syncStorePriority so we verify the exact 200-slot batch sent before pending dispatch.
        queue.dispatchRound(cancelled = { sentBatches.isNotEmpty() })
        assertEquals(1, sentBatches.size)
        val batch = sentBatches.single()
        assertEquals(200, batch.size)
        assertTrue("all 10 already-dispatched apps must fit inside Aurora's 200-slot window", batch.containsAll(queued))
        assertEquals(pendingHints.take(190) + queued, batch)
        assertTrue("overflowing pending hints must keep priority_dirty set", prefs.getBoolean("$id:priority_dirty", false))
    }

    /** Round-3 Opus P2: stopping DEFER_WAKE_JOB must never cancel a concurrently started primary JOB. */
    @Test fun perJobIdCancellationIsolatesDeferredWakeStopFromPrimaryJob() {
        val controller = Robolectric.buildService(MigrationInstallJob::class.java).create()
        try {
            val job = controller.get()
            val wakeFlag = job.registerJobForTests(MigrationInstallQueue.DEFER_WAKE_JOB)
            val primaryFlag = job.registerJobForTests(MigrationInstallQueue.JOB)

            assertTrue(job.stopJobById(MigrationInstallQueue.DEFER_WAKE_JOB))
            assertTrue("stopping DEFER_WAKE_JOB must cancel DEFER_WAKE_JOB", wakeFlag.get())
            assertFalse("stopping DEFER_WAKE_JOB must not cancel primary JOB", primaryFlag.get())

            // Primary JOB still runs to completion using its uncancelled flag.
            val handed = mutableListOf<String>()
            val queue = MigrationInstallQueue(context) { operation, name ->
                if (operation == 2) { handed += name; state(name, "queued") } else state(name, "none")
            }
            assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = false).getBoolean("accepted"))
            queue.select(listOf(doc("com.primary.app")))
            var finishedReschedule: Boolean? = null
            job.runJob(MigrationInstallQueue.JOB, cancelled = primaryFlag, queue = queue, deadlineNanos = System.nanoTime()) {
                finishedReschedule = it
            }
            assertEquals(listOf("com.primary.app"), handed)
            assertNotNull(finishedReschedule)

            // Destroying the service cancels all remaining active job flags.
            job.onDestroy()
            assertTrue(primaryFlag.get())
        } finally {
            controller.destroy()
        }
    }

    /** Round-3 Sol P2: repeated Switch Pause heartbeat extends 12h cap, and runJob uses one clock sample at finish. */
    @Test fun repeatedSwitchPauseRefreshesDeferralHeartbeatAndRunJobFinishUsesSingleClockSample() {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val startMs = 1_700_000_000_000L
        var nowMs = startMs
        val queue = MigrationInstallQueue(
            context,
            controlCall = { operation, _ ->
                when (operation) {
                    4, 5 -> Bundle().apply { putBoolean("success", true) }
                    7 -> Bundle().apply { putBoolean("automatic_update_hold", true) }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "none") },
        ).apply { clock = { nowMs } }

        assertTrue(queue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val sessionId = queue.startPausedTransfer()
        assertTrue(queue.pause(sessionId).getBoolean("success"))
        queue.select(listOf(doc("com.bulk.app")))

        // Switch Pause heartbeat at +8h refreshes defer_bulk_at so the active copy stays deferred until +20h.
        nowMs = startMs + 8L * 60L * 60_000L
        assertEquals(sessionId, queue.startPausedTransfer())
        nowMs = startMs + 19L * 60L * 60_000L
        assertTrue("Switch Pause heartbeat must keep >12h active copy deferred", queue.isBulkDeferred(sessionId))
        nowMs = startMs + 20L * 60L * 60_000L
        assertFalse(queue.isBulkDeferred(sessionId))

        // Late Pause after expiry must not resurrect deferral.
        assertEquals(sessionId, queue.startPausedTransfer())
        assertFalse(queue.isBulkDeferred(sessionId))

        // Single clock sample at end of runJob: even if a second clock() read after finishNowMs would cross 12h,
        // dispatchOrder(finishNowMs) and syncDeferredWake(finishNowMs) share one sample and schedule DEFER_WAKE_JOB cleanly.
        clear()
        val baseMs = 1_800_000_000_000L
        var armedForFinish = false
        var finishClockReads = 0
        val tickingQueue = MigrationInstallQueue(
            context,
            controlCall = { operation, _ ->
                when (operation) {
                    4 -> {
                        if (armedForFinish) finishClockReads = 1
                        Bundle().apply { putBoolean("success", true) }
                    }
                    5 -> Bundle().apply { putBoolean("success", true) }
                    7 -> Bundle().apply { putBoolean("automatic_update_hold", true) }
                    else -> Bundle()
                }
            },
            storeCall = { _, name -> state(name, "none") },
        ).apply {
            clock = {
                when (finishClockReads) {
                    0 -> baseMs
                    1 -> {
                        finishClockReads = 2
                        baseMs + MigrationInstallQueue.MAX_DEFER_BULK_MS - 10_000L
                    }
                    else -> baseMs + MigrationInstallQueue.MAX_DEFER_BULK_MS + 10_000L
                }
            }
        }
        assertTrue(tickingQueue.beginHandoff(run, resumeOnly = false, deferBulk = true).getBoolean("accepted"))
        val id2 = tickingQueue.currentSessionId()!!
        assertTrue(tickingQueue.pause(id2).getBoolean("success"))
        tickingQueue.select(listOf(doc("com.bulk.app")))
        scheduler.cancelAll()
        armedForFinish = true
        val controller = Robolectric.buildService(MigrationInstallJob::class.java).create()
        try {
            var rescheduled: Boolean? = null
            controller.get().runJob(MigrationInstallQueue.JOB, queue = tickingQueue, deadlineNanos = System.nanoTime()) {
                rescheduled = it
            }
            assertEquals(false, rescheduled)
            assertEquals("finish check must sample clock only once", 2, finishClockReads)
            val wake = scheduler.allPendingJobs.single { it.id == MigrationInstallQueue.DEFER_WAKE_JOB }
            assertEquals(10_000L, wake.minLatencyMillis)
        } finally {
            controller.destroy()
        }
    }
}
