# APNs courier protocol (as implemented in `:core:apns`)

Everything here is from observed traffic and public reverse-engineering work (JJTech's pypush write-up and the
`pypush`/`rustpush` sources), reimplemented independently in Kotlin. Values marked **verify** are empirical and must be
re-measured by the phase-0 harness before we depend on them.

## Transport

| | |
| --- | --- |
| Host | `<1..50>-courier.push.apple.com` (rotate on failure; remember the one that worked) |
| Port | 5223, plain TCP wrapped in TLS |
| SNI | `courier.push.apple.com` |
| ALPN | `apns-security-v3` (older builds: `apns-security-v2` — try v3, fall back) |
| TLS | 1.2+; **server certificate verification is disabled** — Apple's courier uses an internal PKI. Trust is established by the client certificate, not the host. Scope the trust-all manager to this socket only. |
| Client auth | Alberts-issued device certificate + 1024-bit RSA key (see §Activation) |

## Framing

```
packet := type:u8 | payloadLength:u32be | field*
field  := id:u8   | valueLength:u16be  | value
```

Big-endian everywhere. Field ids repeat (one field per topic, for instance). Unknown packet types and unknown fields
must be preserved/ignored rather than treated as fatal — Apple adds commands over time.

Implemented in `core/apns/src/main/kotlin/io/github/lichon/abark/apns/Packet.kt` (framing) and
`…/transport/` (channel, session, loop). The session is driven in tests by a scripted in-memory channel, so every
branch below is covered without a socket.

## Commands

| Id | Name | Direction | Fields |
| --- | --- | --- | --- |
| 7 | Connect | → | 1 base token (omit on first connect), 2 state=1, 5 flags=65 (4 B), 12 cert (DER), 13 nonce, 14 signature |
| 8 | ConnectAck | ← | 1 status (0 = ok), 3 token (absent ⇒ our token was accepted) |
| 9 | FilterTopics | → | 1 token, 2 × N topic SHA-1 hashes |
| 10 | SendMessage | ← | 1 token, 2 topic hash, 3 payload (raw JSON), 4 message id |
| 11 | SendMessageAck | → | 1 token, 4 message id, 8 status (1 B) |
| 12 | KeepAlive | → | (none) |
| 13 | KeepAliveAck | ← | 1 unknown |
| 14 | NoStorage | ← | 1 token — ignored today |
| 17 | ScopedToken | → | 1 base token, 2 topic hash |
| 18 | ScopedTokenAck | ← | 1 status, 2 scoped token, 3 topic hash |
| 20 | SetState | → | 1 state, 2 unknown (4 B) — **verify** whether it is required |

### Nonce and signature (Connect fields 13/14)

```
nonce     = 0x00 | nowMillis:u64be | 8 random bytes          (17 bytes)
signature = 0x01 0x01 | RSA-PKCS1v1.5-SHA1(deviceKey, nonce) (130 bytes with RSA-1024)
```

The two leading `0x01 0x01` bytes are constant in captured traffic; the SHA-1 signature is over the nonce only.

## Session sequence

1. TLS handshake with the device certificate; negotiate ALPN.
2. Send `Connect`. First ever session: no base token. Later sessions: send the cached base token.
3. Read `ConnectAck`. `status != 0` ⇒ activation/cert problem, not a network problem. If field 3 is present and differs
   from the cached token, persist the new one.
4. Send `FilterTopics` with `sha1("me.fin.bark")` (the topic `bark-server` pushes to).
5. Send `ScopedToken(baseToken, sha1(topic))`; the returned scoped token is what the push server targets. This is the
   value registered with `bark-server` via `POST /register {device_token, device_type:"ios"}`.
6. Loop: on `SendMessage` → parse, display, send `SendMessageAck`. Send `KeepAlive` periodically and tolerate the ack.
7. On any `IOException`/EOF → tear down, back off, reconnect (possibly to another courier host). The base token
   survives reconnects; the scoped token should be re-minted and compared (idempotent in practice).

`FilterTopics` must be re-sent after every reconnect: the filter is per connection, not per token.

### How the client implements it

| Concern | Implementation |
| --- | --- |
| Handshake | `CourierSession.handshake()` — Connect → ConnectAck → FilterTopics → ScopedToken → ScopedTokenAck, keeping any `SendMessage` that arrives early and delivering it in order afterwards |
| Token rotation | `ConnectAck` without field 3 means "your token is still valid"; a *new* field 3 is persisted and reported to the caller |
| Keepalive clock | The read timeout **is** the clock: `CourierChannel.read(timeout)` returns null on a quiet line and the session sends `KeepAlive`. No timer thread to leak when the service is killed |
| Acknowledgement | Every `SendMessage` is acked with fields 1+4+8 before it is handed to the app |
| Unknown commands | Reported to the listener and ignored; never fatal |
| Reconnect | `CourierLoop`: rotating `N-courier.push.apple.com` host, exponential backoff with jitter (2 s → 60 s), base token reused across reconnects |
| TLS | `TlsCourierChannelFactory`: in-memory PKCS12 with the device certificate, ALPN v3 → v2, TLS 1.2+. Server verification is trust-all **scoped to this socket only** (see the comment in `TlsCourierChannel.kt`) |

## Topic hashing

`sha1(topic)` as 20 raw bytes, and the topic is bundle-id shaped. Known value used by this app:

```
"me.fin.bark" -> 667bc4834b4efda62efc2a77d37ad5e1c9703b67
```

(`:core:apns` asserts this vector.)

## Activation (device identity)

```
POST https://albert.apple.com/deviceservices/deviceActivation?device=Windows
Content-Type: application/x-www-form-urlencoded
body: activation-info = plist{ActivationInfoComplete, ActivationInfoXML, FairPlayCertChain, FairPlaySignature}
```

* `ActivationInfoXML` is a plist with `DeviceCertRequest` (PEM PKCS#10 CSR over a fresh **RSA-1024** key),
  `ProductType`, `ProductVersion`, `BuildVersion`, `DeviceClass`, `UniqueDeviceID`, `SerialNumber`,
  `ActivationRandomness`.
* `FairPlaySignature` = RSA-PKCS1v1.5-SHA1 over the `ActivationInfoXML` bytes with Apple's FairPlay key.
* Response is HTML containing `<Protocol>…</Protocol>`; that plist holds
  `device-activation.activation-record.DeviceCertificate` as a **`<data>`** blob whose bytes are PEM (older responses
  used a `<string>`, and both are accepted — the parser also wraps a raw-DER blob). The issued certificate is valid for
  one year, e.g. `2026-11-16 → 2027-11-16`.

The FairPlay key and chain are the static pair every independent client ships; `:core:apns` carries them in
`AlbertFairPlay` (see PLAN.md decision 2) and `Albert` is the facade that uses them.

The private key never leaves the device; only the CSR does. Persist UDID, serial, product type, key, certificate, base
token, scoped token, and the bark device key.

## Inbound payload (what `bark-server` sends)

`SendMessage.payload` is raw JSON, typically:

```json
{"aps":{"alert":{"title":"t","subtitle":"s","body":"b"},"sound":"minuet","thread-id":"group",
        "category":"myNotificationCategory","mutable-content":1},
 "title":"t","body":"b","group":"group","url":"https://…","level":"active","copy":"…","icon":"https://…"}
```

Custom parameters are copied to the **top level**, lowercased by the server. Total payload ≤ 4096 bytes.

## To measure in phase 0

* [ ] Whether Alberts still accepts the `device=Windows` path and 1024-bit device keys — the gate for everything else.
* [ ] Keepalive interval tolerance (30 s → 5 min): how long can the socket idle before Apple drops it, and how does
      that change on LTE with a NAT timeout in between?
* [ ] Whether `Connect` `flags=65` / `state=1` are still accepted; what the server sends when it dislikes them.
* [ ] Whether `SetState` (20) is required after `FilterTopics`.
* [ ] Scoped-token stability across reconnects and across base-token rotation.
* [ ] Behaviour when the Alberts certificate has expired (what `ConnectAck.status` is).
* [ ] Whether `NO_STORAGE` (14) needs answering on a topic with delivery history.
* [ ] The exact shape of a "delete" push (`delete` as `"1"`/`true`/id at the top level); the client currently accepts
      all three and cancels the referenced notification.
* [ ] Whether a server-initiated `KeepAlive` needs fields on the ack (we send an empty one).
