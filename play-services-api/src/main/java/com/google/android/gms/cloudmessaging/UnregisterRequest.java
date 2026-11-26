/*
 * SPDX-FileCopyrightText: 2026 Cyclon
 * SPDX-License-Identifier: Apache-2.0
 */
package com.google.android.gms.cloudmessaging;

import android.os.Parcel;

import androidx.annotation.NonNull;

import com.google.android.gms.common.internal.safeparcel.AbstractSafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelable;
import com.google.android.gms.common.internal.safeparcel.SafeParcelableCreatorAndWriter;

import org.microg.gms.common.Hide;

/** Deletes a registration made with {@link RegisterRequest}. */
@Hide
@SafeParcelable.Class
public class UnregisterRequest extends AbstractSafeParcelable {
    /** The Firebase project the registration belongs to. */
    @Field(1)
    public final String projectId;
    /** The app's Firebase API key. */
    @Field(2)
    public final String apiKey;
    /** The Firebase installation ID (FID) that was registered. */
    @Field(3)
    public final String installationId;
    /** The Firebase installation auth token for {@link #installationId}. */
    @Field(4)
    public final String installationAuthToken;

    @Constructor
    public UnregisterRequest(@Param(1) String projectId, @Param(2) String apiKey, @Param(3) String installationId, @Param(4) String installationAuthToken) {
        this.projectId = projectId;
        this.apiKey = apiKey;
        this.installationId = installationId;
        this.installationAuthToken = installationAuthToken;
    }

    @NonNull
    @Override
    public String toString() {
        return "UnregisterRequest{projectId=" + projectId + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<UnregisterRequest> CREATOR = findCreator(UnregisterRequest.class);
}
