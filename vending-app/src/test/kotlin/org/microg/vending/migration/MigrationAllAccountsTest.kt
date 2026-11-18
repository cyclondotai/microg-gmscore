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

/**
 * CYC-2499: an Aurora that tries every account itself (all_accounts_install) gets one install request per app, and
 * FakeStore no longer queues a second one for the Google account. 11-17/18 founders-02: that second request broke twice
 * (a refusal in the install reply was not recognised; the retry waited for the pass).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationAllAccountsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val name = "org.example.app"
    private fun selected() = listOf(Bundle().apply { putString("package_name", name); putLong("version_code", 12) })
    private fun state(status: String, installed: Boolean = false, version: Long = 0, offer: Long = 12) = Bundle().apply {
        putString("package_name", name); putString("status", status)
        putBoolean("installed", installed); putLong("installed_version_code", version); putLong("version_code", offer)
    }
    private fun refused() = state("failed").apply { putString("error", "failed"); putString("errorCode", "app_not_purchased") }
    private fun capabilities(allAccounts: Boolean) = Bundle().apply {
        putBoolean("owner_account_fallback", true); putBoolean("all_accounts_install", allAccounts)
    }
    private fun results(queue: MigrationInstallQueue) =
        queue.summary().getParcelableArray("cyclon_app_results")!!.map { it as Bundle }.single()
    @Before fun clear() { context.getSharedPreferences("migration-installs", 0).edit().clear().commit() }

    @Test fun theInstallRequestAsksForEveryAccountOnlyWhenAuroraSupportsIt() {
        for (supported in listOf(true, false)) {
            clear()
            val sent = mutableListOf<Bundle>()
            val queue = MigrationInstallQueue(context, { _, _ -> capabilities(supported) }) { operation, _ ->
                if (operation == 3) state("none") else state("queued")
            }.apply { sentOptions = { operation, options -> if (operation == 2) sent += options!! } }
            queue.select(selected())
            assertTrue(queue.dispatch(name))
            val options = sent.single()
            assertEquals(supported, options.getBoolean("all_accounts"))
            // The older two-step fallback's promise only for an Aurora without it.
            assertEquals(!supported, options.getBoolean("owner_retry_on_refusal"))
            assertFalse(options.getBoolean("owner_account_approved"))
            assertEquals(12L, options.getLong("minimum_version_code"))
        }
    }

    /** Aurora already asked the Google accounts: its refusal in the reply is the app's result, not a second request. */
    @Test fun aRefusalInTheReplyIsFinalWhenAuroraTriedEveryAccount() {
        for (error in listOf("not_found", "app_not_purchased")) {
            clear()
            val sent = mutableListOf<Bundle>()
            val queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { operation, _ ->
                when {
                    operation == 3 -> state("none")
                    error == "not_found" -> MigrationInstallQueue.unavailable(name, error)
                    else -> refused()
                }
            }.apply { sentOptions = { operation, options -> if (operation == 2) sent += options!! } }
            queue.select(selected())
            assertTrue(error, queue.dispatch(name))
            assertTrue(error, queue.pending().isEmpty())
            assertEquals(error, 1, sent.size)
            val app = results(queue)
            assertTrue(error, app.getBoolean("terminal")); assertFalse(app.getBoolean("installed"))
            assertTrue(error, queue.pending().isEmpty())
        }
    }

    /** The download's own refusal (Aurora's last account's) is final too: summary queues nothing for the Google account. */
    @Test fun aRefusedDownloadIsNotQueuedAgainForTheGoogleAccount() {
        val sent = mutableListOf<Bundle>()
        var download = state("none")
        val queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { operation, _ ->
            if (operation == 3) Bundle(download) else state("queued").also { download = it }
        }.apply { sentOptions = { operation, options -> if (operation == 2) sent += options!! } }
        queue.select(selected())
        assertTrue(queue.dispatch(name))
        download = refused()
        val failed = results(queue)
        assertEquals("failed", failed.getString("status")); assertTrue(failed.getBoolean("terminal"))
        assertTrue(queue.pending().isEmpty())
        assertTrue("nothing is left to hand over", queue.dispatchRound())
        assertEquals(1, sent.size)
        // The owner's Retry asks for every account again, starting with the anonymous session.
        queue.retry(queue.currentSessionId()!!)
        assertTrue(queue.dispatch(name))
        assertEquals(2, sent.size)
        assertTrue(sent.last().getBoolean("all_accounts")); assertFalse(sent.last().getBoolean("owner_account_approved"))
    }

    /** As for FakeStore's own Google-account request (10-30 customer-98), the account's older offer is the app. */
    @Test fun theVersionPlayOffersTheGoogleAccountAuroraWentOnToIsTheFloor() {
        var download = state("none")
        val queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { operation, _ ->
            if (operation == 3) Bundle(download) else state("queued").also { download = it }
        }
        queue.select(selected())
        assertTrue(queue.dispatch(name))
        // Play refused the anonymous session; Aurora's download went on to the Google account, which Play offers 9.
        download = state("downloading", offer = 9).apply { putBoolean("google_account", true) }
        val downloading = results(queue)
        assertFalse(downloading.getBoolean("terminal"))
        assertTrue("an older offer of the Google account is no older attempt to repeat", queue.pending().isEmpty())
        download = state("installed", installed = true, version = 9, offer = 9).apply { putBoolean("google_account", true) }
        val installed = results(queue)
        assertTrue(installed.getBoolean("installed")); assertTrue(installed.getBoolean("terminal"))
    }

    /** Without the flag (the anonymous session or the mirror installed it) the listed version stays the floor. */
    @Test fun anOlderAnonymousOfferIsStillNotTheApp() {
        var download = state("none")
        val queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { operation, _ ->
            if (operation == 3) Bundle(download) else state("queued").also { download = it }
        }
        queue.select(selected())
        assertTrue(queue.dispatch(name))
        download = state("installed", installed = true, version = 9, offer = 9)
        assertFalse(results(queue).getBoolean("installed"))
    }

    /** An all-accounts attempt Aurora finished with the Google account (google_account) at an older version than listed. */
    private fun refusedByGoogleAccount() = refused().apply { putBoolean("google_account", true); putLong("version_code", 7) }

    /**
     * Greptile on #102: after an all-accounts attempt failed with google_account and an older version_code, an owner Retry
     * must not adopt that attempt's account choice from Aurora's status, which still shows it: the new request asks for
     * the listed version, without the Google account's approval.
     */
    @Test fun aRetryDoesNotAdoptThePreviousAttemptsGoogleAccount() {
        val sent = mutableListOf<Bundle>()
        val queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { _, _ -> refusedByGoogleAccount() }
            .apply { sentOptions = { operation, options -> if (operation == 2) sent += options!! } }
        queue.select(selected())
        val id = queue.currentSessionId()!!
        assertTrue(queue.dispatch(name))
        assertTrue("the attempt failed for good", queue.pending().isEmpty())
        queue.retry(id, name)
        assertEquals(setOf(name), queue.pending())
        queue.dispatch(name)
        val again = sent.last()
        assertEquals(2, sent.size)
        assertEquals(12L, again.getLong("minimum_version_code"))
        assertFalse(again.getBoolean("owner_account_approved"))
    }

    /** Greptile on #102: a status asked before the app's records changed changes nothing, not even the account choice. */
    @Test fun aStaleStatusChangesNothingBeforeTheRevisionCheck() {
        lateinit var queue: MigrationInstallQueue
        var changeDuringAsk = false
        queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { operation, _ ->
            // While Aurora is asked about the app, its selection is recorded again (its records change).
            if (operation == 3 && changeDuringAsk) { changeDuringAsk = false; queue.select(selected()) }
            refusedByGoogleAccount()
        }
        queue.select(selected())
        assertTrue(queue.dispatch(name))
        // A new attempt for the same app is pending (Switch handed it again), and Aurora still shows the earlier one.
        val prefs = context.getSharedPreferences("migration-installs", 0)
        val id = queue.currentSessionId()!!
        check(prefs.edit().putStringSet("$id:pending", setOf(name)).putBoolean("$id:$name:owner_account", false).remove("$id:$name:ownerVersion").commit())
        changeDuringAsk = true
        assertFalse("decided in the next round", queue.dispatch(name))
        assertFalse(prefs.getBoolean("$id:$name:owner_account", false))
        assertFalse(prefs.contains("$id:$name:ownerVersion"))
    }

    /** Review of #103: a Retry Aurora rate-limits before enqueuing leaves the old row, and a later summary never adopts it. */
    @Test fun aRateLimitedRetryNeverAdoptsTheOldAttemptsGoogleAccount() {
        val sent = mutableListOf<Bundle>()
        var limited = false
        val queue = MigrationInstallQueue(context, { _, _ -> capabilities(true) }) { operation, _ ->
            if (operation == 2 && limited) MigrationInstallQueue.unavailable(name, "rate_limited").apply { putString("status", "failed") }
            else refusedByGoogleAccount()
        }.apply { sentOptions = { operation, options -> if (operation == 2) sent += options!! } }
        queue.select(selected())
        val id = queue.currentSessionId()!!
        assertTrue(queue.dispatch(name))
        queue.retry(id, name)
        limited = true
        assertFalse("rate limited: tried again later", queue.dispatch(name))
        queue.summary(id, reschedule = false)
        limited = false
        queue.dispatch(name)
        val again = sent.last()
        assertEquals(12L, again.getLong("minimum_version_code"))
        assertFalse(again.getBoolean("owner_account_approved"))
    }
}
