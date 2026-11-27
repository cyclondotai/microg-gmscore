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

/**
 * Core-only Binder bridge exposing unsupported GMS service/feature observations recorded by
 * [UnsupportedApiRecorder] (CYC-3474).
 *
 * Caller authentication matches [WebLoginProvider] and [org.microg.gms.passwords.PasswordAgentFillProvider]:
 * exact system `ai.cyclon.core` UID with no shared UID.
 *
 * `call(method, arg, extras)`:
 * - `recent`: `arg` is a package name; optional `window_ms` (default 60_000) and `limit` (default 16).
 *   Returns `entries` (JSON array string) of recent unsupported requests by that package without clearing
 *   the periodic drain queue.
 * - `drain`: `arg` must be null; optional `limit` (default 64). Returns `entries` (JSON array string) of
 *   newly observed unsupported requests across all apps and marks them drained.
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
            val entries = when (method) {
                METHOD_RECENT -> {
                    val windowMs = extras?.getLong(KEY_WINDOW_MS, DEFAULT_RECENT_WINDOW_MS) ?: DEFAULT_RECENT_WINDOW_MS
                    val limit = extras?.getInt(KEY_LIMIT, UnsupportedApiStore.DEFAULT_RECENT_LIMIT)
                        ?: UnsupportedApiStore.DEFAULT_RECENT_LIMIT
                    UnsupportedApiRecorder.recentFor(app, arg, windowMs, limit)
                }
                METHOD_DRAIN -> {
                    if (arg != null) throw SecurityException("Unexpected arg")
                    val limit = extras?.getInt(KEY_LIMIT, UnsupportedApiStore.DEFAULT_DRAIN_LIMIT)
                        ?: UnsupportedApiStore.DEFAULT_DRAIN_LIMIT
                    UnsupportedApiRecorder.drainDirty(app, limit)
                }
                else -> throw SecurityException("Unsupported method")
            }
            val array = JSONArray()
            for (entry in entries) array.put(entry.toJson())
            Bundle().apply {
                putInt(KEY_VERSION, VERSION)
                putString(KEY_ENTRIES, array.toString())
            }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
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
        const val METHOD_DRAIN = "drain"
        const val KEY_VERSION = "version"
        const val KEY_ENTRIES = "entries"
        const val KEY_WINDOW_MS = "window_ms"
        const val KEY_LIMIT = "limit"
        const val VERSION = 1
        const val DEFAULT_RECENT_WINDOW_MS = 60_000L
        private const val CORE = "ai.cyclon.core"
    }
}
