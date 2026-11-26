/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.semanticlocationhistory.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Maps 26.19+ moves Timeline between phones in a device-to-device transfer (CYC-3296): the sending phone hands over its
 * database file, the receiving phone merges the other phone's segments into its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OdlhBackupFileTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = OdlhStorageManager(context)

    @After fun close() = manager.close()

    /** Another phone's database: the shared columns plus one this build doesn't know. */
    private fun backup(vararg rows: Triple<String, Int, Pair<Long?, Long?>>): File {
        val file = File(context.cacheDir, "backup-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE semantic_segment_table (_id INTEGER PRIMARY KEY, obfuscated_gaia_id TEXT NOT NULL, segment_id TEXT NOT NULL UNIQUE, " +
                    "segment_type INTEGER NOT NULL, semantic_segment BLOB NOT NULL, start_timestamp_seconds INTEGER, end_timestamp_seconds INTEGER, " +
                    "timestamp_millis INTEGER NOT NULL DEFAULT 0, database_id INTEGER NOT NULL, newer_column TEXT)"
            )
            rows.forEachIndexed { i, (gaiaId, type, range) ->
                db.insert("semantic_segment_table", null, ContentValues().apply {
                    put("_id", i + 1)
                    put("obfuscated_gaia_id", gaiaId)
                    put("segment_id", "$gaiaId-$type-${range.first}-${range.second}-${file.name}")
                    put("segment_type", type)
                    put("semantic_segment", byteArrayOf(1, 2, 3))
                    put("start_timestamp_seconds", range.first)
                    put("end_timestamp_seconds", range.second)
                    put("database_id", 77L)
                    put("newer_column", "x")
                })
            }
        }
        return file
    }

    @Test
    fun noDatabaseMeansNoBackupFile() {
        assertNull(manager.openDatabaseFileForBackup())
    }

    @Test
    fun mergesEachAccountsSegmentsAndDropsDeletedOrInvalidOnes() {
        val first = backup(
            Triple("gaia-1", 1, 100L to 200L), Triple("gaia-1", 2, 200L to 300L), Triple("gaia-1", -1, 0L to 10L),
            Triple("gaia-1", 1, 300L to 250L), Triple("gaia-1", 2, null to 400L), Triple("gaia-2", 1, 100L to 200L)
        )
        assertEquals(OdlhStorageManager.MergeCounts(restored = 3, skipped = 0), manager.mergeSegmentsFrom(first))
        assertEquals(2, manager.getSegmentCount("gaia-1"))
        assertEquals(1, manager.getSegmentCount("gaia-2"))
        // Restoring the same file again adds nothing: every segment overlaps itself.
        assertEquals(OdlhStorageManager.MergeCounts(restored = 0, skipped = 3), manager.mergeSegmentsFrom(first))
    }

    @Test
    fun aSegmentOverlappingOneOfTheSameTypeIsSkipped() {
        manager.mergeSegmentsFrom(backup(Triple("gaia-1", 1, 100L to 200L), Triple("gaia-1", 2, 200L to 300L)))
        val second = backup(
            Triple("gaia-1", 1, 150L to 160L), // inside the visit
            Triple("gaia-1", 1, 200L to 250L), // starts where the visit ends
            Triple("gaia-1", 2, 250L to 260L), // inside the activity
            Triple("gaia-1", 1, 120L to 120L)  // a point inside the visit
        )
        assertEquals(OdlhStorageManager.MergeCounts(restored = 1, skipped = 3), manager.mergeSegmentsFrom(second))
        assertEquals(3, manager.getSegmentCount("gaia-1"))
    }

    @Test
    fun theBackupFileIsTheWholeDatabaseReadOnly() {
        manager.mergeSegmentsFrom(backup(Triple("gaia-1", 1, 100L to 200L)))
        val descriptor = manager.openDatabaseFileForBackup()
        assertNotNull(descriptor)
        val copy = File(context.cacheDir, "exported.db")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> copy.outputStream().use { out -> input.copyTo(out) } }
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM semantic_segment_table", null).use { c -> assertTrue(c.moveToNext()); assertEquals(1, c.getInt(0)) }
        }
    }
}
