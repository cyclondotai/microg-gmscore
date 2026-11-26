# Reported Play services version

microG reports one Play services version to apps (the package's `versionCode`) and to
Google's servers (`Constants.GMS_VERSION_CODE`, sent in auth, check-in, GCM/IID,
DroidGuard, CryptAuth, passwords, Find My Device and Wallet requests). Upstream's
release pins its own version (`v0.3.17.252432` reports 25.24.32). Cyclon chooses the
reported version in `version.json` (`reportedGmsVersion`) and allocates delivery
version codes inside it.

## Why it matters

Google's client libraries compare the installed Play services version with each
API's minimum (`GmsClient.getMinApkVersion()`). If it is lower, the call fails with
`SERVICE_VERSION_UPDATE_REQUIRED` and the library posts "Update Google Play services
to use APP", a dead end on a phone whose Play services come with the OS.

A higher version is not free either. Apps branch on it: above an API's minimum they
bind that API instead of an older path. If microG does not implement the newer API,
a feature that works today stops working. So the version only rises as far as the
APIs microG serves.

## Current choice: 26.45.31 (2026-11-25, CYC-3296)

Chrome 158 asked for a Play services update. Google's own apps are built against the
current Play services client libraries, and their library constant
`GOOGLE_PLAY_SERVICES_VERSION_CODE` is the minimum for a plain
`isGooglePlayServicesAvailable(context)` check: Chrome 158's is 26.43.80 (264380000,
also its manifest `com.google.android.gms.version`). Other apps keep `12451000` as the
default minimum and use the newer constant only to check their own manifest. The
reported version is 26.45.31, the Play services release (264531035) we decompiled to
check the APIs below; `cyclon/tests` asserts the Chrome floor.

What changes for apps between 25.41.32 and 26.45.31 (Chrome, Gmail, YouTube, YouTube Music,
Meet, Maps, Photos, Drive, Calendar, Contacts, Keep, Messages, Wallet, Google app,
Gemini, Gboard and Authenticator from Google Play on 2026-11-25; `scan-gms-min-versions.py`
plus a scan for version constants):

| Gate | API | Who | microG |
|---|---|---|---|
| 26.12 | Cloud Messaging service 457 (`cloudmessaging.service.START`, `ICloudMessagingService`: register = 1, unregister = 2; features `register` 1 and `unregister` 1) | Firebase Messaging in all scanned apps except Gboard, Gemini and Authenticator | `CloudMessagingService.kt` |
| 26.19.30 | Credential manager transactions 4 and 5 (screen lock before filling passwords; features `credential_manager_first_party_get_biometric_reauth`/`_set_`) | Chrome | `CredentialManagerService.kt` |
| 26.19 | Timeline backup file: semantic location history 314 transactions 14/15, callback 11, feature `odlh_backup_file` | Maps, in device-to-device transfers | `SemanticLocationHistoryService.kt`, `OdlhStorageManager.mergeSegmentsFrom` |
| 25.45 | ServiceEntitlement 359 (TS.43 temporary token, transaction 7) | Google Messages RCS provisioning on carriers whose RCS config enables TS.43 | Not served: on such a carrier RCS setup retries (CYC-3310). Google Messages isn't on our phones |
| 26.14 | People feature `default_contacts_account_api` 3 | Contacts' default account | Not advertised; Contacts falls back to its own setting |
| 26.18 | Unified backup 229 (`backup.unified.service.START`) | Messages' debug menu | Not served; fails quietly |
| 26.41 | OS media backup 439 (`OS_MEDIA_BACKUP_SERVICE`) | Photos' backup settings | Not served; Photos uses its defaults (below 26.41 it could post the update notification) |

Drive and Calendar (26.45.30), Gemini (26.44.10), Gboard (26.42.80) and Authenticator
(26.38.80) carry newer library constants too, but use them only for the manifest check
and a few re-checks inside the library; 26.45.31 passes all of them. A missing service
fails quietly (`API_UNAVAILABLE`); a served service without a feature an app asks for
posts "Update Google Play services" after two minutes of retries, so a feature is only
advertised with its transactions.

### Cloud Messaging

Firebase Messaging registers through service 457 only when the app's manifest sets
`firebase_messaging_installation_id_enabled=true` **and** Play services is 26.12 or newer;
otherwise it keeps the `com.google.android.c2dm.intent.REGISTER` path. None of the apps
above sets the flag (2026-11-25), so their push does not change with this version.
CYC-763's 26.12 ceiling assumed they would switch.

The service takes a `RegisterRequest` (1 sender ID, 2 GMP app ID, 3 API key,
4 Firebase installation ID, 5 installation auth token, 6 app version code, 7 SDK
version) and answers `IRegisterCallback` (one-way 1: status, token, metadata).
Play services turns it into a gRPC call to `fcmregistrations.googleapis.com`
(`google.firebase.fcm.registration.v1.android.AndroidRegistration/RegisterAppInstance`,
`play-services-core-proto/.../fcm_android_registration.proto`) with `X-Goog-Api-Key`,
`X-Goog-Firebase-Installations-Auth` and `X-Aid-Login: androidId:securityToken`, and
deletes with `RegistrationApi/DeleteRegistration`
(`projects/{project}/registrations/{installation ID}`). The returned token ends with
the installation ID, which the client library checks. Messages for it arrive on the
same MCS connection, so the app is recorded in `GcmDatabase` like a legacy registration.
Checked live on 2026-11-25 with Cyclon's push project: register and delete answered
`grpc-status 0` (no `X-Android-Package` needed although that key is Android-restricted).

## Raising it again

1. Scan current versions of the Google apps customers use (all split APKs) and note
   every API above the version you want. `scan-gms-min-versions.py` finds client
   minimums; also look for version constants compared with the installed Play services
   version (`>= 2NNNN0000`) and for each app's `GOOGLE_PLAY_SERVICES_VERSION_CODE`. An app
   whose constant is its default minimum (Chrome) asks for an update as soon as it is
   built against a newer client library, so expect to raise the version again every few
   months.
2. Make sure microG serves each of those APIs, or that apps fall back cleanly.
3. Set `reportedGmsVersion` and a fresh `gmsVersionCode`/`vendingVersionCode`
   allocation; it must stay above every shipped APK.
4. Device acceptance: Google sign-in, push (FCM), Google prompt, passwords, Workspace
   mail, Play Integrity, and no "Update Google Play services" notification from the
   scanned apps.
