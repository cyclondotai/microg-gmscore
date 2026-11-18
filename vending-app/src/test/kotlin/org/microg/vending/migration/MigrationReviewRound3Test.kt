package org.microg.vending.migration

import android.app.job.JobScheduler
import android.content.Context
import android.os.Bundle
import android.os.ResultReceiver
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationReviewRound3Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app = "org.example.app"
    private fun state(status: String) = Bundle().apply {
        putString("package_name", app)
        putString("status", status)
        putLong("version_code", 12)
    }
    private fun select(queue: MigrationInstallQueue): String {
        val id = queue.beginTransfer()
        queue.select(listOf(state("selected")))
        queue.finishSelection(id)
        return id
    }

    @Test fun anUnchangedStatusPollMustNotVetoDispatch() {
        lateinit var queue: MigrationInstallQueue
        var pollOnDispatch = false
        val installs = AtomicInteger()
        queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) {
                installs.incrementAndGet()
                state("queued")
            } else {
                if (pollOnDispatch) {
                    pollOnDispatch = false
                    // Finish a status poll after dispatch took its revision but before its store response.
                    val publisher = Thread { queue.summary() }
                    publisher.start()
                    publisher.join(5000)
                    check(!publisher.isAlive)
                }
                state("none")
            }
        }
        val id = select(queue)
        queue.summary(id) // Stabilize saved preferences before repeating the same poll.
        val prefs = context.getSharedPreferences("migration-installs", 0)
        val before = prefs.all
        repeat(4) {
            pollOnDispatch = true
            queue.dispatchRound()
            if (installs.get() == 0) {
                assertEquals("an abandoned round persisted no changes", before, prefs.all)
            }
        }
        assertEquals("an unchanged status poll should let at least one dispatch proceed", 1, installs.get())
    }

    @Test fun jobsCallbackPublisherMustNotReplaceItsOwnJob() {
        var store = state("queued")
        val queue = MigrationInstallQueue(context, { _, _ ->
            Bundle().apply { putBoolean("owner_account_fallback", true) }
        }) { _, _ -> Bundle(store) }
        val id = select(queue)
        assertTrue(queue.dispatch(app, id))
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = scheduler.allPendingJobs.single()
        scheduler.cancel(job.id) // Remove the host shadow's initial entry so a replacement is observable.
        assertFalse(queue.summary(id, reschedule = false).getBoolean("installs_settled"))
        assertTrue(scheduler.allPendingJobs.isEmpty())
        val callbacks = mutableListOf<Int>()
        MigrationSetupReceivers.register(object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) { callbacks += resultCode }
        }, id)
        try {
            store = state("failed").apply { putString("errorCode", "app_not_purchased") }
            // The job publishes the summaries it collected itself (reschedule = false), never a second one.
            MigrationSetupReceivers.publish(queue, mapOf(id to queue.summary(id, reschedule = false)))
            assertEquals(listOf(2), callbacks)
            assertEquals(setOf(app), queue.pending(id))
            assertTrue("callback publisher scheduled the running job's ID", scheduler.allPendingJobs.isEmpty())
        } finally {
            MigrationSetupReceivers.clearForTests()
        }
    }

    @Test fun aSecondRetrySurvivesTheFirstRetryReply() {
        lateinit var queue: MigrationInstallQueue
        var stage = 0
        var approved = false
        queue = MigrationInstallQueue(context, { _, _ -> Bundle() }) { operation, _ ->
            if (operation == 3) state("none")
            else when (++stage) {
                1 -> state("unavailable").apply { putString("error", "network_error") }
                2 -> {
                    queue.retry(queue.currentSessionId()!!, app)
                    state("queued")
                }
                else -> state("queued")
            }
        }.apply {
            sentOptions = { operation, options ->
                if (operation == 2) approved = options!!.getBoolean("owner_approved_retry")
            }
        }
        val id = select(queue)
        val prefs = context.getSharedPreferences("migration-installs", 0)
        assertTrue(queue.dispatch(app, id))
        assertFalse(prefs.contains("$id:$app:retry_gen"))
        queue.retry(id, app)
        assertFalse(queue.dispatch(app, id))
        assertEquals(setOf(app), queue.pending(id))
        assertTrue(prefs.getBoolean("$id:$app:owner_retry", false))
        assertEquals(2, prefs.getInt("$id:$app:retry_gen", 0))
        assertTrue(queue.dispatch(app, id))
        assertTrue(approved)
        assertEquals(3, stage)
    }

    @Test fun anAppAddedDuringSummaryIsAskedOnTheNextPollOutsideTheLock() {
        val added = "org.example.later"
        lateinit var queue: MigrationInstallQueue
        val asked = mutableListOf<Pair<String, Boolean>>()
        queue = MigrationInstallQueue(context) { operation, name ->
            check(operation == 3)
            asked += name to Thread.holdsLock(MigrationInstallQueue.LOCK)
            if (asked.size == 1) {
                queue.select(listOf(state("selected").apply { putString("package_name", added) }))
            }
            state("none").apply { putString("package_name", name) }
        }
        queue.select(listOf(state("selected")))
        val first = queue.summary()
        assertEquals(listOf(app to false), asked)
        assertFalse(first.getBoolean("installs_settled"))
        queue.summary()
        assertEquals(setOf(app, added), asked.drop(1).map { it.first }.toSet())
        assertFalse(asked.any { it.second })
    }

    @Test fun aThrownInstallDoesNotLeaveTheAppInFlight() {
        var installed = false
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) error("transport failed")
            if (installed) state("installed").apply {
                putBoolean("installed", true)
                putLong("installed_version_code", 12)
            } else state("none")
        }
        val id = select(queue)
        assertFalse(queue.dispatch(app, id))
        installed = true
        assertTrue(queue.summary(id).getBoolean("all_installed"))
        assertTrue(queue.dispatch(app, id))
        assertTrue(queue.summary(id).getBoolean("complete"))
    }

    private fun receipt(queue: MigrationInstallQueue, id: String) =
        queue.summary(id, reschedule = false).getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()

    /** Round 3 review: a poll saving the same receipt again while the boundary summary asked Aurora hid a failure. */
    private fun boundaryKeepsTheFailure(overlap: String, freeze: Boolean) {
        lateinit var queue: MigrationInstallQueue
        var status = "none"
        var hook: (() -> Unit)? = null
        queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued") else {
                hook?.let { hook = null; Thread(it).apply { start(); join(5000) } }
                state(status).apply { if (status == "failed") putString("errorCode", "download_failed") }
            }
        }
        val id = select(queue)
        assertTrue(queue.dispatch(app, id))
        status = "failed"
        hook = { queue.save(app, state(overlap), id = id) }
        // What the boundary summary itself left saved, before any later summary could ask Aurora again.
        val saved = queue.summary(id, reschedule = false, freeze = freeze)
            .getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
        assert(hook == null)
        val prefs = context.getSharedPreferences("migration-installs", 0)
        assertEquals("failed", saved.getString("status"))
        assertEquals("failed", prefs.getString("$id:$app:status", null))
        assertTrue(prefs.getBoolean("$id:$app:terminal", false))
    }
    @Test fun anUnchangedSaveDuringTheBoundarySummaryKeepsTheFailure() = boundaryKeepsTheFailure("queued", freeze = false)
    @Test fun aChangedRowAtTheBoundaryIsAskedAgain() = boundaryKeepsTheFailure("downloading", freeze = true)

    @Test fun anOwnerRetryAsksAuroraWithoutTheLock() {
        val underLock = mutableListOf<Int>()
        var installs = 0
        val queue = MigrationInstallQueue(context, controlCall = { operation, _ ->
            if (Thread.holdsLock(MigrationInstallQueue.LOCK)) underLock += operation
            Bundle().apply { putBoolean("success", true); putBoolean("owner_approved_retry", true) }
        }) { operation, _ -> if (operation == 2) { installs++; state("queued") } else state("awaiting_install") }
        val id = select(queue)
        queue.dispatch(app, id)
        assertEquals("owner_action_required", receipt(queue, id).getString("status"))
        assertEquals(0, installs)
        queue.retry(id)
        assertTrue(app in queue.pending(id))
        assertTrue(queue.dispatch(app, id))
        assertEquals(1, installs)
        assertEquals("Aurora was asked while holding the queue lock", emptyList<Int>(), underLock)
    }
}
