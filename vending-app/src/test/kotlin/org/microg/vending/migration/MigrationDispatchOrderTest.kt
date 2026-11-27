/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.random.Random

/** CYC-2573: dispatchOrder reads each app's sort keys once and gives exactly the order of the comparator it replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationDispatchOrderTest {
    private val prefs: SharedPreferences get() = RuntimeEnvironment.getApplication().getSharedPreferences("migration-installs", 0)

    /** The comparator before CYC-2573, which read the keys on every comparison. */
    private fun previousOrder(queue: MigrationInstallQueue): List<Pair<String, String>> {
        fun tier(id: String, name: String): Int = when {
            name in prefs.getStringSet("$id:priority", emptySet()).orEmpty() -> 0
            prefs.getBoolean("$id:$name:owner_account", false) -> 1
            else -> 2
        }
        return queue.sessions().flatMap { id -> queue.pending(id).sorted().map { id to it } }
            .sortedWith(compareBy({ (id, name) -> prefs.getLong("$id:$name:throttled_at", 0) }, { (id, name) -> tier(id, name) }))
    }

    @Test fun theOrderIsThePreviousComparatorsOverRandomQueues() {
        prefs.edit().clear().commit()
        // One queue for every state: its prefs field sees each state written below.
        val queue = MigrationInstallQueue(RuntimeEnvironment.getApplication())
        val random = Random(2573)
        val pool = (0 until 14).map { "org.example.app%02d".format(it) }
        var ties = 0
        var reordered = 0
        repeat(1500) { round ->
            val edit = prefs.edit().clear()
            val ids = (0 until 1 + random.nextInt(4)).map { "session-$it" }.shuffled(random)
            edit.putString("sessions", ids.joinToString(","))
            for (id in ids) {
                // Pending and priority sets: absent, empty or a random part of the pool (priority may name apps not pending).
                if (random.nextInt(8) != 0) edit.putStringSet("$id:pending", pool.filter { random.nextInt(3) != 0 }.toSet())
                if (random.nextBoolean()) edit.putStringSet("$id:priority", pool.filter { random.nextInt(4) == 0 }.toSet())
                for (name in pool) {
                    // Few distinct throttle times, so most apps tie on the first key; absent counts as 0.
                    when (random.nextInt(4)) {
                        0 -> Unit
                        else -> edit.putLong("$id:$name:throttled_at", random.nextLong(0, 3))
                    }
                    when (random.nextInt(3)) {
                        0 -> Unit
                        1 -> edit.putBoolean("$id:$name:owner_account", true)
                        else -> edit.putBoolean("$id:$name:owner_account", false)
                    }
                    // Non-deferred sessions must keep exact package order even when installSize is recorded.
                    if (random.nextBoolean()) edit.putLong("$id:$name:installSize", random.nextLong(0, 5) * 10_000_000L)
                }
            }
            check(edit.commit())
            val previous = previousOrder(queue)
            val order = queue.dispatchOrder()
            assertEquals("round $round", previous, order)
            val keys = order.map { (id, name) -> prefs.getLong("$id:$name:throttled_at", 0) }
            if (keys.toSet().size < keys.size) ties++
            if (order != queue.sessions().flatMap { id -> queue.pending(id).sorted().map { id to it } }) reordered++
        }
        // The random states did exercise ties and real reordering.
        assertTrue(ties > 1000)
        assertTrue(reordered > 1000)
    }

    /** CYC-3422: defer_bulk gates non-priority apps on the active handoff session and sorts deferral-opted bulk smallest-first. */
    @Test fun deferBulkAndSmallestFirstOrderMatchesReferenceOverRandomQueues() {
        prefs.edit().clear().commit()
        var nowMs = 10_000_000_000L
        val queue = MigrationInstallQueue(RuntimeEnvironment.getApplication()).apply { clock = { nowMs } }
        val random = Random(3422)
        val pool = (0 until 14).map { "org.example.app%02d".format(it) }
        var deferredRounds = 0
        var expiredRounds = 0
        var sizeReordered = 0

        fun referenceOrder(): List<Pair<String, String>> {
            val handoffSession = prefs.getString("handoff_session", null)
            val handoffOutcome = prefs.contains("handoff_outcome")
            fun priorityList(id: String): List<String> {
                val stored = prefs.getString("$id:priority_order", null)
                if (stored != null) return stored.split(',').filter { it.isNotEmpty() }
                return prefs.getStringSet("$id:priority", emptySet()).orEmpty().sorted()
            }
            fun deferred(id: String): Boolean {
                if (id != handoffSession || handoffOutcome) return false
                if (!prefs.getBoolean("$id:defer_bulk", false)) return false
                if (prefs.getBoolean("$id:bulk_released", false)) return false
                if (prefs.getBoolean("$id:selection_closed", false)) return false
                if (prefs.getString("$id:transfer_outcome", null) != null) return false
                val at = prefs.getLong("$id:defer_bulk_at", 0L)
                return at <= 0L || nowMs - at < MigrationInstallQueue.MAX_DEFER_BULK_MS
            }
            return queue.sessions().flatMap { id ->
                val ordered = priorityList(id)
                val pset = ordered.toSet()
                val isDeferred = deferred(id)
                queue.pending(id).filter { !isDeferred || it in pset }.sorted().map { id to it }
            }.sortedWith(
                compareBy(
                    { (id, name) -> prefs.getLong("$id:$name:throttled_at", 0) },
                    { (id, name) ->
                        val pset = priorityList(id).toSet()
                        when {
                            name in pset -> 0
                            prefs.getBoolean("$id:$name:owner_account", false) -> 1
                            else -> 2
                        }
                    },
                    { (id, name) ->
                        if (!prefs.contains("$id:priority_order")) Int.MAX_VALUE
                        else priorityList(id).indexOf(name).let { if (it >= 0) it else Int.MAX_VALUE }
                    },
                    { (id, name) ->
                        val pset = priorityList(id).toSet()
                        val smallestFirst = prefs.getBoolean("$id:defer_opted_in", false) || prefs.getBoolean("$id:defer_bulk", false)
                        if (smallestFirst && name !in pset) {
                            val size = prefs.getLong("$id:$name:installSize", 0L)
                            if (size > 0L) size else Long.MAX_VALUE
                        } else {
                            Long.MAX_VALUE
                        }
                    },
                ),
            )
        }

        repeat(1500) { round ->
            nowMs = 10_000_000_000L + round * 1_000L
            val edit = prefs.edit().clear()
            val ids = (0 until 1 + random.nextInt(4)).map { "session-$it" }.shuffled(random)
            edit.putString("sessions", ids.joinToString(","))
            val handoffSession = if (random.nextInt(5) != 0) ids[random.nextInt(ids.size)] else null
            if (handoffSession != null) edit.putString("handoff_session", handoffSession)
            if (random.nextInt(6) == 0) edit.putString("handoff_outcome", "success")

            for (id in ids) {
                if (random.nextInt(8) != 0) edit.putStringSet("$id:pending", pool.filter { random.nextInt(3) != 0 }.toSet())
                val prio = pool.filter { random.nextInt(4) == 0 }.shuffled(random)
                if (random.nextBoolean()) {
                    edit.putString("$id:priority_order", prio.joinToString(","))
                    edit.putStringSet("$id:priority", prio.toSet())
                } else if (random.nextBoolean()) {
                    edit.putStringSet("$id:priority", prio.toSet())
                }
                val optedIn = if (id == handoffSession) random.nextInt(4) != 0 else random.nextBoolean()
                if (optedIn) edit.putBoolean("$id:defer_opted_in", true)
                val deferFlag = optedIn && (if (id == handoffSession) random.nextInt(4) != 0 else random.nextBoolean())
                if (deferFlag) {
                    edit.putBoolean("$id:defer_bulk", true)
                    when (random.nextInt(4)) {
                        0 -> Unit // legacy missing defer_bulk_at
                        1 -> edit.putLong("$id:defer_bulk_at", nowMs - MigrationInstallQueue.MAX_DEFER_BULK_MS - random.nextLong(0, 60_000L))
                        else -> edit.putLong("$id:defer_bulk_at", nowMs - random.nextLong(0, MigrationInstallQueue.MAX_DEFER_BULK_MS))
                    }
                }
                if (random.nextInt(5) == 0) edit.putBoolean("$id:bulk_released", true)
                if (random.nextInt(6) == 0) edit.putBoolean("$id:selection_closed", true)
                if (random.nextInt(7) == 0) edit.putString("$id:transfer_outcome", "success")
                for (name in pool) {
                    if (random.nextInt(4) != 0) edit.putLong("$id:$name:throttled_at", random.nextLong(0, 3))
                    if (random.nextInt(3) == 1) edit.putBoolean("$id:$name:owner_account", true)
                    when (random.nextInt(5)) {
                        0 -> Unit
                        1 -> edit.putLong("$id:$name:installSize", 0L)
                        else -> edit.putLong("$id:$name:installSize", random.nextLong(1, 6) * 25_000_000L)
                    }
                }
            }
            check(edit.commit())
            val expected = referenceOrder()
            val actual = queue.dispatchOrder()
            assertEquals("round $round", expected, actual)
            if (handoffSession != null && queue.isBulkDeferred(handoffSession)) deferredRounds++
            if (handoffSession != null && prefs.getBoolean("$handoffSession:defer_bulk", false) && !queue.isBulkDeferred(handoffSession)) expiredRounds++
            val alpha = queue.sessions().flatMap { id ->
                val pset = queue.priorityOrder(id).toSet()
                queue.pending(id).filter { !queue.isBulkDeferred(id) || it in pset }.sorted().map { id to it }
            }
            if (actual != alpha) sizeReordered++
        }
        assertTrue("random suite should exercise active deferral", deferredRounds > 150)
        assertTrue("random suite should exercise expired/released deferral", expiredRounds > 150)
        assertTrue("random suite should exercise real reordering", sizeReordered > 1000)
    }
}
