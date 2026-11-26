/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.gms.credential.manager.common;

import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.common.api.Status;

interface IBooleanCallback {
    oneway void onBoolean(in Status status, boolean value, in ApiMetadata apiMetadata) = 0;
}
