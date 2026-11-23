/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.cyclon

import android.content.Context
import java.security.MessageDigest

/** Private receipt, scoped to the native account and exact check-in ID. No auth tokens. */
internal class EnrollmentSession(context: Context, account: String, id: Long) {
    private val appContext = context.applicationContext ?: context
    private val prefs = open(context)
    private val key = keyFor(account, id)
    val accepted get() = prefs.getString(key, null) == "accepted"
    val attempted get() = prefs.getString(key, null) in setOf("attempted", "accepted")
    // Synchronous persistence precedes the one external write; unknown outcomes never replay.
    fun admit(): Boolean = !attempted && prefs.edit().putString(key, "attempted").commit()
    fun accept(): Boolean {
        val stored = prefs.edit().putString(key, "accepted").commit()
        if (stored) FactoryBaseline.sealIfNeeded(appContext)
        return stored
    }

    companion object {
        /** Registration writes in the :ui process; seal and restore run in the main one and must see each other's commit. */
        @Suppress("DEPRECATION")
        private fun open(context: Context) =
            context.getSharedPreferences("cyclon_google_enrollment", Context.MODE_MULTI_PROCESS)

        fun keyFor(account: String, id: Long): String =
            MessageDigest.getInstance("SHA-256").digest("$account\n$id".toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Puts back a sealed receipt after a factory reset. Unlike [accept], it does not seal again. */
        fun restoreAccepted(context: Context, key: String): Boolean =
            open(context).edit().putString(key, "accepted").commit()
    }
}
