/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.vending.migration

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CYC-3430: a hand-off cancelled before any app was chosen has nothing to review and posts no notification. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationCancelledEmptyTransferTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val prefs get() = context.getSharedPreferences("migration-notifications", 0)
    private val run = "00000000-0000-0000-0000-000000000430"

    @Before fun setUp() {
        context.getSharedPreferences("migration-installs", 0).edit().clear().commit()
        prefs.edit().clear().commit()
        manager.cancelAll()
        MigrationSetupReceivers.clearForTests()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun queue(installed: Boolean = true) = MigrationInstallQueue(context) { operation, name ->
        Bundle().apply {
            putString("package_name", name)
            putString("status", if (installed) "installed" else if (operation == 2) "queued" else "none")
            putLong("version_code", 12)
            putBoolean("installed", installed)
            putBoolean("package_present", installed)
            putLong("installed_version_code", if (installed) 12 else 0)
        }
    }

    /** Opens Setup's hand-off and its catalog session, as Cyclon's copy flow does before the owner presses Copy. */
    private fun openHandoff(queue: MigrationInstallQueue): String {
        assertTrue(queue.beginHandoff(run, resumeOnly = false).getBoolean("accepted"))
        return queue.catalogSession()
    }

    private fun observe(queue: MigrationInstallQueue, id: String): Bundle =
        queue.summary(id, reschedule = false).also {
            MigrationNotifications.selectionOpen(context, id, it)
            if (it.getBoolean("complete")) MigrationNotifications.completed(context, id, it)
        }

    private fun select(queue: MigrationInstallQueue, vararg names: String) =
        queue.select(names.map { Bundle().apply { putString("package_name", it); putLong("version_code", 12) } })

    @Test fun emptyHandoffCancelledBeforeSelectionPostsNothingAndClearsTheOpenPrompt() {
        val queue = queue()
        val id = openHandoff(queue)
        // The empty, settled, open selection offers the "Finish app selection" prompt today.
        observe(queue, id)
        assertEquals("Finish app selection", manager.activeNotifications.single().notification.extras.getCharSequence("android.title").toString())

        assertTrue(queue.endHandoff(run, "cancelled").getBoolean("accepted"))
        val closed = queue.summary(id, reschedule = false)
        assertTrue(closed.getBoolean("complete"))
        assertEquals("cancelled", closed.getString("transfer_outcome"))
        // completed() alone clears the prompt, without relying on selectionOpen() running first.
        MigrationNotifications.completed(context, id, closed)
        assertEquals(0, manager.activeNotifications.size)
        assertTrue(prefs.getBoolean(id, false))
        assertFalse(prefs.getBoolean("$id:review_active", false))

        // Later job passes neither retry nor bring back either notification.
        repeat(2) { observe(queue, id) }
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun cancelledHandoffWithOnlyCatalogExclusionsPostsNothing() {
        val queue = queue()
        val id = openHandoff(queue)
        queue.recordCatalog(listOf(
            MigrationInstallQueue.unavailable("org.example.notfound", "not_found"),
            MigrationInstallQueue.unavailable("org.example.unsupported", "app_not_supported"),
        ))
        assertTrue(queue.endHandoff(run, "cancelled").getBoolean("accepted"))
        val state = observe(queue, id)
        assertTrue(state.getBoolean("complete"))
        val results = state.getParcelableArray("cyclon_app_results")!!.map { it as Bundle }
        assertEquals(2, results.size)
        assertTrue(results.all { it.getBoolean("excluded") })
        assertEquals(0, manager.activeNotifications.size)
        assertTrue(prefs.getBoolean(id, false))
    }

    @Test fun emptyCancelledTransferKeepsItsReceiptWhenNotificationsAreDenied() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val queue = queue()
        val id = openHandoff(queue)
        assertTrue(queue.endHandoff(run, "cancelled").getBoolean("accepted"))
        observe(queue, id)
        assertTrue(prefs.getBoolean(id, false))
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        observe(queue, id)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun cancelledHandoffWithSelectedAppsStillNotifies() {
        val queue = queue()
        val id = openHandoff(queue)
        select(queue, "org.example.app")
        queue.recordCatalog(listOf(MigrationInstallQueue.unavailable("org.example.notfound", "not_found")))
        queue.dispatch("org.example.app", id)
        assertTrue(queue.endHandoff(run, "cancelled").getBoolean("accepted"))
        val state = observe(queue, id)
        assertTrue(state.getBoolean("complete"))
        assertEquals("cancelled", state.getString("transfer_outcome"))
        val notification = manager.activeNotifications.single().notification
        assertEquals("Transferred apps are ready to review", notification.extras.getCharSequence("android.title").toString())
        assertEquals("1 of 2 apps installed. Review the remaining apps.", notification.extras.getCharSequence("android.text").toString())
        assertTrue(prefs.getBoolean(id, false))
    }

    @Test fun selectionIsJudgedFromTheStoredSelectionNotFromPendingInstalls() {
        // A chosen app that ended without installing still makes a cancelled session notify: the stored selection
        // (a non-excluded row) decides, not whether anything installed or is still pending.
        val summary = Bundle().apply {
            putBoolean("complete", true); putBoolean("installs_settled", true); putBoolean("selection_closed", true)
            putString("transfer_outcome", "cancelled")
            putParcelableArray("cyclon_app_results", arrayOf(Bundle().apply {
                putString("package_name", "org.example.app"); putString("status", "failed"); putString("error", "not_found")
                putBoolean("installed", false); putBoolean("terminal", true)
            }))
        }
        MigrationNotifications.completed(context, "transfer-one", summary)
        assertEquals("0 of 1 apps installed. Review the remaining apps.",
            manager.activeNotifications.single().notification.extras.getCharSequence("android.text").toString())
    }

    @Test fun successfulEmptyCopyIsUnchanged() {
        val queue = queue()
        val id = openHandoff(queue)
        assertTrue(queue.endHandoff(run, "success").getBoolean("accepted"))
        val state = observe(queue, id)
        assertTrue(state.getBoolean("complete"))
        assertEquals("success", state.getString("transfer_outcome"))
        val notification = manager.activeNotifications.single().notification
        assertEquals("Transferred apps are ready to review", notification.extras.getCharSequence("android.title").toString())
        assertEquals("0 apps installed", notification.extras.getCharSequence("android.text").toString())
    }

    @Test fun successfulCopyWithInstalledAppsIsUnchanged() {
        val queue = queue()
        val id = openHandoff(queue)
        select(queue, "org.example.app")
        queue.dispatch("org.example.app", id)
        assertTrue(queue.endHandoff(run, "success").getBoolean("accepted"))
        observe(queue, id)
        assertEquals("1 apps installed",
            manager.activeNotifications.single().notification.extras.getCharSequence("android.text").toString())
    }
}
