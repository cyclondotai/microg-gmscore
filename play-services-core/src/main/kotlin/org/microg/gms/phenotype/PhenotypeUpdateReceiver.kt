/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.gms.phenotype

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private const val TAG = "PhenotypeUpdateReceiver"

/**
 * Prompts Gboard to refresh its cached Phenotype configuration after a zero-reboot microG update.
 */
class PhenotypeUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val update = Intent(ACTION_PHENOTYPE_UPDATE).apply {
            setPackage(GBOARD_PACKAGE)
            putExtra(EXTRA_PACKAGE_NAME, GBOARD_PHENOTYPE_PACKAGE)
            putExtra(EXTRA_URGENT, true)
        }
        Log.d(TAG, "Notifying $GBOARD_PACKAGE of Phenotype configuration update")
        context.sendBroadcast(update)
    }

    companion object {
        const val ACTION_PHENOTYPE_UPDATE = "com.google.android.gms.phenotype.UPDATE"
        const val EXTRA_PACKAGE_NAME = "com.google.android.gms.phenotype.PACKAGE_NAME"
        const val EXTRA_URGENT = "com.google.android.gms.phenotype.URGENT"
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
        const val GBOARD_PHENOTYPE_PACKAGE = "$GBOARD_PACKAGE#$GBOARD_PACKAGE"
    }
}
