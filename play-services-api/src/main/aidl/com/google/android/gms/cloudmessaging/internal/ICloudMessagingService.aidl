/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.gms.cloudmessaging.internal;

import com.google.android.gms.cloudmessaging.RegisterRequest;
import com.google.android.gms.cloudmessaging.UnregisterRequest;
import com.google.android.gms.cloudmessaging.internal.IRegisterCallback;
import com.google.android.gms.common.api.ApiMetadata;
import com.google.android.gms.common.api.internal.IStatusCallback;

// Cloud Messaging service 457 (com.google.android.gms.cloudmessaging.service.START), Play services 26.12+.
interface ICloudMessagingService {
    void register(IRegisterCallback callback, in RegisterRequest request, in ApiMetadata apiMetadata) = 0;
    void unregister(IStatusCallback callback, in UnregisterRequest request, in ApiMetadata apiMetadata) = 1;
}
