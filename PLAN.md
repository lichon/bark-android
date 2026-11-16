# bark-android — Engineering Plan

**Goal:** An Android app that is a drop-in client for [Finb/bark](https://github.com/Finb/bark) / `bark-server`
(same `/push` API, same device keys, same payload options), which receives notifications **directly from
Apple's APNs courier over 5223** by emulating an Apple device — i.e. a Kotlin port of the APNs layer of
[pypush](https://github.com/JJTech0130/pypush). No FCM, no Google services, works on de-Googled ROMs.

**Repo status:** empty (`main`), remote `github.com/lichon/bark-android`.

---

## 1. Why this is possible (feasibility)

APNs is bidirectional. A device opens a mutually-authenticated TLS connection to
`N-courier.push.apple.com:5223` (ALPN `apns-security-v3`, fallback `v2`), does a `Connect` handshake, gets/keeps a
**base push token**, tells the server which *topics* it wants via SHA-1 topic hashes, mints a **scoped token** for a
topic, and then receives app payloads (and ACKs them). Apple's HTTP/2 send API (`api.push.apple.com`) then delivers
to that token. pypush already proves the whole chain end-to-end, including sending a real push with the test harness
(`tests/test_apns.py`).

Bark's server is unusually friendly to us:

| Fact (from bark-server source) | Consequence |
| --- | --- |
| Topic is the constant `me.fin.bark` (watch: `me.fin.bark.watch`) | We filter one topic; no per-app provisioning |
| `apns/apns_certs.go` ships a **shared p8 auth key** (key `LH4T9V5U4R`, team `5U8LBRXG3A`) | Any self-hosted bark-server (and api.day.app) can push to our token with **zero server changes** |
| `/register` accepts `device_token` + `device_type` (`ios` \| `watchos`), token ≤ 160 chars | Register our 64-hex scoped token as `device_type=ios` → server uses `me.fin.bark` |
| Payload = `aps.alert.{title,subtitle,body}`, `aps.sound`, `aps.thread-id`, `aps.mutable-content`, custom params lowercased at top level | App parses the raw JSON itself; full parity mapping |
| `delete` push = `content-available`/background push, `CollapseID = msg.Id` | "remove notification" feature supported |
| Archive/history is **client-side only** (no `/messages` API) | Room DB owns history; no server work |

**So the MVP needs no server fork at all.** A fork is only needed for optional extras (§6).

Prior art: pypush (Python, SSPL) and OpenBubbles `rustpush` (Rust, used by the OpenBubbles Android/Flutter app) both
do exactly this; JJTech's write-up documents the layer structure. That de-risks the protocol but creates a **licensing**
problem (§11) — plan assumes a **clean-room Kotlin implementation from the wire format**, not a code port.

---

## 2. Architecture

```
┌────────────────────────────── Android app (Kotlin) ──────────────────────────────┐
│                                                                                  │
│  :app (Compose UI)                                                               │
│    Onboarding / Servers / Device keys / Inbox / Settings / Debug console         │
│         │                                                                         │
│  :core:push ─────────────────────────────────────────────────────────────────    │
│    PushTransport (interface)                                                     │
│      ├── ApnsCourierTransport  ← raw SSLSocket :5223, PacketStream, state machine│
│      ├── WebSocketTransport    (phase 5 fallback, needs server route)            │
│      └── FcmTransport          (phase 5, optional, google-services.json)         │
│    CourierService (ForegroundService, type=remoteMessaging)                      │
│    Watchdog (AlarmManager + WorkManager) / BootReceiver                          │
│         │                                                                         │
│  :core:apns ──────────────────────────────────────────────────────────────────    │
│    activation/Albert + FairPlay  → device cert + RSA key (Keystore-wrapped)      │
│    protocol/{Packet,Codec,Commands}  ← golden-vector tested against pypush       │
│    Connection (connect → ack → filter → scopedToken → receive → ack, keepalive)  │
│         │                                                                         │
│  :core:notify ────────────────────────────────────────────────────────────────    │
│    BarkPayloadParser → NotificationSpec → NotificationRenderer (channels, groups,│
│    icon fetch, sound, markdown, actions) + Dedupe + BadgeCounter                 │
│         │                                                                         │
│  :core:data ──────────────────────────────────────────────────────────────────    │
│    Room (messages, servers, devices) + DataStore (settings) + EncryptedPrefs     │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
        │ HTTPS /register, /push, /ping                 │ TLS :5223 (ALPN)
        ▼                                               ▼
   bark-server (stock)                            N-courier.push.apple.com
        │ HTTPS/2 + p8 JWT                              ▲
        └────────────► api.push.apple.com ──────────────┘  (topic me.fin.bark)
```

Data flow for one notification:

1. `bark-server` POST `/push` → APNs HTTP/2 → courier.
2. Courier sends `SendMessage` (type 10) on our socket: `{token, topicHash, payload, id}`.
3. `Connection` matches `token == scopedToken && topicHash == sha1("me.fin.bark")`, ACKs (`SendMessageAck`, type 11).
4. `BarkPayloadParser` → `NotificationSpec` → renderer posts a notification, writes Room row.
5. Tap → `PendingIntent` → in-app detail (or open `url`).

---

## 3. Protocol implementation notes (the crux)

### 3.1 Wire format

```
packet := type:u8 | payloadLen:u32be | field*
field  := id:u8    | valueLen:u16be | value
```

Packet types: `7 Connect, 8 ConnectAck, 9 FilterTopics, 10 SendMessage, 11 SendMessageAck,
12 KeepAlive, 13 KeepAliveAck, 14 NoStorage, 17 ScopedToken, 18 ScopedTokenAck, 20 SetState`.
Unknown types must be logged and ignored (Apple adds fields/commands over time).

### 3.2 Session sequence

1. **Connect** (type 7), fields:
   * `1` base token (absent/empty on first ever connect, then cached)
   * `2` state = `1`
   * `5` flags = `65` (4 bytes)
   * `12` device certificate (DER, from activation)
   * `13` nonce = `0x00` + `now_ms:u64be` + 8 random bytes
   * `14` signature = `0x01 0x01` + `RSA_PKCS1v1_5_SHA1(deviceKey, nonce)`
   * keep `9/10/11` (os version/build/hardware) available for parity experiments
2. **ConnectAck** (type 8): `1` status (must be 0), `3` token (may be absent → cached token accepted).
   If token changed → persist and **re-run `/register`**.
3. **FilterTopics** (type 9): `1` base token, `2` sha1("me.fin.bark") (one field per topic).
   Repeat on every reconnect; re-send when the topic set changes.
4. **ScopedToken** (type 17): `1` base token, `2` topicHash → **ScopedTokenAck** (18): `1` status, `2` scoped
   token, `3` topicHash. Compare with stored token; if different, `POST /register {device_key, device_token,
   device_type:"ios"}`.
5. **Receive loop**: `SendMessage` → ACK, else `KeepAlive`/`KeepAliveAck` (12/13) every `keepaliveInterval`.
6. Bootstrapping: courier host `random(1..50)-courier.push.apple.com`, SNI `courier.push.apple.com`,
   TLS 1.2+, **certificate verification disabled** (Apple-internal PKI) but only on that socket.

### 3.3 Activation (device identity)

`POST https://albert.apple.com/deviceservices/deviceActivation?device=Windows` with a plist body containing
`ActivationInfoXML` (a plist with `DeviceCertRequest` = PEM CSR over a fresh **RSA-1024** key, `ProductType`,
`ProductVersion`, `BuildVersion`, `DeviceClass`, `UniqueDeviceID`, `SerialNumber`), `FairPlayCertChain` and
`FairPlaySignature` (PKCS#1 v1.5 SHA-1 with the Apple FairPlay key). Response HTML contains `<Protocol>…</Protocol>`
plist → `device-activation.activation-record.DeviceCertificate` (PEM). Persist: UDID, serial, product type,
device key (Keystore-wrapped), cert, base token, scoped token, device key of the bark server.

Implementation pieces: BouncyCastle `bcpkix` for CSR/PKCS#10, `dd-plist` for plist read/write, `SHA1withRSA` raw
signature (verify allowed on API 34/35 with the bundled BC provider rather than Conscrypt).

**Legal/design decision:** do **not** ship Apple's FairPlay private key in the APK. Two supported modes:
* **(A, default) Activation proxy** — our tiny service performs the Alberts call with the FairPlay key and returns
  the issued cert; the CSR/private key never leave the phone. Keeps the proprietary blob off-device and lets us patch
  activation without an app release.
* **(B, power users) Bring-your-own activation blob** — user pastes a `cert+key` export; app validates and stores it.
* **(C, dev only) Local Chaquopy/pypush runtime** for research builds; never distributed (SSPL + key).

### 3.4 Things to measure, not assume

* Keepalive tolerance: pypush pings every 30 s; real APNs is far lazier. Sweep 30 s → 5 min and find the NAT/Apple
  idle cutoff; default to the largest safe value up to the 300 s ceiling (battery!). Above 300 s the interval, which is
  also the socket read timeout, starts costing more in delayed failure detection than it saves in radio wakeups.
* Whether `SetState` (20) is required after `FilterTopics` on current servers, and the exact `flags`/`state` values
  that keep the session from being dropped (Beeper-style blocking lands here).
* Topic-hash overlap behaviour when multiple apps share the connection (we only need one topic).
* Courier rotation: reconnect to a *different* host index after repeated failures; remember the working one.

---

## 4. Android platform engineering

| Concern | Decision |
| --- | --- |
| Long-lived socket | `ForegroundService` with `FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING` (API 34+) + persistent low-priority notification ("Push connected / last message 3 min ago") |
| Process death / reboot | `BOOT_COMPLETED` receiver, `AlarmManager.setExactAndAllowWhileIdle` watchdog every 15 min re-arming the service, `START_STICKY`, plus WorkManager periodic health check |
| Doze / OEM killers | Request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` during onboarding with explanation; per-OEM guidance (Xiaomi/Huawei autostart); detect "service killed" and surface a fix-it card |
| TLS with client cert + ALPN | Raw `SSLSocket` from `SSLSocketFactory` over an in-memory `PKCS12` KeyStore; ALPN via `SSLParameters.setApplicationProtocols` (API 24+); Conscrypt provider explicitly installed; TLSv1.2 minimum |
| Trust | Courier socket uses a scoped `X509TrustManager` that trusts Apple's courier CA (pin it); never globally disable verification |
| Network changes | `ConnectivityManager.NetworkCallback` → reconnect on default-network change; separate handling for VPN (APNs over VPN works, but MTU/rekey timeouts happen) |
| Battery | 1 socket + sparse keepalive ≈ 15–40 mAh/day. Budget: measure 24 h soak on LTE, target < 2 %/day idle. No wakelocks held; socket read in a coroutine with timeout |
| Security | All identity material in `EncryptedSharedPreferences`/Keystore; no secrets in logs; debug packet log behind a build flag and redacted |
| Notification pipeline | Channels per `group`; `level` → importance (`critical`→high + alarm-ish + `fullScreenIntent` optional, `timeSensitive`→high, `active`→default, `passive`→low/silent); `sound` → bundled Bark sounds or system; badge via `ShortcutBadger`-style launcher intents + in-app count; `icon` downloaded with Coil and cached; `url`→tap action; `copy`→auto-copy + action; `markdown` → render to `Spannable` with a small MD subset (or open in-app viewer); `delete` → cancel notification by collapse id |
| Permissions | `POST_NOTIFICATIONS` runtime request, `FOREGROUND_SERVICE(+_REMOTE_MESSAGING)`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`, `ACCESS_NETWORK_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

Bark parity mapping (v2 API): `title/subtitle/body`, `level`, `volume`, `badge`, `call`, `autoCopy/copy`, `sound`,
`icon`, `group`, `ciphertext`, `isArchive`, `ttl`, `url`, `action=none`, `device_keys` batch (server-side only).

`ciphertext`: implement Bark's E2E scheme (AES-GCM with key derived from device key) if we want parity; otherwise
display an "encrypted message" placeholder. Decide in phase 3.

---

## 5. Tech stack & repo layout

* Kotlin 2.x, Jetpack Compose (Material3), coroutines/Flow, Hilt-free manual DI (or Koin) to keep it light
* Room + DataStore + EncryptedSharedPreferences
* OkHttp for HTTP (server API), raw `SSLSocket` for courier
* BouncyCastle (`bcprov-jdk18on`, `bcpkix-jdk18on`), `dd-plist`, Coil, kotlinx-serialization
* minSdk 26, target/compile latest, `versionCatalog` (`libs.versions.toml`), Gradle KTS

```
settings.gradle.kts
app/                     # Compose UI, nav, DI wiring
core/apns/               # protocol codec, activation, connection state machine (pure Kotlin/JVM tests)
core/push/               # PushTransport, CourierService, watchdog, boot
core/notify/             # payload parser, channels, renderer, badge, actions
core/data/               # Room, DataStore, repos, server API client
tools/mock-courier/      # Kotlin/JVM scriptable TLS courier for tests
tools/trace-diff/        # Python: pypush trace vs Kotlin trace byte diff
docs/PLAN.md, docs/PROTOCOL.md, docs/RUNBOOK.md (ops: activation proxy), docs/LEGAL.md
```

`core/apns` and `core/notify` must be **plain JVM modules** (no Android deps) so they can be unit-tested and diffed
against the Python reference on CI.

---

## 6. Optional server work (phase 5)

A fork/patch of `bark-server` (or a small sidecar "bark-relay") adding:

1. `device_type: "android"` accepted (mapped to topic `me.fin.bark` for APNs-mode devices; avoids confusing 400s),
   plus a `transport` field so the server knows how to deliver.
2. `GET /ws?device_key=…` WebSocket (or SSE) endpoint as a **fallback transport** for networks that block :5223.
   Server fan-outs every `/push` to both APNs and any live WS subscriber; the app de-dupes by message hash.
3. Delivery receipts: app `POST /ack {message_id, status}` so the server can retry/fallback ("APNs accepted" ≠ delivered).
4. Health endpoint for the app's diagnostics screen.

All optional; the stock server works for MVP. Keep the server contract transport-agnostic so APNs can be killed by
Apple without breaking the app.

---

## 7. Milestones

| Phase | Deliverable | Gate (must pass) | Est. |
| --- | --- | --- | --- |
| **0. Spike** | JVM CLI: activation → connect → filter → scoped token → receive; run on desktop JVM **and** via adb on an emulator + physical phone | (a) A push sent by a stock self-hosted bark-server `/push` renders as a log line **on the phone over LTE**; (b) that push used the shared bark p8 key against topic `me.fin.bark` and returned HTTP 200 — i.e. APNs accepts a scoped token for a topic never actually installed; (c) ~4 h soak without drops | 5–7 d |
| **1. Courier service** | Foreground service, state machine, persistence of identity/token, reconnect/backoff, boot & watchdog, diagnostics screen with connect state + last packets | Survives reboot, airplane mode, Wi-Fi↔LTE, 24 h idle with < 2 %/day battery and < 5 reconnect/hour | 7–10 d |
| **2. Notifications** | Parser, channels/importance, grouping, icon, sound, badge, actions (url/copy), delete, dedupe, local archive | Side-by-side parity table vs Bark iOS for 15 sample pushes (incl. empty body, huge body, markdown, call) | 6–8 d |
| **3. Bark UI parity** | Server add/edit/test, device key + shareable `bark://` URL + QR, inbox (groups, search, mark read, delete by group), settings, onboarding with battery/notification guidance | Install → receive first push in < 60 s, no docs needed | 8–10 d |
| **4. Hardening** | Token rotation, cert expiry re-activation, 410/rate-limit handling, OEM killer matrix, crash-free telemetry (local), packet capture tool, migration/backup | 72 h soak on 3 OEM skins + 2 AOSP ROMs, 0 lost pushes in a 1 push/10 s burst test | 8–10 d |
| **5. Fallback + release** | WS/relay transport + server patch, optional FCM, signed release, Obtainium/F-Droid-metadata, update check, LEGAL.md | Cold install of signed APK on clean device, verified upgrade path | 6–8 d |

Total ≈ 8–10 weeks for one experienced Android dev, assuming phase 0 succeeds fast (that gate is the real risk —
do not build UI before it passes).

---

## 8. Testing strategy

1. **Golden vectors**: instrument pypush (`PYTHONPATH` + a small dumper) to emit hex for every command we implement;
   commit them as `core/apns/src/test/resources/vectors/*.hex`; Kotlin codec must round-trip byte-identically.
2. **Mock courier** (`tools/mock-courier`): self-signed TLS server with ALPN `apns-security-v3` that plays scripted
   sessions — happy path, new-token-on-ack, status≠0, abrupt close, mid-packet truncation, unknown packet types,
   duplicate/out-of-order sends, no-ack-then-close. Runs in instrumented tests on an emulator (port 5223 is
   privileged-free, but bind to 15223 and inject host/port for tests).
3. **Replay**: recorded `SendMessage` payloads from a real bark-server → parser/renderer snapshot tests.
4. **Live canary** (manual/nightly): self-hosted bark-server + real phone; push script asserts on-device receipt
   within N s (app exposes `/debug/lastReceived` via `adb shell am broadcast`).
5. **Battery/network profiling**: Battery Historian + Energy Profiler; 24 h soak × (Wi-Fi, LTE, 2G-ish, VPN, captive).
6. **Chaos**: clock skew (nonce timestamp), flaky DNS, TLS handshake stalls, proxied TLS (MTU 1400), SIM swap,
   double-SIM default network change.

---

## 9. Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| **Apple blocks the fake-device path** (Beeper Mini precedent) | Total loss of APNs mode | Transport-agnostic design + WS fallback (§6); server-side feature flag; app degrades to fallback with a clear banner; never make APNs the only path |
| **Licensing**: pypush/rustpush are SSPL/"NOASSERTION"; FairPlay key is Apple's | Legal exposure if we copy code or ship the key | Clean-room Kotlin from the wire format; activation proxy keeps the key server-side; `docs/LEGAL.md` documents provenance; no code copied from pypush |
| **Apple ToS / distribution** | No Play Store; possible takedown of the activation proxy | Sideload/Obtainium/F-Droid metadata; name must not use Bark's trademark ("Bark" is Finb's); state clearly it's unofficial |
| **OEM process killers** | Missed pushes | FGS + battery-exemption flow + per-OEM onboarding + watchdog + "connection lost" breadcrumb notification |
| **Activation breaks (albert changes)** | New installs fail | Proxy-mode (A) lets us patch server-side without releasing; app caches identity and can restore from backup |
| **Token/cert rotation churn** | Old key 410s | Detect `Unregistered` on `/push` (server side) and surface "re-register" CTA; re-mint scoped token after every connect and diff |
| **Bark's shared p8 key is revoked / its topic changes** | Delivery stops; the fix is server-side and not ours | Topic and server are single config values (`ABARK_APNS_TOPIC`, `ABARK_SERVER`) so switching to our own key/topic or a self-hosted server is a config change; document the switch in the app's diagnostics screen |
| **Battery complaints** | Bad reviews | Default keepalive 2 min, tunable 30–300 s (120 s on mobile data, up to 300 s on Wi-Fi); "do not disturb at night" option (drop socket 00:00–07:00); battery stats card in app |
| **Security**: trust-all TLS, stored identity | Credential theft | Trust pinned Apple courier CA only; Keystore-wrapped keys; no plaintext tokens in DB; optional biometric lock on the debug export |

---

## 10. Decisions (locked)

| # | Decision | Consequence to watch |
| --- | --- | --- |
| 1 | **No APNs auth key in the app.** Pushes are sent by Bark's public server (`api.day.app`) with Bark's shared p8, topic `me.fin.bark`. A stock self-hosted `bark-server` also works unchanged. | We inherit Bark's key *and* Bark's infrastructure. If either changes or the key is revoked, delivery stops and the fix is server-side, outside our control. (The app was never going to hold the sending key — it only receives — so this costs us nothing at runtime.) |
| 2 | **On-device Alberts activation** with the embedded FairPlay key + cert chain. *(Implemented: `AlbertFairPlay` + `Albert` in `:core:apns`, exposed as Settings → Activate on this device; CLI and app both default to it.)* | This *does* ship Apple's FairPlay private key inside the APK. It is not the APNs auth key from (1), but it is Apple-proprietary: fine for a personal build, **do not distribute**. Revisit (proxy mode) before any public release. |
| 3 | **Test against `api.day.app`**, no self-hosted server. | Their logs see push contents; per-key rate limits and server behaviour are not ours to tune. Use a throwaway device key. Self-hosted bark-server remains a drop-in substitute. |
| 4 | **Personal build.** | No Play / F-Droid / Obtainium obligations; the activation proxy, clean-room provenance work and public-release hardening are dropped. The update checker stays (already written, cheap) but is optional. |
| 5 | **Name `ABark`, applicationId `io.github.lichon.abark`**, artifact `abark-<version>.apk`. | "ABark" keeps Finb's "Bark" as a descriptive reference — defensible for a personal, sideloaded client; rename before any public distribution, since keystore, signature pinning and saved device keys outlive the name. |

Deferred (not needed for phase 0/1): encrypted pushes (`ciphertext`), the WebSocket fallback transport, `minSdk` (default 26),
and switching to our own APNs key/topic.

## 11. First two weeks (concrete)

**Day 1–2 — reference harness.** Python venv, `pip install pypush`, run `tests/test_apns.py` against a sandbox
project to confirm the current wire behaviour still works (ALPN v3, connect flags, scoped token). Record hex of every
packet with a 30-line monkey-patch dumper; commit under `tools/trace-diff/vectors/`.

**Day 3–4 — identity.** Kotlin/JVM CLI: BouncyCastle RSA-1024 + CSR + plist + Alberts POST → parse cert; save
`identity.json`. Gate: `openssl x509 -text` shows an Apple-issued `iPhone Device` cert.

**Day 5–7 — transport.** `SSLSocket` + ALPN, `PacketCodec`, `Connect`, `ConnectAck`, `FilterTopics`, `ScopedToken`,
`SendMessage` loop, ACK. Gate: custom push from a stock bark-server appears in the CLI on a desktop and on a phone
(Chaquopy or a tiny `am`-driven harness).

**Day 8–10 — service shell.** FGS with `remoteMessaging`, boot receiver, reconnect/backoff, persistent status
notification, debug screen showing state/token/packet counts. Gate: 4 h soak, no drops, survives reboot.

**Day 11–14 — first real notification.** Parser + channel + renderer for `title/body/group/level/url/copy/delete`;
Room persistence; tap → open URL. Gate: 15-case parity sheet signed off.

Write the results of phase 0 into `docs/PROTOCOL.md` (measured keepalive tolerances, flags, ALPN fallback behaviour) —
that document is the source of truth for the rest of the project.

---

## 12. Distribution: why not Google Play

This is not a technical limitation — an APNs-emulating client *can* be packaged as an AAB — but publishing it on Play
is the highest-cost, lowest-value option. Concrete reasons, in order of severity:

1. **Account-level risk, not app-level.** Play enforcement escalates rejection → removal → suspension → developer
   account termination, and termination takes down *every* app you publish. A single rights-holder complaint is enough
   to start that ladder. Don't put an unrelated app catalogue behind an experiment like this.
2. **IP exposure we cannot engineer away.** The activation path fundamentally rests on Apple's FairPlay private key and
   an Apple-issued device certificate; the Bark name/icon are Finb's marks; a copied pypush/rustpush implementation is
   SSPL. Google acts on IP/trademark notices, and Apple has a track record of shutting down APNs-based impersonation
   (Beeper Mini, Dec 2023). Even the *listing* is a problem: to be honest it has to say "receives Apple Push
   Notification service pushes on Android", which is exactly the claim that invites the complaint.
3. **Deceptive-behavior / impersonation policy.** Play's Deceptive Behavior policy covers apps that "misrepresent or
   mislead users about their functionality, content, or affiliation" and behaviour that changes after review. A client
   that activates as an Apple device and talks to an undocumented Apple endpoint, with activation logic that can be
   swapped server-side, sits squarely in the second and fifth failure modes (undisclosed/revocable behaviour).
4. **Foreground-service declaration is a poor fit.** Play requires declaring each FGS type with a use case, a
   justification and a demo video. The only type that fits an always-on socket is `remoteMessaging`, whose documented
   purpose is "transfer text messages from one device to another … when they switch devices" — that is not what we do.
   The honest choice is `specialUse`, which is explicitly reviewed and can be rejected. A mismatch here is a policy
   finding on its own, independent of the APNs question.
5. **Review latency kills the product.** When Apple changes the courier handshake (it has before), users need a fix the
   same day. Play review is days and staged rollout is not optional; a GitHub Release is hours. For a niche,
   technically literate audience, Play adds risk and delay and no reach.
6. **Onboarding overhead for zero benefit.** New personal Play accounts need closed testing with 12 testers for 14
   continuous days before production, plus yearly target-API compliance, Data Safety declarations, and an FGS video —
   all to distribute to users who already install APKs from GitHub.

### What to do instead

> With decision 4 (personal build) only the first bullet is in scope. The Obtainium/F-Droid/Play notes below are kept as reference for a later, wider distribution.

* **Primary: GitHub Releases + [Obtainium](https://github.com/ImranR98/Obtainium)** for auto-update from releases;
  tag `vX.Y.Z`, publish `app-release.apk` + SHA-256 + changelog. Add an in-app "update available" check against the
  GitHub API. Full design, CI workflow and drop-in Kotlin implementation: [`docs/DISTRIBUTION.md`](docs/DISTRIBUTION.md)
  and `core/update/`.
* **Optional: IzzyOnDroid / F-Droid** — only if the activation proxy keeps Apple's key material off the device and
  there are no proprietary blobs in the APK; declare `NonFreeNet` (talks to Apple's service) as an anti-feature and
  keep the build reproducible. F-Droid's inclusion does not reduce legal exposure, so treat it as a nice-to-have.
* **If Play distribution is a hard requirement:** ship a *separate, honest* app on Play that is only a relay client
  (our own WebSocket/SSE + optional FCM against a self-hosted bark-relay, §6) with **no** device emulation, no Apple
  endpoints, no activation code. Keep APNs mode in the sideloaded build. Never enable emulation from the server after
  install — that is the bait-and-switch clause and it is the one that escalates fastest.
* Keep `docs/LEGAL.md` current: protocol provenance (clean-room from observed wire format, no pypush code), activation
  material handling, the non-affiliation disclaimer, and a short note that using APNs from non-Apple hardware violates
  Apple's terms — the user makes that choice knowingly.
