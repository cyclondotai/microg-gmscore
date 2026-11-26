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

/**
 * A Firebase Cloud Messaging registration that names the app instance by its Firebase installation (FID) instead of
 * the legacy Instance ID. Sent by the Firebase Messaging library to Cloud Messaging service 457 when the app opts in
 * with the {@code firebase_messaging_installation_id_enabled} manifest flag and Play services is 26.12 or newer.
 */
@Hide
@SafeParcelable.Class
public class RegisterRequest extends AbstractSafeParcelable {
    /** The sender ID (Firebase project number). */
    @Field(1)
    public final String senderId;
    /** The app's Firebase (GMP) app ID, such as {@code 1:123:android:abc}. */
    @Field(2)
    public final String gmpAppId;
    /** The app's Firebase API key. */
    @Field(3)
    public final String apiKey;
    /** The Firebase installation ID (FID). */
    @Field(4)
    public final String installationId;
    /** The Firebase installation auth token for {@link #installationId}. */
    @Field(5)
    public final String installationAuthToken;
    /** The calling app's version code. */
    @Field(6)
    public final int appVersionCode;
    /** The Firebase Messaging library version, such as {@code fcm-25.1.2_1p}. */
    @Field(7)
    public final String sdkVersion;

    @Constructor
    public RegisterRequest(@Param(1) String senderId, @Param(2) String gmpAppId, @Param(3) String apiKey, @Param(4) String installationId,
                           @Param(5) String installationAuthToken, @Param(6) int appVersionCode, @Param(7) String sdkVersion) {
        this.senderId = senderId;
        this.gmpAppId = gmpAppId;
        this.apiKey = apiKey;
        this.installationId = installationId;
        this.installationAuthToken = installationAuthToken;
        this.appVersionCode = appVersionCode;
        this.sdkVersion = sdkVersion;
    }

    @NonNull
    @Override
    public String toString() {
        // Keys and tokens stay out of logs.
        return "RegisterRequest{senderId=" + senderId + ", gmpAppId=" + gmpAppId + ", appVersionCode=" + appVersionCode
                + ", sdkVersion=" + sdkVersion + "}";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        CREATOR.writeToParcel(this, dest, flags);
    }

    public static final SafeParcelableCreatorAndWriter<RegisterRequest> CREATOR = findCreator(RegisterRequest.class);
}
