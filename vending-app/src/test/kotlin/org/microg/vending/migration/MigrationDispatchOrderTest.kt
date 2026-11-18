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
}
