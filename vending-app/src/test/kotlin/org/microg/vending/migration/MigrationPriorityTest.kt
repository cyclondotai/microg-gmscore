/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** CYC-2499 (11-17 founders-02): Setup's sign-in apps and Google-account retries go to Aurora before the rest. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationPriorityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val run = "0f8fad5b-d9cb-469f-a165-70867728950e"
    @Before fun clear() { context.getSharedPreferences("migration-installs", 0).edit().clear().commit() }
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
}
