# Manual end-to-end testing

The unit suites cover the domain thoroughly — `sbt test`, `./gradlew test` and
`swift test` between them exercise splitting, combining, signing, canonical byte
construction and every service rule. What they cannot cover is the part that only exists
when three devices, a real relay and a real network are involved: encryption across
platform boundaries, consent round-trips, and the social flows.

That is what this document is for. It replaces the near-duplicate flow lists that used to
live in both app READMEs.

## Start a relay

From `deposplit.com/`:

```bash
sbt run -Dconfig.file=conf/localhost.conf
```

It listens on port 9000 and uses a file-backed H2 database, so state survives restarts.

## Point a device at it

The relay URL is **not** a build-time constant on either platform. Both apps resolve their
default relay at runtime, and both let you change it from the in-app **Settings** screen.
`RelayDefaults` supplies a single fixed fallback (`https://api.deposplit.com`) when nothing
is configured.

| Target | Set the default relay to | Notes |
|---|---|---|
| Android emulator | `http://10.0.2.2:9000` | `10.0.2.2` is the emulator's alias for the host. Cleartext to that host is already allowed by `app/src/debug/res/xml/network_security_config.xml`. |
| Android device | `http://<your-LAN-IP>:9000` | Same Wi-Fi. The `10.0.2.2` alias is emulator-only. |
| iOS Simulator | `http://localhost:9000` | The Simulator shares the host's network stack. |
| iOS device | `http://<your-LAN-IP>:9000` | Same Wi-Fi. Add the IP and port to `play.filters.hosts.allowed` in `conf/localhost.conf`. |

This is a one-time step per fresh install; the setting persists across restarts.

## Three devices

The full social flow needs **three instances** — Alice plus two holders. Three emulators,
three simulators, or a mix.

- *Android*: create two extra AVDs (API 29+) in the Device Manager and launch them
  alongside the first.
- *iOS*: Xcode runs one simulator from the Run button, but you can open more via
  **Xcode → Open Developer Tool → Simulator**, then **File → Open Simulator**.

Mixing platforms is not just possible but the most valuable configuration — see Flow 6.

## Flow 1 — Happy path, 2-of-2 across two holders

Tab names differ slightly by platform: Android says **My Shared Secrets** / **Their Secret
Shares**, iOS says **Distributed** / **Held**. They are the same two things.

| Step | Device | Action |
|---|---|---|
| 1 | A | Launch, register as "Alice" |
| 2 | B | Launch, register as "Bob" |
| 3 | C | Launch, register as "Carol" |
| 4 | A | QR icon in the top bar → Alice's QR appears |
| 5 | B | Contacts → add contact → enter Alice's keys manually (or scan); then show Bob's QR |
| 6 | C | Same — add Alice, then show Carol's QR |
| 7 | A | Add Bob and Carol as contacts |
| 8 | A | ＋ → label ("test secret"), secret text, select Bob and Carol, threshold 2-of-2 → **Deposit** |
| 9 | A | Distributed view shows one grouped card for the secret; expand to see both holders |
| 10 | B | Held view shows Alice's deposit → the app auto-approves, decrypts, and stores the **plaintext** share; the relay clears the ciphertext |
| 11 | C | Same |
| 12 | A | Expand the card → **Request Retrieval** (opens retrievals for both holders at once) |
| 13 | B | Requests → retrieval from Alice → **Approve**. The app re-encrypts the stored plaintext to Alice's *current* key |
| 14 | C | Same |
| 15 | A | Both holders show "Approved" → **Reconstruct** → biometric prompt → the secret appears |

Step 10 is the one worth watching closely: it is where holder-decrypts-at-pickup happens,
and where the relay stops holding anything.

## Flow 2 — Deny and re-request

At step 13, Bob taps **Deny** instead. Alice's view shows "Denied" with a retry affordance;
she re-requests and Bob approves.

## Flow 3 — Sender-initiated removal

Alice opens a **Removal** request on one share. Bob's Requests tab shows it; Bob approves;
Bob's deposit row is deleted, cascading to any related retrieval and removal rows, and the
share disappears from Bob's Held view.

## Flow 4 — Holder-initiated deletion

Bob deletes Alice's share from his Held view directly — swipe on iOS, delete icon on
Android — with no request and no approval. If Bob holds several shares from Alice, the
confirmation also offers to delete all of them. Then check what Alice sees on refresh: she
should learn about it eventually, but never by a row simply going missing.

## Flow 5 — Offline and error states

Kill the relay, then open or refresh both apps.

- Distributed and Held views must still render **from local storage**, with a soft
  "relay not reachable" banner rather than a blocking error.
- The Requests tab queries the relay for pending events that are not stored locally, so it
  will show an error. That is correct behaviour.

Restart the relay, navigate away and back — the banner clears and data refreshes.

## Flow 6 — Cross-platform

Run Alice on iOS and Bob on Android at the same time, against one relay. Alice deposits for
Bob; Bob sees it in Held; Bob opens a retrieval; Alice reconstructs.

This is the highest-value flow in this document, because it is the only test that proves
CryptoKit and BouncyCastle produce interoperable X25519 + HKDF-SHA-256 +
ChaCha20-Poly1305 bytes on a live wire. The vector tests prove the canonical byte
constructions agree; this proves the whole stack does.

## Flow 7 — Bring Your Own Relay

Run **two** relays on different ports:

```bash
sbt run -Dconfig.file=conf/localhost.conf                      # port 9000
sbt run -Dconfig.file=conf/localhost.conf -Dhttp.port=9001     # port 9001
```

Both relay editors sit behind the Premium unlock, so unlock first: on iOS buy it in the
Simulator (the scheme carries `Deposplit.storekit`, so no App Store Connect record is
needed); on Android set `FAKE_PREMIUM=true` in `local.properties` and rebuild, since Play
Billing cannot run without a Play Console listing.

Give one contact a `relayBaseUrl` override pointing at 9001 and leave another with no
override. Then verify that deposit, pickup, retrieval and removal all route through the
override for the first contact while the second still round-trips through the default —
and that killing one relay degrades only that contact, leaving the other's operations
working. The fan-out is independently soft-failed per relay precisely so that holds.

## Flow 8 — Locale

Switch the device to German and relaunch. All strings should appear in German, with dates
in `dd.MM.yyyy`. On Android: **Settings → General management → Language**.

## Edge cases worth checking

- **Fresh keypairs after reinstall.** Clearing app data or reinstalling generates new keys;
  existing contacts can no longer decrypt shares sent to the old ones.
- **Reconstruct stays hidden below threshold.** The button must not appear until *k*
  approved retrievals exist for the same `secretId`.
- **2-of-3 with only two approvals** still reconstructs.
- **Integrity margin.** With three holders and a 2-of-2 threshold, all three approving gives
  a margin of one — enough to *detect* a bad share. Confirm the reconstruction advisory
  reports the margin honestly rather than claiming more confidence than it has.
- **Verification levels.** Manual key entry defaults to `VERY_LOW` and offers `LOW`/`HIGH`
  but never `VERY_HIGH`; QR scan defaults to `VERY_HIGH`.
- **Biometric behaviour on Android differs by API level.** On API 30+ the prompt offers
  "or use PIN"; on API 29 it is biometric-only, because the combined
  `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` authenticator is unavailable there.
- **Key-change indicator.** After a contact rotates keys, their retrieval requests should
  carry the "key changed N days ago" warning — and only retrieval requests.
