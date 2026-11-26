/* SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0 */
package com.google.android.gms.semanticlocationhistory.db

import android.accounts.Account
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import com.google.android.gms.common.api.ApiMetadata
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.api.internal.IStatusCallback
import com.google.android.gms.common.data.DataHolder
import com.google.android.gms.semanticlocation.SemanticLocationState
import com.google.android.gms.semanticlocationhistory.ExperimentVisitsResponse
import com.google.android.gms.semanticlocationhistory.InferredPlace
import com.google.android.gms.semanticlocationhistory.LocationHistorySegment
import com.google.android.gms.semanticlocationhistory.LocationHistorySegmentRequest
import com.google.android.gms.semanticlocationhistory.LocationHistorySettings
import com.google.android.gms.semanticlocationhistory.OdlhBackupSummary
import com.google.android.gms.semanticlocationhistory.RequestCredentials
import com.google.android.gms.semanticlocationhistory.SemanticLocationEditInputs
import com.google.android.gms.semanticlocationhistory.UserLocationProfile
import com.google.android.gms.semanticlocationhistory.backupFileRefusal
import com.google.android.gms.semanticlocationhistory.internal.ISemanticLocationHistoryCallbacks
import com.google.android.gms.semanticlocationhistory.internal.ISemanticLocationHistoryService
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

    @Test
    fun backupFileTransactionsAndCallbackMatchPlayServicesWireLayout() {
        var exportedFor: String? = null
        var restoredFor: Pair<String?, Boolean>? = null
        val service = object : ISemanticLocationHistoryService.Stub() {
            override fun getSegments(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, request: LocationHistorySegmentRequest?, apiMetadata: ApiMetadata?) = Unit
            override fun getInferredHome(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun getInferredWork(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun editSegments(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, list: MutableList<LocationHistorySegment>?, apiMetadata: ApiMetadata?) = Unit
            override fun deleteHistory(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, startTime: Long, endTime: Long, apiMetadata: ApiMetadata?) = Unit
            override fun onDemandBackup(callback: IStatusCallback?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun onDemandRestore(callback: IStatusCallback?, requestCredentials: RequestCredentials?, list: MutableList<*>?, apiMetadata: ApiMetadata?) = Unit
            override fun getBackupSummary(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun deleteBackups(callback: IStatusCallback?, requestCredentials: RequestCredentials?, list: MutableList<*>?, apiMetadata: ApiMetadata?) = Unit
            override fun getUserLocationProfile(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun getLocationHistorySettings(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun getExperimentVisits(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) = Unit
            override fun editCsl(callback: IStatusCallback?, requestCredentials: RequestCredentials?, editInputs: SemanticLocationEditInputs?, state: SemanticLocationState?, apiMetadata: ApiMetadata?) = Unit
            override fun getBackupFile(callback: ISemanticLocationHistoryCallbacks?, requestCredentials: RequestCredentials?, apiMetadata: ApiMetadata?) { exportedFor = requestCredentials?.packageName }
            override fun restoreBackupFile(callback: IStatusCallback?, requestCredentials: RequestCredentials?, file: ParcelFileDescriptor?, apiMetadata: ApiMetadata?) { restoredFor = requestCredentials?.packageName to (file != null); file?.close() }
        }
        val creds = RequestCredentials(Account("a@example.com", "com.google"), "backup", "com.google.android.apps.maps")
        val serviceDescriptor = "com.google.android.gms.semanticlocationhistory.internal.ISemanticLocationHistoryService"
        fun transact(code: Int, writeExtra: Parcel.() -> Unit = {}) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(serviceDescriptor)
                data.writeStrongBinder(Binder())
                data.writeInt(1); creds.writeToParcel(data, 0)
                data.writeExtra()
                data.writeInt(1); ApiMetadata.DEFAULT.writeToParcel(data, 0)
                data.setDataPosition(0)
                service.transact(code, data, reply, 0)
            } finally { data.recycle(); reply.recycle() }
        }
        transact(14)
        assertEquals("com.google.android.apps.maps", exportedFor)
        val tmp = File.createTempFile("odlh-wire-", ".db", context.cacheDir)
        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            transact(15) { writeInt(1); pfd.writeToParcel(this, 0) }
        }
        tmp.delete()
        assertEquals("com.google.android.apps.maps" to true, restoredFor)

        var callbackStatus: Int? = null
        val callback = object : ISemanticLocationHistoryCallbacks.Stub() {
            override fun onSegmentListResponse(status: Status?, segments: MutableList<LocationHistorySegment>?, apiMetadata: ApiMetadata?) = Unit
            override fun onGetSegmentsResponse(dataHolder: DataHolder?, apiMetadata: ApiMetadata?) = Unit
            override fun onGetInferredHomeResponse(status: Status?, inferredPlace: InferredPlace?, apiMetadata: ApiMetadata?) = Unit
            override fun onGetInferredWorkResponse(status: Status?, inferredPlace: InferredPlace?, apiMetadata: ApiMetadata?) = Unit
            override fun onEditSegmentsResponse(status: Status?, apiMetadata: ApiMetadata?) = Unit
            override fun onDeleteHistoryResponse(status: Status?, apiMetadata: ApiMetadata?) = Unit
            override fun onGetUserLocationProfileResponse(status: Status?, userLocationProfile: UserLocationProfile?, apiMetadata: ApiMetadata?) = Unit
            override fun onGetBackupSummaryResponse(status: Status?, summaries: MutableList<OdlhBackupSummary>?, apiMetadata: ApiMetadata?) = Unit
            override fun onLocationHistorySettings(status: Status?, locationHistorySettings: LocationHistorySettings?, apiMetadata: ApiMetadata?) = Unit
            override fun onGetExperimentVisitsResponse(status: Status?, response: ExperimentVisitsResponse?, apiMetadata: ApiMetadata?) = Unit
            override fun onBackupFile(status: Status?, file: ParcelFileDescriptor?, apiMetadata: ApiMetadata?) { callbackStatus = status?.statusCode; file?.close() }
        }
        val cbData = Parcel.obtain(); val cbReply = Parcel.obtain()
        try {
            cbData.writeInterfaceToken("com.google.android.gms.semanticlocationhistory.internal.ISemanticLocationHistoryCallbacks")
            cbData.writeInt(1); Status.SUCCESS.writeToParcel(cbData, 0)
            cbData.writeInt(0) // null ParcelFileDescriptor
            cbData.writeInt(1); ApiMetadata.DEFAULT.writeToParcel(cbData, 0)
            cbData.setDataPosition(0)
            callback.transact(11, cbData, cbReply, Binder.FLAG_ONEWAY)
        } finally { cbData.recycle(); cbReply.recycle() }
        assertEquals(0, callbackStatus)
    }

    @Test
    fun backupFileRequiresTimelineEnabledAndMapsCaller() {
        val mapsCreds = RequestCredentials(Account("a@example.com", "com.google"), "backup", "com.google.android.apps.maps")
        val otherCreds = RequestCredentials(Account("a@example.com", "com.google"), "backup", "com.example.other")
        assertEquals(17, backupFileRefusal(timelineEnabled = false, callingPackage = "com.google.android.apps.maps", requestCredentials = mapsCreds)?.statusCode)
        assertEquals(CommonStatusCodes.DEVELOPER_ERROR, backupFileRefusal(timelineEnabled = true, callingPackage = "com.example.other", requestCredentials = mapsCreds)?.statusCode)
        assertEquals(CommonStatusCodes.DEVELOPER_ERROR, backupFileRefusal(timelineEnabled = true, callingPackage = "com.google.android.apps.maps", requestCredentials = otherCreds)?.statusCode)
        assertNull(backupFileRefusal(timelineEnabled = true, callingPackage = "com.google.android.apps.maps", requestCredentials = mapsCreds))
    }
}
