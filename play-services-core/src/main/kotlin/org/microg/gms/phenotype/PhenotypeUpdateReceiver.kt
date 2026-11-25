/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.phenotype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

private const val TAG = "PhenotypeUpdateReceiver"

/**
 * Prompts a running Gboard process to refresh its cached Phenotype configuration after a
 * zero-reboot microG update (`MY_PACKAGE_REPLACED`) or post-unlock boot (`BOOT_COMPLETED`, in
 * case direct-boot-aware Gboard initialized before microG unlocked).
 *
 * Targets Gboard's dynamically registered runtime receiver (`PhenotypeModule`), which is exported
 * without a sender permission and checks `URGENT`; Gboard's static manifest receiver requires
 * `com.google.android.gms.permission.PHENOTYPE_UPDATE_BROADCAST`, which microG intentionally does
 * not declare (a cold Gboard process fetches flags on `ON_CREATE` at startup anyway).
 *
 * On `MY_PACKAGE_REPLACED`, sends one immediate broadcast plus a single best-effort follow-up
 * after [FOLLOW_UP_DELAY_MS] via `goAsync()` to recover if Gboard's `PhenotypeModule` was already
 * running a fetch against the prior APK and coalesced the immediate trigger. On `BOOT_COMPLETED`,
 * sends only the immediate broadcast with no `goAsync()` hold so Android's ordered boot broadcast
 * is not delayed for downstream receivers.
 */
class PhenotypeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in TRIGGER_ACTIONS) return
        val appContext = context.applicationContext ?: context
        sendGboardUpdate(appContext, action)
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            scheduleFollowUp(appContext, action)
        }
    }

    private fun scheduleFollowUp(context: Context, action: String) {
        val pendingResult = goAsync()
        val posted = Handler(Looper.getMainLooper()).postDelayed({
            try {
                sendGboardUpdate(context, "$action/follow-up")
            } finally {
                pendingResult?.finish()
            }
        }, FOLLOW_UP_DELAY_MS)
        if (!posted) {
            pendingResult?.finish()
        }
    }

    private fun sendGboardUpdate(context: Context, reason: String) {
        val update = Intent(ACTION_PHENOTYPE_UPDATE).apply {
            setPackage(GBOARD_PACKAGE)
            putExtra(EXTRA_PACKAGE_NAME, GBOARD_PHENOTYPE_PACKAGE)
            putExtra(EXTRA_URGENT, true)
        }
        Log.d(TAG, "Notifying $GBOARD_PACKAGE of Phenotype update ($reason)")
        try {
            context.sendBroadcast(update)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to notify $GBOARD_PACKAGE of Phenotype update ($reason)", e)
        }
    }

    companion object {
        const val ACTION_PHENOTYPE_UPDATE = "com.google.android.gms.phenotype.UPDATE"
        const val EXTRA_PACKAGE_NAME = "com.google.android.gms.phenotype.PACKAGE_NAME"
        const val EXTRA_URGENT = "com.google.android.gms.phenotype.URGENT"
        internal const val FOLLOW_UP_DELAY_MS = 1_500L
        private val TRIGGER_ACTIONS = setOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
        )
    }
}
