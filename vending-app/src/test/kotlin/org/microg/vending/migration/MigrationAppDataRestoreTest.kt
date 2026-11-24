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
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        queue.dispatch(app, sessionId)
        assertTrue("must not trigger while still downloading", provider.calls.isEmpty())

        installed = true
        val summary = queue.summary(sessionId, reschedule = false)
        assertTrue(summary.getBoolean("all_installed"))
        assertTrue("expected async restorePackage call", provider.called.await(5, TimeUnit.SECONDS))
        assertEquals(1, provider.calls.size)
        val call = provider.calls.single()
        assertEquals("restorePackage", call.method)
        assertEquals(app, call.arg)
        assertEquals(sessionId, call.sessionId)
        assertNotEquals("restorePackage must run off the main thread", "main", call.threadName)

        // Repeated summary polls do not re-trigger restorePackage for the same installed app.
        queue.summary(sessionId, reschedule = false)
        queue.summary(sessionId, reschedule = false)
        assertEquals(1, provider.calls.size)
    }

    @Test fun busyRestoreWhileSwitchHoldsLeaseRetriesOnLaterRoundAndSucceeds() {
        val statuses = ArrayDeque(listOf(
            MigrationAppDataRestore.Result(ok = false, status = "busy"),
            MigrationAppDataRestore.Result(ok = true, status = "restored"),
        ))
        val calls = mutableListOf<Pair<String, String>>()
        var installed = false
        val queue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (installed) state("installed", installed = true, version = 12)
            else state("downloading")
        }.apply {
            appDataRestore = { pkg, sid ->
                calls += pkg to sid
                statuses.removeFirst()
            }
        }
        val sessionId = queue.beginTransfer()
        queue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        queue.dispatch(app, sessionId)

        installed = true
        queue.summary(sessionId, reschedule = false)
        assertEquals(listOf(app to sessionId), calls)
        assertEquals(setOf(app), queue.pendingAppDataRestores(sessionId))

        // Next round retries after Switch releases the lease and clears pending on restored.
        assertTrue(queue.dispatchRound())
        assertEquals(listOf(app to sessionId, app to sessionId), calls)
        assertTrue(queue.pendingAppDataRestores(sessionId).isEmpty())
    }

    @Test fun iosTransfersAndPreexistingAppsNeverTriggerAppDataRestore() {
        val calls = mutableListOf<String>()
        // 1. iOS cable transfer (restore_source=5) never triggers Android app-data restore.
        var installed = false
        val iosQueue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (installed) state("installed", installed = true, version = 12)
            else state("downloading")
        }.apply { appDataRestore = { pkg, _ -> calls += pkg; MigrationAppDataRestore.Result(true, "restored") } }
        val iosSession = iosQueue.beginTransfer()
        iosQueue.recordOptions(Bundle().apply { putInt("restore_source", 5) })
        iosQueue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        iosQueue.dispatch(app, iosSession)
        installed = true
        iosQueue.summary(iosSession, reschedule = false)
        assertTrue("iOS transfer must not call restorePackage", calls.isEmpty())

        // 2. App already installed on the phone before dispatch never triggers restorePackage.
        val preexistingQueue = MigrationInstallQueue(context) { _, _ ->
            state("installed", installed = true, version = 12)
        }.apply { appDataRestore = { pkg, _ -> calls += pkg; MigrationAppDataRestore.Result(true, "restored") } }
        val a2aSession = preexistingQueue.beginTransfer()
        preexistingQueue.recordOptions(Bundle().apply { putInt("restore_source", 1) })
        preexistingQueue.select(listOf(Bundle().apply { putString("package_name", app); putLong("version_code", 12) }))
        preexistingQueue.dispatch(app, a2aSession)
        preexistingQueue.summary(a2aSession, reschedule = false)
        assertTrue("preexisting app must not overwrite owner's live data", calls.isEmpty())

        // 3. Stale transfer's pending restore is dropped when a newer transfer begins.
        var newerInstalled = false
        val staleQueue = MigrationInstallQueue(context) { operation, _ ->
            if (operation == 2) state("queued")
            else if (newerInstalled) state("installed", installed = true, version = 12)
            else state("downloading")
        }.apply { appDataRestore = { pkg, _ -> calls += pkg; MigrationAppDataRestore.Result(false, "busy") } }
        val firstSession = staleQueue.beginTransfer()
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

    private data class Call(val method: String, val arg: String?, val sessionId: String?, val threadName: String)

    private class FakeRestoreTransportProvider(
        private val response: Bundle? = Bundle().apply { putBoolean("ok", true); putString("status", "restored") },
        private val failure: RuntimeException? = null,
    ) : ContentProvider() {
        val calls = CopyOnWriteArrayList<Call>()
        val called = CountDownLatch(1)

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            calls += Call(method, arg, extras?.getString("sessionId"), Thread.currentThread().name)
            called.countDown()
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
