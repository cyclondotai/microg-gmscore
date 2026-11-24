/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationAppDataRestoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app = "com.example.thirdparty"

    @Before fun clear() {
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
    }

    private fun installRestoreProvider(
        provider: FakeRestoreTransportProvider,
        owner: String = "ai.cyclon.migration",
        flags: Int = ApplicationInfo.FLAG_SYSTEM,
        authority: String = "ai.cyclon.migration.restore.package",
    ) {
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val appInfo = ApplicationInfo().apply { packageName = owner; this.flags = flags }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = owner
            applicationInfo = appInfo
            providers = arrayOf(ProviderInfo().apply {
                this.authority = authority
                packageName = owner
                name = "$owner.RestorePackageProvider"
                applicationInfo = appInfo
            })
        })
    }

    private fun state(status: String, installed: Boolean = false, version: Long = 0) = Bundle().apply {
        putString("package_name", app)
        putString("status", status)
        putBoolean("installed", installed)
        putLong("installed_version_code", version)
        putLong("version_code", 12)
    }

    @Test fun singlePackageRestoreTriggersOnceOffMainThreadWhenAppTransitionsToInstalled() {
        val provider = FakeRestoreTransportProvider(response = Bundle().apply {
            putBoolean("ok", true)
            putString("status", "restored")
        })
        installRestoreProvider(provider)

        var installed = false
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (installed) state("installed", installed = true, version = 12)
            else state("downloading")
        }
        val sessionId = queue.beginTransfer()
        assertEquals(listOf("transferStarting"), provider.calls.map { it.method })
        queue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        queue.dispatch(app, sessionId)
        assertTrue("must not trigger restorePackage while still downloading", provider.calls.none { it.method == "restorePackage" })

        installed = true
        val summary = queue.summary(sessionId, reschedule = false)
        assertTrue(summary.getBoolean("all_installed"))
        assertTrue("expected async restorePackage call", provider.called.await(5, TimeUnit.SECONDS))
        val restoreCalls = provider.calls.filter { it.method == "restorePackage" }
        assertEquals(1, restoreCalls.size)
        val call = restoreCalls.single()
        assertEquals("restorePackage", call.method)
        assertEquals(app, call.arg)
        assertEquals(sessionId, call.sessionId)
        assertTrue("d2d_seen_elapsed_ms must be forwarded", call.d2dSeenElapsedMs >= 0L)
        assertNotEquals("restorePackage must run off the main thread", "main", call.threadName)

        // Repeated summary polls do not re-trigger restorePackage for the same installed app.
        queue.summary(sessionId, reschedule = false)
        queue.summary(sessionId, reschedule = false)
        assertEquals(1, provider.calls.count { it.method == "restorePackage" })

        // Direct in-Switch retry calling catalogSession after prior selection signals transferStarting again.
        val retrySession = queue.catalogSession()
        assertNotEquals(sessionId, retrySession)
        assertEquals(2, provider.calls.count { it.method == "transferStarting" })
    }

    @Test fun droppedTransferStartingBlocksRestoreAndRecordOptionsNeverEmitsLateMarker() {
        val provider = FakeRestoreTransportProvider(
            response = Bundle().apply { putBoolean("ok", true); putString("status", "restored") },
            transferStartingResponse = null,
        )
        installRestoreProvider(provider)

        var installed = false
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (installed) state("installed", installed = true, version = 12)
            else state("downloading")
        }
        // Direct copy whose transferStarting reply is dropped (null) must NOT restore against a prior batch.
        val unacked = queue.catalogSession()
        assertEquals(listOf("transferStarting"), provider.calls.map { it.method })
        queue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        queue.dispatch(app, unacked)
        installed = true
        queue.summary(unacked, reschedule = false)
        assertTrue(queue.processPendingAppDataRestores())
        assertTrue("dropped transferStarting must never call restorePackage", provider.calls.none { it.method == "restorePackage" })

        // Calling recordOptions directly without a prior session (e.g. after batchStarted) never emits a late
        // transferStarting marker that could poison the companion's unpromoted ceiling.
        clear()
        provider.calls.clear()
        installed = false
        queue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        assertTrue("recordOptions must never emit transferStarting", provider.calls.isEmpty())
    }

    @Test fun busyRestoreWhileSwitchHoldsLeaseDoesNotExhaustAttemptsAndRetriesUntilRestored() {
        var replyStatus = "busy"
        val calls = mutableListOf<Pair<String, String>>()
        var installed = false
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (installed) state("installed", installed = true, version = 12)
            else state("downloading")
        }.apply {
            appDataRestore = { pkg, sid ->
                calls += pkg to sid
                MigrationAppDataRestore.Result(ok = replyStatus == "restored", status = replyStatus)
            }
        }
        val sessionId = queue.beginTransfer()
        queue.recordOptions(Bundle().apply { putInt("restore_source", 2) }) // Android Wi-Fi D2D
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        queue.dispatch(app, sessionId)

        installed = true
        queue.summary(sessionId, reschedule = false)
        assertEquals(1, calls.size)
        assertEquals(setOf(app), queue.pendingAppDataRestores(sessionId))

        // 30 consecutive busy polls while Switch still owns the lease must NOT exhaust the 20-attempt error cap.
        var now = 1_000_000L
        repeat(30) {
            now += 60_000L
            assertFalse(queue.processPendingAppDataRestores(clock = { now }))
            assertEquals(setOf(app), queue.pendingAppDataRestores(sessionId))
        }
        assertEquals(31, calls.size)

        // Once Switch releases the lease, the next round restores and clears pending.
        replyStatus = "restored"
        assertTrue(queue.dispatchRound())
        assertEquals(32, calls.size)
        assertTrue(queue.pendingAppDataRestores(sessionId).isEmpty())

        // Busy retries still stop after the 6-hour wall-clock ceiling so a permanently leaked lease never polls forever.
        val stuckApp = "com.example.stuck"
        var stuckInstalled = false
        val stuckQueue = MigrationInstallQueue(context) { operation, _ ->
            Bundle().apply {
                putString("package_name", stuckApp)
                putString("status", if (operation == 2) "queued" else if (stuckInstalled) "installed" else "downloading")
                putBoolean("installed", stuckInstalled)
                putLong("installed_version_code", if (stuckInstalled) 5 else 0)
                putLong("version_code", 5)
            }
        }.apply {
            appDataRestore = { _, _ -> MigrationAppDataRestore.Result(ok = false, status = "busy") }
        }
        val stuckSession = stuckQueue.beginTransfer()
        stuckQueue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        stuckQueue.select(listOf(Bundle().apply { putString("package_name", stuckApp); putLong("version_code", 5) }))
        stuckQueue.dispatch(stuckApp, stuckSession)
        stuckInstalled = true
        stuckQueue.summary(stuckSession, reschedule = false)
        assertEquals(setOf(stuckApp), stuckQueue.pendingAppDataRestores(stuckSession))
        val farFuture = System.currentTimeMillis() + 7L * 60L * 60_000L
        assertTrue(stuckQueue.processPendingAppDataRestores(clock = { farFuture }))
        assertTrue("busy past 6h ceiling should stop retrying", stuckQueue.pendingAppDataRestores(stuckSession).isEmpty())

        // Non-busy errors still respect the bounded attempt budget so broken restores don't retry forever.
        val errApp = "com.example.broken"
        val errQueue = MigrationInstallQueue(context) { operation, _ ->
            Bundle().apply {
                putString("package_name", errApp)
                putString("status", if (operation == 2) "queued" else "installed")
                putBoolean("installed", operation != 2)
                putLong("installed_version_code", if (operation != 2) 5 else 0)
                putLong("version_code", 5)
            }
        }.apply {
            appDataRestore = { _, _ -> MigrationAppDataRestore.Result(ok = false, status = "error") }
        }
        val errSession = errQueue.beginTransfer()
        errQueue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        errQueue.select(listOf(Bundle().apply { putString("package_name", errApp); putLong("version_code", 5) }))
        errQueue.dispatch(errApp, errSession)
        errQueue.summary(errSession, reschedule = false) // attempt 1
        repeat(19) { errQueue.processPendingAppDataRestores() } // attempts 2..20
        assertTrue("20 non-busy errors should settle pending restore", errQueue.pendingAppDataRestores(errSession).isEmpty())
    }

    @Test fun nonAndroidD2dSourcesPreexistingAppsAndTransientReadErrorsNeverTriggerAppDataRestore() {
        val calls = mutableListOf<String>()
        // 1. iOS cable (5), cloud restore (7), and unset restore_source never trigger local Android D2D app-data restore.
        for (source in listOf(5, 6, 7, null)) {
            var installed = false
            val q = MigrationInstallQueue(context) { operation, _ ->
                if (operation == 2) state("queued")
                else if (installed) state("installed", installed = true, version = 12)
                else state("downloading")
            }.apply { appDataRestore = { pkg, _ -> calls += pkg; MigrationAppDataRestore.Result(true, "restored") } }
            val sid = q.beginTransfer()
            if (source != null) q.recordOptions(Bundle().apply { putInt("restore_source", source) })
            q.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
            q.dispatch(app, sid)
            installed = true
            q.summary(sid, reschedule = false)
            assertTrue("restore_source=$source must not call restorePackage", calls.isEmpty())
        }

        // 2. App already installed on the phone before dispatch never triggers restorePackage, even if an earlier poll hit store_unavailable.
        var storeOnline = false
        val preexistingQueue = MigrationInstallQueue(context) { _, _ ->
            if (!storeOnline) MigrationInstallQueue.unavailable(app, "store_unavailable")
            else state("installed", installed = true, version = 12)
        }.apply { appDataRestore = { pkg, _ -> calls += pkg; MigrationAppDataRestore.Result(true, "restored") } }
        val a2aSession = preexistingQueue.beginTransfer()
        preexistingQueue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        preexistingQueue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        preexistingQueue.summary(a2aSession, reschedule = false) // transient store_unavailable while pending
        storeOnline = true
        preexistingQueue.dispatch(app, a2aSession)
        preexistingQueue.summary(a2aSession, reschedule = false)
        assertTrue("preexisting app after store_unavailable must not overwrite owner's live data", calls.isEmpty())

        // 3. Stale transfer's pending restore is dropped when a newer transfer begins.
        var newerInstalled = false
        val staleQueue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (newerInstalled) state("installed", installed = true, version = 12)
            else state("downloading")
        }.apply { appDataRestore = { pkg, _ -> calls += pkg; MigrationAppDataRestore.Result(false, "busy") } }
        val firstSession = staleQueue.beginTransfer()
        staleQueue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        staleQueue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        staleQueue.dispatch(app, firstSession)
        newerInstalled = true
        staleQueue.summary(firstSession, reschedule = false)
        assertEquals(setOf(app), staleQueue.pendingAppDataRestores(firstSession))
        val secondSession = staleQueue.beginTransfer()
        assertNotEquals(firstSession, secondSession)
        calls.clear()
        assertTrue(staleQueue.processPendingAppDataRestores())
        assertTrue("stale session restore must not consult new session's staging dir", calls.isEmpty())
        assertTrue(staleQueue.pendingAppDataRestores(firstSession).isEmpty())

        // 4. Queued triggerAsync skips RPC when shouldRun returns false (session superseded while queued).
        val provider = FakeRestoreTransportProvider()
        installRestoreProvider(provider)
        val skippedLatch = CountDownLatch(1)
        var skippedStatus: String? = null
        MigrationAppDataRestore.triggerAsync(context, app, firstSession, shouldRun = { false }) { res ->
            skippedStatus = res.status
            skippedLatch.countDown()
        }
        assertTrue(skippedLatch.await(5, TimeUnit.SECONDS))
        assertEquals("stale_session", skippedStatus)
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun absentOrUntrustedProviderIsGracefulNoOpWithoutFailingInstall() {
        // Provider not registered at all -> graceful unsupported no-op.
        val missingResult = MigrationAppDataRestore.restorePackage(context, app, "session-1")
        assertFalse(missingResult.ok)
        assertEquals("unsupported", missingResult.status)

        // Untrusted non-system package claiming the authority -> ignored.
        val fake = FakeRestoreTransportProvider()
        installRestoreProvider(fake, owner = "org.example.untrusted", flags = ApplicationInfo.FLAG_SYSTEM)
        assertEquals("unsupported", MigrationAppDataRestore.restorePackage(context, app, "session-1").status)
        assertTrue(fake.calls.isEmpty())

        // Non-system ai.cyclon.migration -> ignored.
        installRestoreProvider(fake, owner = "ai.cyclon.migration", flags = 0)
        assertEquals("unsupported", MigrationAppDataRestore.restorePackage(context, app, "session-1").status)
        assertTrue(fake.calls.isEmpty())

        // Install completion still succeeds even with no restore provider installed.
        val queue = MigrationInstallQueue(context) { _, _ -> state("installed", installed = true, version = 12) }
        val id = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        val summary = queue.summary(id, reschedule = false)
        assertTrue(summary.getBoolean("all_installed"))
    }

    @Test fun nullUnsupportedLegacyOrThrowingProviderResponsesNeverFailInstall() {
        for (case in listOf(
            FakeRestoreTransportProvider(response = null),
            FakeRestoreTransportProvider(response = Bundle().apply { putBoolean("success", false) }),
            FakeRestoreTransportProvider(response = Bundle().apply { putBoolean("ok", false); putString("status", "unsupported") }),
            FakeRestoreTransportProvider(response = Bundle().apply { putBoolean("ok", true); putString("status", "no_data") }),
            FakeRestoreTransportProvider(failure = IllegalArgumentException("Unknown restore method")),
            FakeRestoreTransportProvider(failure = SecurityException("Missing BACKUP permission")),
        )) {
            installRestoreProvider(case)
            val result = MigrationAppDataRestore.restorePackage(context, app, "session-1")
            assertNotNull(result.status)
            assertEquals(1, case.calls.size)
        }

        // Legacy authority fallback still works when only ai.cyclon.migration.restore is registered.
        val legacy = FakeRestoreTransportProvider(response = Bundle().apply {
            putBoolean("ok", true)
            putString("status", "restored")
        })
        installRestoreProvider(legacy, authority = "ai.cyclon.migration.restore")
        val legacyResult = MigrationAppDataRestore.restorePackage(context, app, "session-1")
        assertTrue(legacyResult.ok)
        assertEquals("restored", legacyResult.status)
        assertEquals(1, legacy.calls.size)
    }

    @Test fun handoffReportsRestoringDataUntilA2aRestoreSettlesAndExcludesProvidedByOs() {
        val run = "00000000-0000-0000-0000-000000000009"
        var replyStatus = "busy"
        var installed = false
        val queue = MigrationInstallQueue(context) { operation, name ->
            Bundle().apply {
                putString("package_name", name)
                putString("status", if (operation == 2) "queued" else if (installed) "installed" else "downloading")
                putBoolean("installed", installed)
                putLong("installed_version_code", if (installed) 12 else 0)
                putLong("version_code", 12)
            }
        }.apply {
            appDataRestore = { _, _ -> MigrationAppDataRestore.Result(ok = replyStatus == "restored", status = replyStatus) }
        }
        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        val sessionId = queue.catalogSession()
        queue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        // OS-provided apps (e.g. CyclonClockBridge com.google.android.deskclock) must not appear as failed exclusions.
        queue.recordCatalog(listOf(
            Bundle().apply { putString("package_name", app); putString("title", "Third Party"); putInt("availability", 1); putLong("version_code", 12) },
            MigrationInstallQueue.unavailable("com.google.android.deskclock", "provided_by_os"),
        ))
        queue.select(listOf(Bundle().apply { putString("package_name", app); putString("title", "Third Party"); putLong("version_code", 12) }))
        queue.dispatch(app, sessionId)

        // Even before summary() runs, once PackageManager sees the newly dispatched APK at expected version,
        // handoff() must already report restoring_data so Setup cannot race ahead of summary() + restorePackage().
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = app
            longVersionCode = 12L
            applicationInfo = ApplicationInfo().apply { packageName = app }
        })
        assertEquals(listOf("restoring_data"), queue.handoff(run).getStringArrayList("statuses"))

        installed = true
        val summary = queue.summary(sessionId, reschedule = false)
        assertTrue(summary.getBoolean("all_installed"))
        val restoring = queue.handoff(run)
        assertEquals(listOf(app), restoring.getStringArrayList("packages"))
        assertEquals(listOf("restoring_data"), restoring.getStringArrayList("statuses"))

        // Once Switch releases the backup transport lease and restore finishes, handoff flips to installed.
        replyStatus = "restored"
        assertTrue(queue.processPendingAppDataRestores())
        val settled = queue.handoff(run)
        assertEquals(listOf("installed"), settled.getStringArrayList("statuses"))

        // A superseded transfer's handoff must never remain stuck in restoring_data.
        val run2 = "00000000-0000-0000-0000-00000000000a"
        replyStatus = "busy"
        installed = false
        assertTrue(queue.beginHandoff(run2, resumeOnly = false).getBoolean("accepted"))
        val s2 = queue.catalogSession()
        queue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        queue.dispatch(app, s2)
        installed = true
        queue.summary(s2, reschedule = false)
        assertEquals(listOf("restoring_data"), queue.handoff(run2).getStringArrayList("statuses"))
        queue.beginTransfer()
        assertEquals(listOf("installed"), queue.handoff(run2).getStringArrayList("statuses"))
    }

    private data class Call(
        val method: String,
        val arg: String?,
        val sessionId: String?,
        val d2dSeenElapsedMs: Long,
        val threadName: String,
    )

    private class FakeRestoreTransportProvider(
        private val response: Bundle? = Bundle().apply { putBoolean("ok", true); putString("status", "restored") },
        private val failure: RuntimeException? = null,
        private val transferStartingResponse: Bundle? = Bundle().apply { putBoolean("success", true) },
    ) : ContentProvider() {
        val calls = CopyOnWriteArrayList<Call>()
        val called = CountDownLatch(1)

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            calls += Call(
                method = method,
                arg = arg,
                sessionId = extras?.getString("sessionId"),
                d2dSeenElapsedMs = extras?.getLong("d2d_seen_elapsed_ms", -1L) ?: -1L,
                threadName = Thread.currentThread().name,
            )
            if (method == MigrationAppDataRestore.METHOD_TRANSFER_STARTING) {
                failure?.let { throw it }
                return transferStartingResponse?.let(::Bundle)
            }
            if (method == MigrationAppDataRestore.METHOD_RESTORE_PACKAGE) called.countDown()
            failure?.let { throw it }
            return response?.let(::Bundle)
        }

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
