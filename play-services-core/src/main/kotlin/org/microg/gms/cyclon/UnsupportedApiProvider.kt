/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.cyclon

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ApplicationInfo
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Core-only Binder bridge exposing unsupported GMS service/feature observations recorded by
 * [UnsupportedApiRecorder] (CYC-3474).
 *
 * Caller authentication matches [WebLoginProvider] and [org.microg.gms.passwords.PasswordAgentFillProvider]:
 * exact system `ai.cyclon.core` UID with no shared UID.
 *
 * `call(method, arg, extras)`:
 * - `recent`: `arg` is a package name; optional `window_ms` (default 60_000) and `limit` (default 16).
 *   Returns `entries` (JSON array string, bounded <= 128 KiB) without clearing pending drain state.
 * - `peek`: `arg` must be null; optional `limit` (default 64). Returns `entries` (bounded <= 128 KiB) and
 *   `has_more` without marking them drained; Core follows with `ack` after durable persistence.
 * - `ack`: `arg` must be null; `acks` JSON array of `{"key": "...", "lastSeenAt": <ms>}`. Marks matching
 *   entries drained only if no newer observation arrived since `lastSeenAt`.
 * - `drain`: legacy one-step drain (`arg` must be null, bounded <= 128 KiB; marks only returned entries clean).
 */
class UnsupportedApiProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Build.VERSION.SDK_INT < 28) throw SecurityException("Unsupported SDK")
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        val core = try {
            app.packageManager.getApplicationInfo(CORE, 0)
        } catch (_: Exception) {
            throw SecurityException("Core absent")
        }
        val isSystem = core.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (uid != core.uid || !isSystem || app.packageManager.getPackagesForUid(uid)?.toSet() != setOf(CORE)) {
            throw SecurityException("Core only")
        }

        val identity = Binder.clearCallingIdentity()
        return try {
            when (method) {
                METHOD_RECENT -> {
                    val windowMs = extras?.getLong(KEY_WINDOW_MS, DEFAULT_RECENT_WINDOW_MS) ?: DEFAULT_RECENT_WINDOW_MS
                    val limit = extras?.getInt(KEY_LIMIT, UnsupportedApiStore.DEFAULT_RECENT_LIMIT)
                        ?: UnsupportedApiStore.DEFAULT_RECENT_LIMIT
                    val entries = UnsupportedApiRecorder.recentFor(app, arg, windowMs, limit)
                    entriesBundle(entries, hasMore = false)
                }
                METHOD_PEEK -> {
                    if (arg != null) throw SecurityException("Unexpected arg")
                    val limit = extras?.getInt(KEY_LIMIT, UnsupportedApiStore.DEFAULT_DRAIN_LIMIT)
                        ?: UnsupportedApiStore.DEFAULT_DRAIN_LIMIT
                    val (entries, hasMore) = UnsupportedApiRecorder.peekDirty(app, limit)
                    entriesBundle(entries, hasMore = hasMore)
                }
                METHOD_ACK -> {
                    if (arg != null) throw SecurityException("Unexpected arg")
                    val rawAcks = extras?.getString(KEY_ACKS).orEmpty()
                    val acks = parseAcks(rawAcks)
                    val acked = UnsupportedApiRecorder.ackDrained(app, acks)
                    Bundle().apply {
                        putInt(KEY_VERSION, VERSION)
                        putInt(KEY_ACKED, acked)
                    }
                }
                METHOD_DRAIN -> {
                    if (arg != null) throw SecurityException("Unexpected arg")
                    val limit = extras?.getInt(KEY_LIMIT, UnsupportedApiStore.DEFAULT_DRAIN_LIMIT)
                        ?: UnsupportedApiStore.DEFAULT_DRAIN_LIMIT
                    val entries = UnsupportedApiRecorder.drainDirty(app, limit)
                    entriesBundle(entries, hasMore = false)
                }
                else -> throw SecurityException("Unsupported method")
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun entriesBundle(entries: List<UnsupportedApiEntry>, hasMore: Boolean): Bundle {
        val array = JSONArray()
        for (entry in entries) array.put(entry.toJson())
        return Bundle().apply {
            putInt(KEY_VERSION, VERSION)
            putString(KEY_ENTRIES, array.toString())
            putBoolean(KEY_HAS_MORE, hasMore)
        }
    }

    private fun parseAcks(raw: String): List<Pair<String, Long>> {
        if (raw.isBlank()) return emptyList()
        val arr = try {
            JSONArray(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<Pair<String, Long>>(minOf(arr.length(), UnsupportedApiStore.MAX_DRAIN_LIMIT))
        for (i in 0 until minOf(arr.length(), UnsupportedApiStore.MAX_DRAIN_LIMIT)) {
            val obj: JSONObject = arr.optJSONObject(i) ?: continue
            val key = obj.optString("key").takeIf { it.isNotEmpty() } ?: continue
            val lastSeenAt = obj.optLong("lastSeenAt", -1L)
            if (lastSeenAt >= 0L) out.add(key to lastSeenAt)
        }
        return out
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.google.android.gms.cyclon.unsupported_api"
        const val METHOD_RECENT = "recent"
        const val METHOD_PEEK = "peek"
        const val METHOD_ACK = "ack"
        const val METHOD_DRAIN = "drain"
        const val KEY_VERSION = "version"
        const val KEY_ENTRIES = "entries"
        const val KEY_HAS_MORE = "has_more"
        const val KEY_ACKS = "acks"
        const val KEY_ACKED = "acked"
        const val KEY_WINDOW_MS = "window_ms"
        const val KEY_LIMIT = "limit"
        const val VERSION = 1
        const val DEFAULT_RECENT_WINDOW_MS = 60_000L
        private const val CORE = "ai.cyclon.core"
    }
}
