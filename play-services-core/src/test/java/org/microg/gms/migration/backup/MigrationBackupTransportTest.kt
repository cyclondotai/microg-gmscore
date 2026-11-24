/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.migration.backup

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercises framework API shapes and stream counts, not BackupManager/app acceptance.
 * Robolectric's static dup(FileDescriptor) copies into a temporary file; exact descriptor
 * byte delivery is asserted on Android by MigrationRestoreProbe instead. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MigrationBackupTransportTest {
    @Test fun iosTelephonySetUsesFullRestoreAndPreservesItsArchiveForRetry() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.noBackupFilesDir, "migration-staging").apply { mkdirs() }
        File(folder, "restore_token_file").writeText("1")
        File(folder, "@pm@").writeBytes(byteArrayOf(1, 2, 3))
        val archive = ByteArray(70_001) { (it % 251).toByte() }
        File(folder, "com.android.providers.telephony").writeBytes(archive)
        val pm = PackageInfo().apply { packageName = "@pm@" }
        val telephony = PackageInfo().apply {
            packageName = "com.android.providers.telephony"
            applicationInfo = ApplicationInfo().apply {
                backupAgentName = "com.android.providers.telephony.TelephonyBackupAgent"
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_FULL_BACKUP_ONLY
            }
        }
        MigrationBackupTransport(context).use { transport ->
            assertEquals(1L, transport.availableRestoreSets.single().token)
            assertEquals(-1000, transport.startRestore(2L, arrayOf(telephony, pm)))
            assertEquals(0, transport.startRestore(1L, arrayOf(telephony, pm)))
            assertEquals("@pm@", transport.nextRestorePackage().packageName)
            val output = File(context.cacheDir, "ios-restore-output")
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                assertEquals(0, transport.getRestoreData(it))
                val next = transport.nextRestorePackage()
                assertEquals(telephony.packageName, next.packageName)
                assertEquals(2, next.dataType)
                var total = 0
                while (true) {
                    val count = transport.getNextFullRestoreDataChunk(it)
                    if (count == -1) break
                    assertTrue(count > 0); total += count
                }
                assertEquals(archive.size, total)
            }
            transport.finishRestore()
            assertEquals(0, transport.startRestore(1L, arrayOf(telephony, pm)))
            assertEquals("@pm@", transport.nextRestorePackage().packageName)
            transport.finishRestore()
        }
        assertArrayEquals(archive, File(folder, telephony.packageName).readBytes())
    }

    @Test fun localTransportApiShapesAndStreamCountsRejectBackupWrites() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.noBackupFilesDir, "migration-staging").apply { mkdirs() }
        val metadata = byteArrayOf(0, 9, 1, -1)
        val data = ByteArray(70001) { (it % 251).toByte() }
        File(folder, "restore_token_file").writeText("42")
        File(folder, "@pm@").writeBytes(metadata)
        File(folder, "com.example.synthetic").writeBytes(data)
        val pm = PackageInfo().apply { packageName = "@pm@" }
        val app = PackageInfo().apply {
            packageName = "com.example.synthetic"
            applicationInfo = ApplicationInfo().apply { backupAgentName = null }
        }
        MigrationBackupTransport(context).use { transport ->
            assertEquals(2, transport.transportFlags)
            assertEquals(42L, transport.availableRestoreSets.single().token)
            assertEquals(-1000, transport.initializeDevice())
            assertEquals(0, transport.startRestore(42, arrayOf(pm, app)))
            assertEquals("@pm@", transport.nextRestorePackage().packageName)
            val kvOutput = File(context.cacheDir, "kv-output")
            ParcelFileDescriptor.open(kvOutput, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                assertEquals(0, transport.getRestoreData(it))
                assertEquals(-1000, transport.performBackup(app, it, 0))
            }
            val next = transport.nextRestorePackage()
            assertEquals(app.packageName, next.packageName); assertEquals(2, next.dataType)
            val fullOutput = File(context.cacheDir, "full-output")
            ParcelFileDescriptor.open(fullOutput, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use {
                var total = 0
                while (true) {
                    val count = transport.getNextFullRestoreDataChunk(it)
                    if (count == -1) break
                    assertTrue(count > 0); total += count
                }
                assertEquals(data.size, total)
            }
            transport.finishRestore()
        }
        assertArrayEquals(data, File(folder, app.packageName).readBytes())
    }

    @Test fun singlePackageThirdPartyRestoreWorksAfterInitialTelephonySystemRestore() {
        val context = RuntimeEnvironment.getApplication()
        val folder = File(context.noBackupFilesDir, "migration-staging").apply { mkdirs() }
        val telephonyArchive = ByteArray(16_384) { (it % 199).toByte() }
        val fullAppArchive = ByteArray(40_005) { ((it * 7) % 251).toByte() }
        val kvAppArchive = byteArrayOf(10, 20, 30, 40, 50)
        File(folder, "restore_token_file").writeText("1")
        File(folder, "@pm@").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(folder, "com.android.providers.telephony").writeBytes(telephonyArchive)
        File(folder, "com.example.fullapp").writeBytes(fullAppArchive)
        File(folder, "com.example.kvapp").writeBytes(kvAppArchive)

        val pm = PackageInfo().apply { packageName = "@pm@" }
        val telephony = PackageInfo().apply {
            packageName = "com.android.providers.telephony"
            applicationInfo = ApplicationInfo().apply {
                backupAgentName = "com.android.providers.telephony.TelephonyBackupAgent"
                flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_FULL_BACKUP_ONLY
            }
        }
        val fullApp = PackageInfo().apply {
            packageName = "com.example.fullapp"
            applicationInfo = ApplicationInfo().apply { backupAgentName = null }
        }
        val kvApp = PackageInfo().apply {
            packageName = "com.example.kvapp"
            applicationInfo = ApplicationInfo().apply {
                backupAgentName = "com.example.kvapp.KvAgent"
                flags = 0
            }
        }

        // Phase 1: Initial SystemRestoreService restores telephony before third-party apps are installed.
        MigrationBackupTransport(context).use { transport ->
            assertEquals(0, transport.startRestore(1L, arrayOf(pm, telephony)))
            assertEquals("@pm@", transport.nextRestorePackage().packageName)
            val out1 = File(context.cacheDir, "sys-restore-out")
            ParcelFileDescriptor.open(out1, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                assertEquals(0, transport.getRestoreData(fd))
                assertEquals(telephony.packageName, transport.nextRestorePackage().packageName)
                var total = 0
                while (true) {
                    val count = transport.getNextFullRestoreDataChunk(fd)
                    if (count == -1) break
                    total += count
                }
                assertEquals(telephonyArchive.size, total)
            }
            assertEquals("no more packages after telephony", "NO_MORE_PACKAGES", transport.nextRestorePackage().packageName)
            transport.finishRestore()
        }

        // Phase 2: Later, a fresh transport instance restores each newly installed third-party app one at a time.
        MigrationBackupTransport(context).use { transport ->
            assertEquals(1L, transport.availableRestoreSets.single().token)
            assertEquals(0, transport.startRestore(1L, arrayOf(fullApp)))
            val desc = transport.nextRestorePackage()
            assertEquals(fullApp.packageName, desc.packageName)
            assertEquals(2, desc.dataType) // TYPE_FULL_STREAM
            val out2 = File(context.cacheDir, "full-single-out")
            ParcelFileDescriptor.open(out2, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                var total = 0
                while (true) {
                    val count = transport.getNextFullRestoreDataChunk(fd)
                    if (count == -1) break
                    total += count
                }
                assertEquals(fullAppArchive.size, total)
            }
            assertEquals("NO_MORE_PACKAGES", transport.nextRestorePackage().packageName)
            transport.finishRestore()

            // Key-value third-party app restored in a subsequent single-package session.
            assertEquals(0, transport.startRestore(1L, arrayOf(kvApp)))
            val kvDesc = transport.nextRestorePackage()
            assertEquals(kvApp.packageName, kvDesc.packageName)
            assertEquals(1, kvDesc.dataType) // TYPE_KEY_VALUE
            val out3 = File(context.cacheDir, "kv-single-out")
            ParcelFileDescriptor.open(out3, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                assertEquals(0, transport.getRestoreData(fd))
            }
            assertEquals("NO_MORE_PACKAGES", transport.nextRestorePackage().packageName)
            transport.finishRestore()
        }

        // Staged archives remain intact across all single-package restores.
        assertArrayEquals(fullAppArchive, File(folder, fullApp.packageName).readBytes())
        assertArrayEquals(kvAppArchive, File(folder, kvApp.packageName).readBytes())
    }
}
