/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration

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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationRestoreTransportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun provider(packageName: String = "ai.cyclon.migration", flags: Int = ApplicationInfo.FLAG_SYSTEM) = RestoreTransportProvider().also {
        ShadowContentResolver.registerProviderInternal("ai.cyclon.migration.restore", it)
        installRestoreTransportOwner(context, packageName, flags)
    }

    @Test fun olderImageWithoutTheCompanionProviderKeepsTheExistingTransferPath() {
        val unregistered = RestoreTransportProvider()
        ShadowContentResolver.registerProviderInternal("ai.cyclon.migration.restore", unregistered)
        MigrationRestoreTransport(context).acquire()
        assertTrue(unregistered.calls.isEmpty())
    }

    @Test fun anotherPackageOrNonSystemCompanionIsNeverCalled() {
        for ((name, flags) in listOf("org.example.other" to ApplicationInfo.FLAG_SYSTEM, "ai.cyclon.migration" to 0)) {
            val provider = provider(name, flags)
            MigrationRestoreTransport(context).acquire()
            assertTrue(provider.calls.isEmpty())
        }
    }

    @Test fun updatedSystemCompanionStillAcquiresTheLease() {
        val provider = provider(flags = ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        MigrationRestoreTransport(context).acquire()
        assertEquals(listOf("acquire" to null), provider.calls)
    }

    @Test fun acquireUsesTheCompanionContractAndAcceptsAnAlreadySelectedTransport() {
        val provider = provider()
        MigrationRestoreTransport(context).acquire()
        provider.leaseId = null
        MigrationRestoreTransport(context).acquire()
        assertEquals(listOf("acquire" to null, "acquire" to null), provider.calls)
    }

    @Test fun deniedOrMissingAcquireResponseNeverClaimsALease() {
        val provider = provider()
        val selection = MigrationRestoreTransport(context)
        provider.success = false
        assertThrows(IllegalStateException::class.java) { selection.acquire() }
        provider.nullResponse = true
        assertThrows(IllegalStateException::class.java) { selection.acquire() }
        provider.failure = SecurityException("Denied")
        assertThrows(SecurityException::class.java) { selection.acquire() }
    }

    @Test fun notifyBatchStartedNeverCallsUntrustedProviders() {
        for ((name, flags) in listOf("org.example.other" to ApplicationInfo.FLAG_SYSTEM, "ai.cyclon.migration" to 0)) {
            val bad = provider(name, flags)
            MigrationRestoreTransport(context).notifyBatchStarted()
            assertTrue(bad.calls.isEmpty())
        }
    }

    @Test fun notifyBatchStartedSignalsEachA2aStagingBatchOnceAndIgnoresOlderCompanions() {
        // Trusted system companion receives batchStarted once per Android D2D staging batch (tx 3 startWrite),
        // even when a batch stages multiple packages, and fires again when a second batch starts.
        val trusted = provider()
        val descriptor = "com.google.android.gms.backup.migrate.helper.ID2dMigrateHelper"
        fun startWrite(helper: EmptyMigrateHelper, pkg: String) {
            val file = java.io.File.createTempFile("migration", ".fixture", context.cacheDir).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = 0
            val cb = object : android.os.Binder() {
                override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.google.android.gms.backup.migrate.helper.IVoidCallbacks")
                    result = code
                    latch.countDown()
                    return true
                }
            }
            val req = android.os.Parcel.obtain()
            val reply = android.os.Parcel.obtain()
            try {
                req.writeInterfaceToken(descriptor)
                req.writeString(pkg)
                req.writeInt(1)
                pfd.writeToParcel(req, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                req.writeStrongBinder(cb)
                // Switch invokes ID2dMigrateHelper transactions one-way (FLAG_ONEWAY, where callingPid == 0 on device).
                assertTrue(helper.transact(3, req, null, android.os.IBinder.FLAG_ONEWAY))
            } finally {
                req.recycle()
                reply.recycle()
            }
            assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, result)
        }

        val batch1 = EmptyMigrateHelper(android.os.Binder.getCallingUid(), context)
        // Drop the first batchStarted notification (simulating transient delivery failure): the next write
        // retries with the exact same monotonic batchSeq (1) and stops once acknowledged.
        trusted.failNextBatchStarted = 1
        startWrite(batch1, "com.example.one")
        startWrite(batch1, "com.example.two")
        startWrite(batch1, "com.example.three")
        val batch2 = EmptyMigrateHelper(android.os.Binder.getCallingUid(), context)
        // Drop batch2 during staging completely; MigrationBackupTransport.getAvailableRestoreSets() still returns
        // the staged RestoreSet (so Switch tx 6 system restore never aborts), tagging device="D2D:pending" while
        // publication fails and returning device="D2D" once publication recovers with batchSeq=2 + original timestamp.
        val store = MigrationArtifactStore(context)
        store.file("@pm@").writeBytes(byteArrayOf(1))
        store.file("restore_token_file").writeText("1")
        trusted.failNextBatchStarted = 2
        startWrite(batch2, "com.example.one")
        store.file("@pm@").writeBytes(byteArrayOf(1))
        store.file("restore_token_file").writeText("1")
        val transport = org.microg.gms.migration.backup.MigrationBackupTransport(context)
        try {
            val pendingSets = transport.getAvailableRestoreSets()
            assertEquals(1, pendingSets.size)
            assertEquals(1L, pendingSets[0].token)
            assertEquals("D2D:pending", pendingSets[0].device)

            val recoveredSets = transport.getAvailableRestoreSets()
            assertEquals(1, recoveredSets.size)
            assertEquals(1L, recoveredSets[0].token)
            assertEquals("D2D", recoveredSets[0].device)
        } finally {
            transport.close()
        }
        assertEquals(listOf(1L, 1L, 2L, 2L, 2L), trusted.batchSeqs)
        assertTrue("batchStarted retries must preserve original staging timestamp", trusted.batchStagedElapsed.all { it >= 0L })
        assertEquals(trusted.batchStagedElapsed[2], trusted.batchStagedElapsed[3])
        assertEquals(trusted.batchStagedElapsed[3], trusted.batchStagedElapsed[4])

        // Older companion replying Bundle(success=false) for unknown "batchStarted" settles cleanly without
        // breaking Switch's system restore or looping forever.
        trusted.success = false
        assertTrue(MigrationRestoreTransport(context).notifyBatchStarted(98L, 0, 100L))
        trusted.success = true

        // Companion throwing on unknown method never fails staging and returns false (transient).
        trusted.failure = IllegalArgumentException("unknown")
        assertFalse(MigrationRestoreTransport(context).notifyBatchStarted(99L, 0, 100L))
    }
}

internal fun installRestoreTransportOwner(context: Context, owner: String = "ai.cyclon.migration", flags: Int = ApplicationInfo.FLAG_SYSTEM) {
    val app = ApplicationInfo().apply { packageName = owner; this.flags = flags }
    shadowOf(context.packageManager).installPackage(PackageInfo().apply {
        packageName = owner; applicationInfo = app
        providers = arrayOf(ProviderInfo().apply {
            authority = "ai.cyclon.migration.restore"; packageName = owner; name = "$owner.RestoreTransportProvider"; applicationInfo = app
        })
    })
}

internal class RestoreTransportProvider : ContentProvider() {
    var leaseId: String? = "first"
    var success = true
    var nullResponse = false
    var failure: RuntimeException? = null
    var failNextBatchStarted = 0
    val calls = mutableListOf<Pair<String, String?>>()
    val batchSeqs = mutableListOf<Long>()
    val batchStagedElapsed = mutableListOf<Long>()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        calls += method to extras?.getString("lease_id")
        when (method) {
            "scan_volume" -> assertEquals("external_primary", arg)
            "batchStarted" -> {
                val seq = extras?.getLong("batch_seq", -1L) ?: -1L
                assertEquals(arg?.toLongOrNull() ?: -1L, seq)
                batchSeqs += seq
                batchStagedElapsed += extras?.getLong("batch_staged_elapsed", -1L) ?: -1L
                if (failNextBatchStarted > 0) {
                    failNextBatchStarted--
                    return null
                }
            }
            else -> assertNull(arg)
        }
        failure?.let { throw it }
        return if (nullResponse) null else Bundle().apply {
            putBoolean("success", success)
            if (method == "acquire") putString("lease_id", leaseId)
        }
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
