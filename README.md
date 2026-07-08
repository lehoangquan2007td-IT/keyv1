# keystroker — security research project

> ⚠️ **FOR AUTHORIZED SECURITY RESEARCH / EDUCATIONAL USE ONLY.**
>
> This project captures input via Android's Accessibility Service. Installing or running
> it on a device you do not own, or on a person who has not consented, is **illegal** in
> most jurisdictions (e.g. Vietnam: Nghị định 13/2023/NĐ-CP and the Penal Code; EU GDPR;
> US Computer Fraud & Abuse Act and state wiretap statutes). Use only on your own devices
> or with explicit, written authorization.

## What this is

A research harness that demonstrates how an Android Accessibility Service can observe
text input, how a naïve keystroke logger fails under realistic conditions (autocorrect,
paste, voice input, large fields, concurrency), and how those weaknesses can be mitigated.
The companion Node.js server collects events for analysis in a lab.

This fork specifically **hardens** the original code (see *Changelog* below).

## Project layout

```
.
├── settings.gradle / build.gradle / gradle.properties   # Gradle build config
├── app/
│   ├── build.gradle                                       # module config + BuildConfig fields
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/xml/accessibility_service_config.xml
│       └── java/com/android/inject/keystroker/
│           ├── KeyLogger.java        # AccessibilityService (capture + structured diff)
│           ├── KeyLoggerDAO.java     # encrypted, thread-safe SQLite store
│           └── KeyNetwork.java       # Socket.IO client w/ auth + cert pinning
└── server/                            # sample Node.js collector
    ├── index.js
    ├── package.json
    └── README.md
```

## Build

Requirements: Android Studio (Hedgehog+) or Android SDK 34 + JDK 17 + Gradle 8.2.
Minimum supported device: **Android 6.0 (API 23)** — required by `androidx.security.crypto`
(EncryptedSharedPreferences used to store the SQLCipher passphrase).

If the Gradle wrapper jar is not present, generate it once:

```bash
gradle wrapper --gradle-version 8.2     # requires a local gradle install the first time
# or open the project in Android Studio, which will offer to generate the wrapper.
```

Then:

```bash
./gradlew assembleDebug
# apk: app/build/outputs/apk/debug/app-debug.apk
```

## Configure

Edit `app/build.gradle` `defaultConfig`:

```groovy
buildConfigField "String", "SERVER_URL", "\"https://your-host.example.com\""
buildConfigField "String", "AUTH_TOKEN", "\"your-research-token\""
buildConfigField "String", "PIN_SHA256", "\"\""
```

| Field        | Purpose                                                            |
|--------------|-------------------------------------------------------------------|
| `SERVER_URL` | Collector URL (https). Networking is skipped while it contains `your-server`. |
| `AUTH_TOKEN` | Bearer token sent in every payload; verified by the sample server.|
| `PIN_SHA256` | Base64url SHA-256 of the server's leaf certificate (optional).   |

### Cert pinning

1. Obtain the server certificate's SHA-256 fingerprint, e.g.:
   ```bash
   openssl s_client -connect your-host:443 2>/dev/null \
     | openssl x509 -pubkey -noout \
     | openssl pkey -pubin -outform der \
     | openssl dgst -sha256 -binary | base64
   ```
   → e.g. `abcdef...=` (base64url, no padding).
2. Put that value (without the `sha256/` prefix) into `PIN_SHA256`.

If `PIN_SHA256` is empty, pinning is disabled (the client still validates the normal CA
chain). For **local research** only, debug builds also trust self-signed certs
(`ALLOW_SELF_SIGNED_CERT = true`) — **never** ship a release that way.

## Enable on device

1. Install the apk on a device you own.
2. **Settings → Accessibility → System Service → Enable.**
3. Observe events on the collector (see `server/README.md`).

## Changelog (hardening of the original code)

| Area | Before | After |
|------|--------|-------|
| **Build** | No Gradle config, code at repo root | Full Gradle + standard `app/` layout |
| **Thread-safety** | Two independent DAOs/SQLite connections, writes on main thread → ANR/lock exceptions | One shared DAO, all writes on a background DB thread |
| **DB errors** | `fetchUnsynced` swallowed exceptions silently | Per-row failures logged + counted |
| **GC** | `garbageCollect` ran a blocking full `VACUUM` | Cheap `DELETE`; bounded by `prune()` TTL + cap |
| **Retention** | Unbounded growth when offline | TTL 7d (synced) + max 5000 unsynced rows |
| **Timestamp** | String with local TZ offset → ambiguous | INTEGER epoch millis |
| **Storage** | Plaintext SQLite | SQLCipher, key in EncryptedSharedPreferences (Keystore) |
| **Diff** | Naïve prefix/suffix diff → mis-classified autocorrect/voice/paste | Structured JSON diffs (`{op,pos,ins,del,len}`) + composite snapshots |
| **Large fields** | Unbounded buffer, O(n²) on paste | `MAX_FIELD_LEN=8192` cap, snapshot fallback |
| **Passwords** | Captured verbatim | Masked marker only (privacy-by-design) |
| **Network auth** | None, hardcoded placeholder URL | Bearer token + configurable URL |
| **Transport** | Plain WS/HTTPS, no pinning | Optional cert pinning via OkHttpClient |
| **Device id** | Raw `ANDROID_ID` (unstable) | SHA-256(ANDROID_ID + Build info) |
| **Android 13+** | `registerReceiver` crashed (missing flag) | `RECEIVER_NOT_EXPORTED` |
| **Manifest** | `exported="true"`, separate process | `exported="false"`, single process |

## Known limitations

- `DB_VERSION` was bumped 1 → 2; `onUpgrade` drops and recreates the table (acceptable for
  a research project; existing on-device test data is wiped on first run of the new build).
- Cert pinning requires a real fingerprint to be useful.
- SQLCipher adds native libraries and increases APK size.
- The diff algorithm is a best-effort single-region heuristic; extremely complex
  multi-region simultaneous edits still degrade to a `replace` of the differing span
  (data is preserved, just coarser-grained).
