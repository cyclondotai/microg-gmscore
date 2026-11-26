# Agent sign-in policy (CYC-779, CYC-3345)

Since CYC-3345 (Thomas, 2026-11-26), per-login Ask / Never access and **Require device
authentication** are removed from the agent sign-in path and the Passwords app. Any saved
login matching the authenticated app (package, UID and single signing certificate,
including the app's own embedded web page) or browser-verified HTTPS origin (Jelly /
Google-signed Chrome) is treated as Always, including entries persisted with Ask, Never or
`requireDeviceAuthentication` by earlier builds.

Core receives only account metadata (`ref`, `username`, `account`, `access="always"`,
`require_authentication=false`) and redeems over the Core-UID-only
`PasswordAgentFillProvider`. `AgentSignInAccess` keeps per-login incarnations and
`lastUsed` timestamps so deleting or replacing a login or vault invalidates stale opaque
references.

The password crosses only the private `ResultReceiver` Binder to Core native code and
never reaches the agent, model, traces or logs. This does not change owner-initiated
Android Autofill, which keeps its own origin checks. Matching Core/controller builds
retain the native privacy fence and realm binding so a login is filled only into the app
or site it was saved for.
