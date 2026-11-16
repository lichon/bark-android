# Distribution & in-app updates

Sideload-first distribution: **GitHub Releases** as the canonical artifact store, **Obtainium** as the
zero-effort updater for most users, and a **built-in checker** for everyone else. No Play Store (see
`PLAN.md` §12), no third-party update SDK, no embedded GitHub token.

Guiding rules:

1. **Never install silently.** The app may download and verify an APK, but the user presses the system install
   dialog. `PackageInstaller` + explicit user action only.
2. **Signature-pinned.** An APK is only offered for install if its signing certificate matches the installed app
   (or an explicit, hardcoded rotation allowlist). This is the real defence — checksums published next to the
   artifact share its trust root.
3. **Fail quiet.** A failed update check never blocks startup, never shows an error dialog, and is retried at most
   once a day.
4. **Forward only.** `versionCode` must strictly increase. Downgrades are refused (Android blocks them anyway) and
   rollbacks are published as a *new, higher* version.

---

## 1. Versioning

* Tag: `vMAJOR.MINOR.PATCH` (stable) or `vMAJOR.MINOR.PATCH-beta.N` (pre-release, also flagged `prerelease` on GitHub).
* `versionName` = tag minus the `v`.
* `versionCode = major * 10000 + minor * 100 + patch` → `1.4.2` = `10402`. Constraints: `minor < 100`,
  `patch < 100` (enforced by `SemanticVersion`), `major < 210000` (Android's `versionCode` ceiling).
* A hotfix must bump at least `patch`; a docs-only change needs no release at all.
* `CHANGELOG.md` holds user-facing notes per version; the release body is the section for that version
  (users see it in-app).

## 2. Release artifacts (per GitHub Release)

| Asset | Required | Purpose |
| --- | --- | --- |
| `abark-<versionName>.apk` | yes | Signed release APK, single universal artifact |
| `SHA256SUMS.txt` | yes | `<sha256>  abark-<versionName>.apk`; parity with Obtainium/`gh` users |
| `update.json` | optional | Machine-readable override (see §7); take precedence over API heuristics when present |
| `mapping.txt` | **no** | Keep R8 mappings private; upload to a private store for crash triage |

Optionally add ABI splits later (`abark-<version>-arm64-v8a.apk`); the selector in §5 already ranks them.

## 3. CI: `.github/workflows/release.yml`

Triggered by a tag push (`v*`) or `workflow_dispatch` (with a channel input for re-running a beta). It refuses to
publish an APK that is unsigned, signed with the wrong key, or built with a stale `versionCode`.

Secrets (repo → Settings → Secrets):

* `KEYSTORE_B64` — release keystore, base64 of the `.jks`/`.keystore` file
* `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

The keystore is **the** irreplaceable asset: back it up offline (two copies, different media). Losing it means every
user must uninstall/reinstall, and `ApkSignatureVerifier` will (correctly) refuse to update them — see §6.

## 4. In-app update checker — behaviour

State machine:

```
idle ──(app start, lastCheck > 24h)──▶ checking ──▶ upToDate | available | unsupported
  ▲                                        │
  └──────────(manual "Check now", any time)┘         └── download → verify → install prompt
```

* **Throttle**: automatic check at most once per 24 h (persisted `lastCheckMillis`); a manual check always runs.
* **Transport**: `GET https://api.github.com/repos/<owner>/<repo>/releases?per_page=30` with
  `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, a real `User-Agent`, and
  `If-None-Match: <etag>`. Store the `ETag`; a `304` is free of rate-limit cost, which matters because the
  unauthenticated limit is ~60 req/h **per IP** (carrier NAT/CGNAT can share that across thousands of users).
* **Never embed a GitHub PAT.** It is extractable from the APK. If rate limits ever bite, publish `update.json`
  from CI to GitHub Pages (or any static host) and point the checker at it — same schema, no rate limit.
* **Channel**: `STABLE` (default, skips `prerelease` and `draft`) or `BETA` (accepts prereleases). Exposed as a
  settings toggle; the channel is stored locally and never sent anywhere.
* **Selection** (`ReleaseSelector`): parse versions from `tag_name`, ignore drafts, filter by channel, require an
  `.apk` asset, prefer the device's ABI then universal, then take the highest version whose `versionCode` is
  **strictly greater** than the installed one.
* **Mandatory updates**: if the installed `versionCode` is below `minSupportedVersionCode` (§7), the UI switches
  from "Update available" to a blocking card *only for the APNs transport* (the app must remain usable as a plain
  Bark client) — this is the kill-switch path for a courier protocol break.
* **UI**: banner/card in Settings + an opt-in notification; shows new version, release notes, size, and the link to
  the GitHub release page. Never auto-opens a download.

## 5. Download & verification (must all pass)

1. HTTPS only; reject redirects to non-HTTPS; cap asset size (`MAX_APK_BYTES`, e.g. 100 MB).
2. Stream to `cacheDir/updates/<name>.apk.part`, then rename; check `Content-Length` vs bytes written.
3. If `SHA256SUMS.txt` is attached, verify the digest (cheap consistency check, not a trust boundary).
4. **Mandatory**: `ApkSignatureVerifier.isTrusted(...)` — APK's signing certs (as parsed by `PackageManager`,
   i.e. the same check the installer will perform) equal the installed app's certs, or are in `PINNED_ROTATION`.
5. Only then expose "Install". Otherwise delete the file and log the reason to the in-app diagnostics screen.

**Why the signature check is the only real one:** the APK, the checksums, and `update.json` all come from the same
GitHub release. If that release were compromised, all three would be. Pinning the signing certificate to the one
already on the device makes the attack require the release *key*.

## 6. Install flow

* Manifest: `REQUEST_INSTALL_PACKAGES`, plus `<receiver android:name=".update.InstallResultReceiver" android:exported="false"/>`.
* Pre-flight: `packageManager.canRequestPackageInstalls()`; if false, deep-link the user to
  `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:` data (this is per-source consent the app cannot
  grant itself — expected friction, one time).
* Install via `PackageInstaller` session (`MODE_FULL_INSTALL`, `setAppPackageName(packageName)`) with a mutable
  `PendingIntent` receiver — **no** `FileProvider`/`ACTION_VIEW` needed, so no exposed content URI.
* Result handling: `STATUS_PENDING_USER_ACTION` (dialog shown), `STATUS_SUCCESS` (process will be killed),
  `STATUS_FAILURE_*` (surface the message; `INSTALL_FAILED_UPDATE_INCOMPATIBLE` almost always means a signing-key
  mismatch — the app should then tell the user to reinstall from GitHub instead of retrying).
* After a successful install, note the pending `versionCode` in prefs so the next launch can show "Updated to
  1.4.2".

## 7. `update.json` (optional override, also the kill switch)

Published per release (and mirrored to Pages):

```json
{
  "versionCode": 10402,
  "versionName": "1.4.2",
  "minSupportedVersionCode": 10200,
  "channel": "stable",
  "apk": "abark-1.4.2.apk",
  "sha256": "…",
  "notes": "…",
  "url": "https://github.com/lichon/bark-android/releases/tag/v1.4.2"
}
```

When present it wins over API heuristics (useful when a release has no APK yet, or when GitHub API is rate-limited).
`minSupportedVersionCode` only gates APNs transport, never the whole app.

## 8. Obtainium / F-Droid

* **Obtainium** (`ImranR98/Obtainium`) works out of the box: point it at `https://github.com/lichon/bark-android`.
  Keep the asset name containing the version (`abark-1.4.2.apk`), publish real (non-draft) releases, and mark betas
  as `prerelease` so Obtainium's stable channel skips them. Users get update *notifications* from Obtainium and
  don't need our in-app checker (offer "I use Obtainium" to hide it).
* **F-Droid / IzzyOnDroid**: only with the activation proxy (Apple's key material never in the APK), no proprietary
  blobs, reproducible build, and the `NonFreeNet` anti-feature declared. Their clients handle updates; keep the
  in-app checker disabled when the installer package is not ours (detect `installerPackageName != packageName`
  is *not* reliable — use an explicit user setting instead).

## 9. Testing

* Unit: `SemanticVersionTest` (precedence, prerelease ordering, `versionCode` monotonicity, rejection of
  `1.2.3.4`/`1.300.0`), `ReleaseSelectorTest` (draft/prerelease/channel filters, ABI ranking, downgrade refusal,
  missing APK asset, tag-vs-asset mismatch).
* Instrumented: fake `UpdateSource` feeding canned JSON; assert the downloaded bytes are hashed, the wrong-signature
  APK is rejected, and the installer is only invoked on user action.
* Manual matrix: fresh install → update prompt → install; unsigned CI artifact → rejected; release with a
  `debug`-signed APK → rejected; airplane mode → silent failure, no dialog; rate-limited 403 → silent, retried
  next day.

---

## 10. Implementation notes (already in the repo)

The updater is split so its decision logic stays JVM-testable: `:core:update` (pure Kotlin, no Android imports,
19 unit tests runnable without an SDK) and `core/update-android/` (Android-only glue, compiled from phase 1).
Pure sources live in `core/update/src/main/kotlin/io/github/lichon/abark/update/`:

| File | Role |
| --- | --- |
| `SemanticVersion.kt` | strict semver parse/precedence + the `versionCode` scheme (pure) |
| `ReleaseModels.kt` | API DTOs, `UpdateCandidate`, `UpdateChannel`, `UpdateDecision` (pure) |
| `UpdateSource.kt` | `UpdateSource` interface + `GithubReleaseSource` (ETag, 304, rate-limit handling) |
| `ReleaseSelector.kt` | channel/ABI/downgrade filters and version ranking (pure) |
| `core/update-android/…/ApkSignatureVerifier.kt` | installed-vs-archive signer digest comparison, `VerifiedApk` proof object |
| `core/update-android/…/UpdateInstaller.kt` | `PackageInstaller` session, unknown-sources deep link, result receiver |
| `core/update-android/…/UpdateRepository.kt` | throttle + ETag cache + streaming download + SHA-256 + verification |

Module dependencies (`core/update/build.gradle.kts`, added with the Gradle skeleton in phase 1):

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
}
android {
    compileSdk = 35; minSdk = 26
    buildFeatures { buildConfig = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
```

Manifest additions in `:app`:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<receiver android:name="io.github.lichon.abark.update.InstallResultReceiver" android:exported="false" />
```

Wiring:

```kotlin
// Application.onCreate (or a startup initializer): never blocks, never shows an error.
appScope.launch { runCatching { updateRepository.maybeCheck() } }

// Settings -> "Check for updates":
available = updateRepository.checkNow()

// After the user taps "Download":
val apk = updateRepository.download(candidate, expectedSha256) { done, total -> progress = done to total }
updateRepository.install(apk) // no-op install prompt if canInstallPackages() is false -> show the deep link

// Optional backstop when the app is rarely opened (24h, network-constrained):
WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
    "update-check", ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS).build(),
)
```

> `.github/workflows/release.yml` becomes active once phase 1 adds `gradlew` + `settings.gradle.kts`; until then it is
> a specification. It intentionally fails the build on a debug-signed APK and on a `minor`/`patch` that would break the
> `versionCode` scheme.
