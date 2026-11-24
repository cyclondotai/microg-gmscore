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
                assertTrue(helper.transact(3, req, reply, 0))
                reply.readException()
            } finally {
                req.recycle()
                reply.recycle()
            }
            assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, result)
        }

        val batch1 = EmptyMigrateHelper(android.os.Binder.getCallingUid(), context)
        batch1.endStaging() // no-op before staging starts
        startWrite(batch1, "com.example.one")
        startWrite(batch1, "com.example.two")
        batch1.endStaging()
        batch1.endStaging() // idempotent once ended
        val batch2 = EmptyMigrateHelper(android.os.Binder.getCallingUid(), context)
        startWrite(batch2, "com.example.one")
        batch2.endStaging()
        assertEquals(
            listOf("batchStarted" to null, "batchEnded" to null, "batchStarted" to null, "batchEnded" to null),
            trusted.calls,
        )

        // Older companion throwing on unknown method never fails staging.
        trusted.failure = IllegalArgumentException("unknown")
        MigrationRestoreTransport(context).notifyBatchStarted()
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
    val calls = mutableListOf<Pair<String, String?>>()
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        calls += method to extras?.getString("lease_id")
        if (method == "scan_volume") assertEquals("external_primary", arg) else assertNull(arg)
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
