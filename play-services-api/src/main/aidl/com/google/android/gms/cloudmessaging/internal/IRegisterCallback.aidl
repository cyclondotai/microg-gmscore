/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.gms.cloudmessaging.internal;

import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.common.api.Status;

interface IRegisterCallback {
    oneway void onRegister(in Status status, String token, in ApiMetadata apiMetadata) = 0;
}
