# Changelog

Release notes for a tag are extracted from the matching section by `.github/workflows/release.yml` and used as the
GitHub release body (which the in-app update screen shows). Keep sections short, user-facing, and in the app's
voice. Format: `## [<version>] - <date>` where `<version>` matches the tag without the `v`.

## [0.2.0] - 2026-11-17

### Added
- APNs courier engine: framing/codec, Connect/Filter/ScopedToken/SendMessage/KeepAlive commands, TLS channel with ALPN,
  session state machine, reconnect loop with host rotation and backoff.
- Device identity: Alberts activation (plist, PKCS#10 CSR, FairPlay signature, certificate intake) with offline tests,
  and an identity store that survives restarts.
- Alberts activation works out of the box: `:core:apns` bundles the static FairPlay key + cert chain (`AlbertFairPlay`),
  `Albert` is the one-call facade, `tools/cli activate` defaults to the embedded pair, and the app activates from the
  phone — **Servers** shows **Activate** until an identity exists (activation then connects), **Settings** keeps
  **Activate on this device** and the identity import.
- Bark client: payload parser → notification spec, `bark-server` API client (`/register`, `/push`), `bark://` links.
- Android app: `remoteMessaging` foreground service, per-group notification channels with Bark levels, inbox, boot and
  watchdog receivers, Compose UI, in-app update check.
- Three-tab UI — **Servers** (courier state, server URL/key, share link), **Messages** (inbox with unread badge) and
  **Settings** (delivery permissions, identity, updates) — replacing the single scrolling screen.
- Inbox actions: long-press a message for **Copy content** (title and body, or the URL when there is no body) and
  **Delete** (removes the stored message and its notification).
- Spoken notifications: **Settings → Delivery → Read notifications aloud** reads each push with the system
  text-to-speech engine (title, subtitle and body, repeats dropped). Off by default; passive pushes are not spoken and
  a silent/vibrate phone stays quiet.
- CLI harness (`tools/cli`): `activate`, `run`, `push`, `link`, `show`.
- 158 JVM unit tests covering protocol, activation (including the embedded FairPlay pair and the live response
  shapes), transport, payloads and release selection.
- App icon: the ring mark from `lichon/bark-desktop` (`bark_pc/assets/icons`) as an adaptive launcher icon, with a
  round variant and a monochrome layer for themed icons.

### Changed
- **Servers**: the **Save** button is now **Register** — it stores the server URL and then reconnects so the scoped
  token is registered with that server right away.
- The device key is no longer typed by hand: `bark-server` mints it during `/register`, the app stores the key the
  server answers with, and the Servers tab shows it read-only (Register works with no key at all).
- The keepalive moved from **Servers** to **Settings → Delivery**, with an **Apply** button that reconnects so the new
  interval takes effect. The range is now **30–300 s** (was 30–900): the interval doubles as the socket read timeout, so
  anything longer both delays spotting a dead line and invites a carrier NAT to reap the mapping first, and the UI
  rejects out-of-range values instead of silently clamping them.

### Fixed
- Alberts returns `DeviceCertificate` as a `<data>` blob of PEM bytes, not a `<string>`; activation now accepts
  `<string>`, PEM-in-`<data>` and raw-DER-in-`<data>`, so a real Apple response is no longer misread as "no
  certificate". Verified live: on-device Activate issues a one-year certificate and the courier connects.

### Notes
- Fresh installs default the server to the public `https://api.day.app` service, so only a device key and a device
  identity are needed to start; the field can still be pointed at a self-hosted `bark-server`.
- Activation signs with Apple's FairPlay key, which the build now carries (PLAN.md decision 2): fine for a personal
  build, **do not distribute** the APK.
- Distribution, in-app update check plumbing and the update-android install path join the build.

<!--
Template:

## [1.4.2] - 2026-03-01

### Added
- ...

### Fixed
- ...
-->
