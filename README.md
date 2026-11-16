# ABark (`bark-android`)

An Android client for [Bark](https://github.com/Finb/bark) servers that receives notifications **directly from
Apple's APNs courier** — no FCM, no Google services, works with a stock `bark-server`.

Status: **end-to-end MVP implemented, awaiting the on-device phase-0 gate.** The engine (activation, courier transport,
Bark payloads, notification mapping), the CLI harness and the Android app all build; `./gradlew test` is green.

Application ID: `io.github.lichon.abark` · package root: `io.github.lichon.abark.*` · artifact: `abark-<version>.apk`.

## What exists

| Module | Platform | Contents |
| --- | --- | --- |
| `:core:apns` | JVM | Courier wire protocol (`Packet`, `Commands`), Alberts activation (plist, CSR, FairPlay signing, certificate intake), TLS transport + session state machine + reconnect loop, identity store |
| `:core:bark` | JVM | Bark payload parser → `NotificationSpec`, `bark-server` API client (`/register`, `/push`), `bark://` link parsing |
| `:core:net` | JVM | `HttpTransport` abstraction + `HttpURLConnection` implementation |
| `:core:update` | JVM | SemVer, release selection, GitHub Releases source |
| `:core:update-android` | Android | `PackageInstaller` install flow, APK signature verification, DataStore cache |
| `:app` | Android | Foreground-service courier, notification renderer, inbox, Compose UI, boot/watchdog receivers |
| `:tools:cli` | JVM | `activate` / `run` / `push` / `link` / `show` — the phase-0 harness |

## Quick start (desktop CLI)

```bash
# 1. Activate. Signs with the FairPlay pair embedded in :core:apns (see docs/DEVELOPING.md);
#    --fairplay-key/--fairplay-chain override it.
./gradlew :tools:cli:run --args="activate --store identity.json"

# 2. Connect and link to your bark server (registers the scoped APNs token via /register).
./gradlew :tools:cli:run --args="run --store identity.json \
    --server https://api.day.app --key YOUR_DEVICE_KEY"

# 3. Push to yourself from anywhere.
./gradlew :tools:cli:run --args="push --server https://api.day.app --key YOUR_DEVICE_KEY \
    --title hello --body world"
```

## Quick start (Android app)

```bash
./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

In the app: **Servers** tab → **Activate** (the FairPlay key is bundled in this build, so the phone talks to Apple's
Alberts itself and then connects), then press **Register** (the server defaults to `https://api.day.app`; override it for
a self-hosted `bark-server`). **Register** registers the scoped APNs token and keeps the device key the server answers
with — the key is read-only in the UI and the share link is built from it. **Settings** still offers **Activate on this
device** and **Import identity** (paste the JSON from `tools/cli activate`). Allow notifications and battery-optimisation
exemption — both are required for reliable delivery on stock and OEM ROMs. The **Messages** tab is the local inbox, and
the badge on the tab counts unread pushes.

The bundled FairPlay key is Apple-proprietary material (PLAN.md decision 2): fine for a personal build, **do not
distribute** the APK. Activation certifies a fresh device key each time, so a re-activation replaces the stored identity.

## Documentation

* [`PLAN.md`](PLAN.md) — feasibility, protocol notes, architecture, milestones, risks, distribution rationale.
* [`docs/PROTOCOL.md`](docs/PROTOCOL.md) — courier wire format, session sequence, activation, open questions.
* [`docs/DEVELOPING.md`](docs/DEVELOPING.md) — toolchain, build/SDK setup, what is tested and how.
* [`docs/DISTRIBUTION.md`](docs/DISTRIBUTION.md) — release pipeline, in-app update checker, signing/verification.
* [`.github/workflows/release.yml`](.github/workflows/release.yml) — tag-driven signed release with checksums.

## Known gaps

* The live gate (a real push arriving over 5223 on a phone) needs FairPlay material and a physical device; everything
  up to it is tested offline against scripted courier sessions and fake HTTP transports.
* End-to-end encrypted pushes (`ciphertext`) are displayed as "encrypted", not decrypted.
* The WebSocket/relay fallback transport and the optional `bark-server` patch (PLAN.md §6) are not implemented.
* `docs/PROTOCOL.md` still lists **verify** items that only a real courier session can settle (keepalive tolerance,
  `SetState`, `delete` push shape).

Unofficial project. Not affiliated with Apple, Finb/Bark, or Beeper.
