# Developing

## Current state

Six modules build; five of them are plain **Kotlin/JVM** and need no Android SDK, which is where all the logic lives.

| Module | Type | Contents | Tests |
| --- | --- | --- | --- |
| `:core:net` | JVM | `HttpTransport` + `HttpURLConnection` implementation | – (thin wrapper) |
| `:core:apns` | JVM | framing, commands, activation (plist/CSR/FairPlay/Alberts), TLS courier channel, session state machine, reconnect loop, identity store | 101 |
| `:core:bark` | JVM | payload parser → `NotificationSpec`, bark-server API client, `bark://` links | 28 |
| `:core:update` | JVM | semver, release selection, GitHub Releases source | 19 |
| `:core:update-android` | Android | `PackageInstaller` flow, APK signature verification, DataStore cache | – (needs a device) |
| `:app` | Android | foreground service, notifications, inbox, Compose UI, boot/watchdog receivers | – |
| `:tools:cli` | JVM | phase-0 harness: `activate`, `run`, `push`, `link`, `show` | – |

`./gradlew test` runs 148 tests in ~5 s.

## Commands

```bash
./gradlew test                     # every JVM test (no Android SDK required)
./gradlew :core:apns:test          # protocol + activation + transport + identity
./gradlew :core:bark:test          # payload/spec/API/link
./gradlew :app:assembleDebug       # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug           # lint (only warnings today)
./gradlew :tools:cli:run --args="link https://api.day.app/DEVKEY"
```

Wrapper: Gradle 8.7, downloaded through the Huawei Cloud mirror (`gradle-wrapper.properties`) because
`services.gradle.org` is unreliable here. Kotlin 2.0.21, JVM target 11. Dependency versions live in
`gradle/libs.versions.toml` — add new ones there, not inline in a module script.

## Toolchain on this machine

| Piece | Value | Notes |
| --- | --- | --- |
| Gradle | 8.7 (wrapper) | requires JDK 17+ |
| JDK (default) | `C:/Users/lc/scoop/apps/openjdk17/current` | already on `PATH` / `JAVA_HOME`; nothing to set |
| JDK 8 | `C:/Users/lc/scoop/apps/openjdk8-redhat/current` | only needed by the legacy `sdkmanager` |
| Android SDK | `C:/Users/lc/AppData/Local/Android/Sdk` | `ANDROID_HOME` set; `local.properties` also written (gitignored) |
| compileSdk / targetSdk | 34 | `build-tools;34.0.0` + `platforms;android-34` installed |
| AGP | 8.5.2 | Compose via `org.jetbrains.kotlin.plugin.compose` 2.0.21 |

Installing SDK pieces needs the *legacy* `tools/bin/sdkmanager.bat` (there is no `cmdline-tools`) and it only runs on
JDK 8:

```bash
export JAVA_HOME="C:/Users/lc/scoop/apps/openjdk8-redhat/current"
yes | "$ANDROID_HOME/tools/bin/sdkmanager.bat" --licenses
"$ANDROID_HOME/tools/bin/sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0"
```

On JDK 11/17 that tool dies with `ClassNotFoundException: javax.xml.bind.annotation.XmlSchema`. Everything else in the
build runs on JDK 17.

### TLS workaround (historical)

`gradle.properties` used to force `-Djdk.tls.client.protocols=TLSv1.2` for a TLS 1.3 resumption bug in JDK 11.0.2
(`No PSK available. Unable to resume.`). With Gradle 8.7 on JDK 17 those flags are gone and not needed.

## Activation and the FairPlay key

Alberts refuses to issue a device certificate without a FairPlay signature, and Apple's FairPlay private key is
Apple-proprietary. PLAN.md decision 2 picks the on-device route for this personal build, so the pair lives in
`:core:apns`:

* `AlbertFairPlay` holds the static key (base64 PKCS#1 PEM) and certificate chain (base64 concatenated DER) plus
  `credentials`, a lazily built `FairPlayCredentials`; `AlbertFairPlayTest` asserts the chain is Apple's four
  certificates, the key is RSA-1024, and the leaf certificate covers the embedded key;
* `Albert` wraps `ActivationClient` with those credentials — `Albert().activate()` mints a device key, asks Alberts for
  a certificate, and returns the `ActivatedDevice` (nothing is persisted for you);
* the Android app exposes that as the **Servers** tab's primary action (Activate when there is no identity, Start once
  there is) and as **Settings → Activate on this device**, and still accepts an imported identity;
* `tools/cli activate --store identity.json` uses the embedded pair by default; `--fairplay-key/--fairplay-chain`
  (or `ABARK_FAIRPLAY_KEY`/`ABARK_FAIRPLAY_CHAIN`) replace it, e.g. to try a different chain.

**Do not redistribute a build that contains the key.** It is not the APNs sending key, but it is Apple material; the
proxy mode in PLAN.md decision 2 is the public-release path.

Tests inject a throwaway RSA key and a self-signed chain (`TestCertificates`), so the activation path — CSR
construction, signature over the exact uploaded bytes, form encoding, response parsing, certificate-key matching — is
verified without depending on the embedded pair. The embedded material gets its own fixtures above, so a corrupted
base64 blob fails the build rather than the first real activation.

## Testing philosophy

* Wire format is tested against **hand-computed byte vectors** (`PacketCodecTest`) and one independent constant,
  `sha1("me.fin.bark")`.
* Command assembly is verified by **checking the RSA-SHA1 signature** over the nonce with the public key, which is what
  Apple does on the other end.
* The courier session is driven by a **scripted in-memory channel** (`ScriptedChannel`): happy path, refused
  `ConnectAck`, missing token, token rotation, refused scoped token, message-before-scoped-token, unknown commands,
  quiet-line keepalives, truncated packets, protocol violations, clean close. No sockets, no timers, no flakiness.
* Activation and the Bark client are driven through **fake `HttpTransport`s**, covering 2xx/4xx/5xx, non-JSON bodies,
  unparseable plists, missing certificates, foreign-key certificates and IO failures.
* Pure decision logic (`NotificationSpec`, `ReleaseSelector`, `SemanticVersion`, backoff) is unit-tested directly, so
  the Android layer stays a thin mapping onto platform objects.
* Anything marked **verify** in `docs/PROTOCOL.md` is a hypothesis, not a fact.

## Android specifics worth knowing

* The courier runs on a plain thread inside a `remoteMessaging` foreground service; `CourierLoop` is blocking by design
  and `stop()` just closes the socket, which unblocks the read.
* A `BOOT_COMPLETED` receiver plus a 15-minute inexact alarm (`RestartReceiver`) re-arm the service after reboots and
  OEM task-killer kills.
* Notification channels are created per Bark `group`; importance follows `level`
  (`critical`/`timeSensitive` → high, `active` → default, `passive` → low/silent).
* Identity + history storage is app-private SharedPreferences / a JSON file. The documented next step is a
  Keystore-wrapped AES-GCM blob (`androidx.security:security-crypto` is deprecated, so it is a small in-house wrapper).
* Release signing is CI-only: `.github/workflows/release.yml` writes `keystore.properties` from secrets; a local
  `assembleRelease` without it produces an unsigned APK on purpose.

## Repo layout

```
app/                     foreground service, notifications, inbox, Compose UI
core/net/                HTTP transport abstraction
core/apns/               protocol, activation, transport, session, identity
core/bark/               payloads, notification spec, bark-server API, links
core/update/             update decision logic (pure JVM)
core/update-android/     install + signature verification (Android)
tools/cli/               phase-0 harness
docs/                    PROTOCOL, DEVELOPING, DISTRIBUTION
```
