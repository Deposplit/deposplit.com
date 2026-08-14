# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Deposplit** is a secret-sharing app based on **Shamir's Secret Sharing (SSS)**. A secret is split into *n* shares, of which *k* are required to reconstruct the original secret. The app sends each share to one of *n* contacts and later reassembles the secret when at least *k* holders cooperate.

## How We Got Here

Deposplit's architecture evolved through several design sessions:

1. **The Signal question:** The project started by exploring whether a third-party app could piggyback on Signal — its contacts, groups, and messaging infrastructure. Signal is intentionally an appliance, not a platform: it exposes no SDK or inter-app API.

2. **Matrix adopted as transport:** Matrix was identified as the natural fit — designed to be built upon, with Android/iOS SDKs, arbitrary custom message types, E2EE via Double Ratchet, and federation. The SSS libraries (Kotlin and Swift ports of the Privy TypeScript reference) were built and tested. An Android scaffold was completed with a working OIDC sign-in flow (Chrome Custom Tab → matrix.org → deep-link callback).

3. **DCR friction with matrix.org:** matrix.org's Matrix Authentication Service rejected Deposplit's Dynamic Client Registration attempts (`invalid_redirect_uri`). This triggered a re-evaluation of the transport layer.

4. **Pivot to custom Web app/service:** Matrix is heavyweight for Deposplit's actual protocol (4 message types). Since recipients must install Deposplit, federation between homeservers adds no user value. A custom deposplit.com Web app/service with E2EE was chosen: simpler, leaner, and the server provably cannot read share content regardless of breach.

5. **Web App/Service redesigned as a stateless relay:** The initial Web app/service design included a `users` registry and a `contacts` table with a server-mediated invitation flow. This violated the trust-minimising philosophy: the server knew who the users were and who knew whom. The Web app/service was simplified to a pure relay with no user registration and no contact storage. Key exchange happens out-of-band (QR code in person, or via Signal/Threema). The server authenticates callers by verifying Ed25519 signatures against the public key supplied in each request header — no pre-registration required. The DB schema shrank from four tables (`users`, `contacts`, `shares`, `share_requests`) to two (`shares`, `share_requests`).

6. **Unified single-table relay:** The two-table schema (`shares` + `share_requests`) was collapsed into a single `share_requests` table with three request types: `pick_up` (deposit), `retrieve`, and `delete`. All three follow the same symmetric consent model — Alice requests something of Bob; Bob can approve or deny. Every row is self-describing with embedded `sender_key` and `recipient_key`, making the relay fully stateless. The DB schema now has one table.

## Architecture Decisions

### Communication Layer: Custom Web App/Service

The transport layer is a **custom deposplit.com REST API** with end-to-end encryption. No native crypto libraries are used on any platform — everything is implemented using each platform's standard crypto stack (BouncyCastle on Android/JVM, Swift Crypto / CryptoKit on iOS).

Key design decisions:
- **User identity is two keypairs.** At first launch the device generates an X25519 keypair (share encryption) and an Ed25519 keypair (API authentication). The user picks a pseudonym (display name only, stored locally on the device — never sent to the Web app/service). No server registration is required: the keypair IS the identity. Contacts exchange both public keys out-of-band — ideally in person via QR code, or via a trusted third-party channel (Signal, Threema, email).
- **Server is an opaque relay.** The Web app/service stores and forwards ciphertext only. It never participates in key agreement and cannot decrypt share content regardless of a breach. Reconstructing the original secret requires obtaining at least *k* of the recipients' shares, which live only on their devices — so a full relay breach yields nothing. *(Under the holder-decrypts-at-pickup redesign — "What is next" item 7 — the holder stores the plaintext share, so this means compromising *k* holders' devices or defeating *k* holders' retrieve-consent, not their X25519 private keys.)*
- **Library-agnostic authentication protocol.** API requests are authenticated via Ed25519 signatures (RFC 8032) over a canonical request representation. Mobile clients sign with BouncyCastle (Android) or Swift Crypto (iOS); the Web app/service verifies with BouncyCastle (`Ed25519Signer`). Ed25519 is deterministic and fully specified — cross-library interoperability proves the protocol is correctly defined, not a coincidence of using the same library. The canonical signing string is:
  ```
  nonce || "\n" || UPPERCASE(method) || "\n" || path_with_query || "\n" || hex(SHA-256(body))
  ```
  where `body` is the empty string for requests without a body. Three request headers carry the authentication material: `X-Deposplit-Public-Key` (caller's Ed25519 public key, base64url), `X-Deposplit-Nonce` (per-request unique string in the form `<unix-ms>.<random>`; server rejects requests whose embedded timestamp is more than 5 minutes old), `X-Deposplit-Signature` (base64url-encoded signature).
- **No federation needed.** Recipients must install Deposplit, so cross-server communication adds no user value. Deposplit operates a single canonical Web app/service at deposplit.com.
- **No practical client exclusivity.** There is no cryptographically sound way to restrict the API to the two official native apps. Hardcoded secrets are extractable from binaries; certificate pinning proves the connection is not intercepted but not which software is running; Play Integrity / App Attest are bypassable on rooted/jailbroken devices and introduce Google/Apple as gatekeepers. This is not a gap — the security model does not rely on client exclusivity. Because the server is cryptographically blind, a rogue client can only act within the bounds of the keypair it controls; it cannot read other users' shares or impersonate other users. The only realistic abuse vector (spam, resource exhaustion) is addressed by rate limiting and storage quotas, as with any public API. An open, auditable protocol is consistent with Deposplit's trust-minimizing philosophy.

Rejected alternatives:

| Option | Reason rejected |
|---|---|
| Matrix | Heavyweight (sliding sync, room state, ~20 MB native SDK) for a 4-message protocol; matrix.org DCR restrictions create friction for third-party clients; federation adds no user value since recipients must install Deposplit |
| XMPP + OMEMO | Similar to Matrix but older, more fragmented ecosystem, weaker mobile SDKs |
| Signal Protocol (libsignal) | AGPL-3.0 licence — incompatible with a more permissive app licence; Double Ratchet is designed for continuous conversations; Deposplit's sparse one-shot share deposits do not benefit from per-message key ratcheting |
| Nostr | NIP-44 E2EE is newer and less battle-tested; relay infrastructure reliability varies |
| P2P (WebRTC, Bluetooth, DHT) | Async delivery requires persistent storage; true P2P without infrastructure cannot reliably hold shares for offline recipients over days or months |

### Repository Structure

The [Deposplit GitHub organization](https://github.com/Deposplit) contains independent repositories, each cloned into a corresponding subfolder of the local `Deposplit/` workspace (which is itself not a git repository):

| Folder | Repository | Purpose |
|---|---|---|
| `deposplit.com/` | [Deposplit/deposplit.com](https://github.com/Deposplit/deposplit.com) | Project hub, landing page, cross-project documentation, and Web app/service server |
| `Android/` | [Deposplit/Android](https://github.com/Deposplit/Android) | Kotlin SSS library + Android app (`:hexagon` + `:app` Gradle modules) |
| `iOS/` | [Deposplit/iOS](https://github.com/Deposplit/iOS) | Swift SSS library + iOS app (SwiftUI, iOS 26+) |

### Web App/Service Tech Stack: Scala + Play

The `deposplit.com` repository is a **Play Framework (Scala)** application built with **sbt**. It serves two distinct concerns:

- **Landing page / GUI**: server-side rendered with **Twirl** templates
- **REST API**: consumed by the Android and iOS apps (spec at `conf/openapi.yaml`)

Architecture follows **Ports & Adapters** enforced by sbt's multi-project build, mirroring the approach in the global CLAUDE.md:

| sbt subproject | Role |
|---|---|
| `hexagons/relay` (sbt project `relay`) | Pure Scala library — business logic, port interfaces, no Play/framework imports. Packages: `value_objects`, `driving_ports`, `driven_ports.persistence`, `driving_adapters` |
| `hexagons/phon` (sbt project `phon`) | Phone emulator for manual end-to-end testing — mirrors the Android/iOS hexagon structure; simulates a device calling the live `http://localhost:9000` backend. Packages: `value_objects`, `driving_ports` (`Identity`, `ShareManagement`, `ContactManagement`), `driven_ports` (`IdentityStore`, `ContactRepository`, `ShareRelay`, `ShareRepository`, `ShareMetadataRepository`), `driving_adapters` (`IdentityService`, `ShareEncryption`, `ContactService`, `ShareService`) |
| root (Play app) | Adapters (DB, Web app/service API controllers), Twirl views, routes |

The `hexagons` subprojects have **no dependency on Play** or any infrastructure library. The root Play project depends on `hexagons`; `hexagons` must never depend on the root. This enforces the hexagonal boundary at the build level.

The hexagons and root both programme **synchronously (blocking)** — no Scala `Future`s. This keeps the code straightforward and stack traces readable; with Java virtual threads becoming mainstream, blocking I/O will carry negligible cost.

**Key library choices:**
- **sbt** build tool (use standard `build.sbt` and `project/` Scala/sbt files)
- **Play JSON** (`play-json`) for API serialisation — bundled with Play
- **Twirl** (built into Play) for the landing page
- **BouncyCastle** (`bcprov-jdk18on`) for Ed25519 signature verification — declared in `hexagons/relay/build.sbt` because signature verification is a domain concern; no native libsodium on the server — share content passes through as opaque bytes
- **PostgreSQL** for persistent storage — see rationale below
- **H2** as an in-memory database for development and testing (no PostgreSQL instance required locally); configured with `MODE=PostgreSQL` in `conf/localhost.conf`. H2 compatibility constraints to keep in mind when editing the evolutions script: use `TIMESTAMP WITH TIME ZONE` not `TIMESTAMPTZ`; place `DEFAULT expr` before `PRIMARY KEY` in column definitions; avoid semicolons inside `--` line comments (H2 tokenises them as statement terminators); partial indexes (`WHERE` clause) are not supported — the one-pending-request-per-type constraint is enforced at the application level in `ShareRequestsService` (`hasActivePickUp` + `hasPendingRequest`) instead and must be added to production PostgreSQL manually (see comment in `1.sql`). The `NULL`-able `share_id`, `ciphertext`, and `responded_at` columns in `share_requests` use standard nullable types — H2 handles these without special treatment. `secret_created_at` is `NOT NULL` with no `DEFAULT` (client-supplied); `requested_at` is `NOT NULL DEFAULT now()` (server-generated).
- **Anorm** for database access (preferred over Slick) — SQL-first, minimal abstraction, fits cleanly in the adapter layer of the hexagonal architecture; Slick (type-safe DSL) is an acceptable alternative if type-safe query composition is preferred
- **Play Evolutions** for schema migrations — initial schema at `conf/evolutions/default/1.sql` (one table: `share_requests`)
- **OpenAPI 3.0** spec at `conf/openapi.yaml` — covers all REST endpoints; kept in sync with the Play routes file

**Why PostgreSQL over MongoDB:**
Deposplit's data model is relational: shares and consent requests are entities with typed, stable relationships. MongoDB's schema flexibility is not needed and would give up meaningful guarantees:
- **Relational integrity**: foreign keys and cascading deletes prevent orphaned share records (a data-integrity concern for a security app)
- **ACID transactions**: the consent state machine (approve retrieval, approve sender-initiated deletion) requires atomicity — approving a request and releasing share bytes must be one transaction
- **`bytea` type**: maps directly to opaque share ciphertext; structured metadata lives in typed columns alongside it
- **Native UUID type**: fits `secret_id` exactly
- **Row-level security (RLS)**: enforces at the DB layer that a session can only see its own rows — defense-in-depth if the application layer has a bug

### CLAUDE.md Layout

Claude Code discovers `CLAUDE.md` files by walking up the directory tree from the working directory. The cross-project guidance lives here (`deposplit.com/CLAUDE.md`) and is the source of truth. The workspace root (`Deposplit/CLAUDE.md`) contains a single `@`-import that loads it, so launching `claude` from `Deposplit/` automatically picks up the full context. Platform-specific guidance lives in `Android/CLAUDE.md` and `iOS/CLAUDE.md` respectively.

### Cryptography

- Secret splitting: **Shamir's Secret Sharing** (SSS)
- Parameters: *n* total shares, *k*-of-*n* threshold for reconstruction
- Share encryption: **X25519 + HKDF-SHA-256 + ChaCha20-Poly1305** — mobile only; Web app/service never decrypts
- API authentication: **Ed25519** signatures (RFC 8032) — BouncyCastle on Android, Swift Crypto on iOS, BouncyCastle on Web app/service

#### SSS Reference Implementation

**[privy-io/shamir-secret-sharing](https://github.com/privy-io/shamir-secret-sharing)** (TypeScript, MIT) is the canonical reference. Key implementation details:

- **Field**: GF(2⁸), byte-by-byte — each secret byte is treated independently over GF(256)
- **Irreducible polynomial**: x⁸ + x⁴ + x³ + x + 1 (same as AES), generator `0xe5`
- **Lookup tables**: same as HashiCorp Vault (`LOG_TABLE` / `EXP_TABLE`)
- **Share format**: `[n bytes of y-values] || [1 byte x-coordinate]`
- **Reconstruction**: Lagrange interpolation at x=0
- **Size**: ~250 lines — the entire algorithm is five functions plus two lookup tables

Both the Kotlin (Android) and Swift (iOS) implementations are **hand-ports of the Privy TypeScript**, not third-party libraries. This was chosen over alternatives for the following reasons:

| Option | Reason rejected |
|---|---|
| Bouncy Castle (`org.bouncycastle.crypto.threshold`) | `ShamirSecretSplitter` exposes `Algorithm`/`Mode` enums (multiple variants); which variant matches Privy's exact GF/generator/table choices is not documented, risking silent cross-platform incompatibility. Also a heavyweight dependency for ~150 lines of arithmetic. |
| [CharlZKP/shamirs-secret-sharing-swift-privyio](https://github.com/CharlZKP/shamirs-secret-sharing-swift-privyio) | Single-contributor repo, unknown maintenance status; acceptable as a reference while writing the Swift port, but not adopted as-is for a security-critical primitive. |

#### Transport Encryption: X25519 + HKDF-SHA-256 + ChaCha20-Poly1305

> ⚠ **Pending redesign — see "What is next" item 7 (holder-decrypts-at-pickup).** The description below is the current *encrypt-to-recipient blind-courier* model, which is being replaced: the holder will decrypt at pickup, store the plaintext share, and re-encrypt to the *current* sender at retrieve. The DH box construction and wire format below are unchanged; what changes is *who decrypts when* (and that the relay stays blind at both deposit and retrieve).

Each share is encrypted by the sender to the recipient's X25519 public key before leaving the device. The construction is a standard static-static DH box:

1. **Key agreement**: X25519(sender_private_key, recipient_public_key) → 32-byte shared secret
2. **Key derivation**: HKDF-SHA-256(ikm=shared_secret, salt=nonce, info=`"deposplit-share"`) → 32-byte symmetric key
3. **Encryption**: ChaCha20-Poly1305(key, nonce, plaintext) → ciphertext + 16-byte tag
4. **Wire format**: `nonce(12 bytes) || ciphertext+tag`

The Web app/service stores ciphertext only. A full Web app/service breach yields nothing without also compromising at least *k* recipients' X25519 private keys.

**Why no native crypto library (libsodium was the original choice, rejected Apr 2026):**

| Criterion | libsodium | BouncyCastle + Swift Crypto |
|---|---|---|
| Android native `.so` | Required (JNA/lazysodium, complex ABI setup) | Not required — pure JVM |
| iOS | Required (or manual Swift port) | Swift Crypto (Apple-maintained, no native deps) |
| Web App/Service | Not needed | Already used (BouncyCastle) |
| Cipher available everywhere | XSalsa20-Poly1305 missing from Swift Crypto | ChaCha20-Poly1305 in all three stacks |
| Auditability | Opaque prebuilt binaries | Open-source, platform-standard |

BouncyCastle provides `X25519Agreement`, `HKDFBytesGenerator`, and `ChaCha20Poly1305` on Android and the Web app/service. Swift Crypto provides `Curve25519.KeyAgreement`, `HKDF`, and `ChaChaPoly` on iOS.

#### Implementation Status

The SSS ports are **complete and fully tested**:

| Library | Module | Public API |
|---|---|---|
| `Android/` | `com.deposplit.shamir` | `split(secret: ByteArray, shares: Int, threshold: Int): List<ByteArray>` / `combine(shares: List<ByteArray>): ByteArray` — throws `IllegalArgumentException` |
| `iOS/` | `ShamirSecretSharing` | `split(secret: [UInt8], shares: Int, threshold: Int) throws -> [[UInt8]]` / `combine(shares: [[UInt8]]) throws -> [UInt8]` — throws `ShamirError` |

The Android `IdentityService` uses **BouncyCastle** (`bcprov-jdk18on`) for all crypto — no native libraries, no JNA. The iOS equivalent uses **Swift Crypto**. The Web app/service uses BouncyCastle for Ed25519 verification and passes share ciphertext through opaquely.

#### Cross-Platform Compatibility

Both test suites contain three identical hand-derived test vectors (in `ShamirTest.kt` and `ShamirSecretSharingTests.swift`) that verify `combine()` byte-for-byte against the same inputs. The vectors use the polynomial `f(x) = secret_byte + 0x01·x` in GF(2⁸) with x-coordinates `[1, 2]` — the simplest non-trivial 2-of-2 case — and were verified by hand against the GF(2⁸) arithmetic tables.

### App Protocol

Secrets are identified by a **UUID** generated at split time. The human-readable label (e.g. "BitLocker key") is display-only metadata — two secrets with the same label are distinguished by their UUIDs.

There are three request types exchanged via the deposplit.com Web app/service API. All three follow the same symmetric consent model — Alice requests something of Bob; Bob can approve or deny:

| Type | Direction | Payload | Purpose |
|---|---|---|---|
| `pick_up` | Sender → recipient | `secret_id`, `label`, `secret_created_at`, encrypted share bytes | **Deposit** a share; Bob approves to receive it |
| `retrieve` | Sender → recipient → sender | Request: references PickUp ID. Response: share bytes from Bob's local storage | **Retrieve** a specific share |
| `delete` | Sender → recipient | Request: references PickUp ID. Response: ack | **Delete** a share (sender-initiated, requires Bob's approval) |

> ⚠ **Being extended — see "What is next" items 7–10.** These are the *three current* request types. The redesign adds a **metadata-only recovery return** (item 8), a **signed rotation push** (items 9–10), and a **health-check request/ack** (item 9), plus a **"withdrawn by recipient"** row state (item 9). It also re-keys `retrieve` on **`secretId`** rather than the pickUp relay-row id (item 8), and adds **`k` and `n`** to the pick_up payload (item 8).

**Recipient-initiated deletion** is unilateral (no approval needed). The recipient can delete individual shares or all shares from a given sender at any time. *(Revised — see "What is next" item 9: it stays unilateral but is no longer purely silent; the holder's app additionally writes a best-effort "withdrawn by recipient" tombstone so the sender isn't blindsided by silent redundancy erosion.)*

**The Web app/service is a pure relay — ciphertext is ephemeral:**

> ⚠ **Pending redesign — see "What is next" item 7.** Under the holder-decrypts-at-pickup model the *bytes* differ from what's written below (the holder stores the decrypted plaintext share, not the deposit ciphertext, and re-encrypts to the current sender at retrieve), but the relay's role — store/forward opaque ciphertext, blind at every phase — is unchanged.

PickUp flow (deposit):
- **Request sub-phase** (sender → relay): Alice opens a PickUp request with the encrypted share bytes; the relay stores them.
- **Response sub-phase** (relay → recipient): Bob approves the PickUp; the relay delivers the ciphertext once and clears it from the relay row. The ciphertext now lives only on Bob's device.

Retrieve flow:
- **Request sub-phase** (sender → relay): Alice opens a Retrieve request referencing the PickUp ID; the relay stores it as pending.
- **Response sub-phase** (recipient → relay → sender): Bob approves and sends the ciphertext from his local storage; the relay stores it temporarily. Alice polls, fetches the ciphertext, then deletes the PickUp row (which cascade-deletes the Retrieve/Delete rows).

Every row is self-describing — it embeds both `sender_key` and `recipient_key`. The relay never needs to look up any other row to authorize a request.

Consequence: a relay database wipe after all recipients have picked up their shares does not destroy the secret — the shares live on the recipients' devices. The relay is a mailbox, not a store.

**Consent model:**
- *Retrieval* — the recipient must approve. This allows out-of-band verification (e.g. a phone call) that the sender genuinely requested reconstruction and is not an attacker who stole their device.
- *Sender-initiated deletion* — the recipient must approve. The sender cannot force deletion.
- *Recipient-initiated deletion* — unilateral, no approval needed.

**Notification delivery — polling only (v0.1):**
There is no WebSocket or push notification channel. Clients poll for pending events on app open and periodically while foregrounded (`GET /share-requests?role=recipient&state=pending`, etc.). Event frequency is low enough that polling is sufficient. Background push via FCM/APNs is deferred — it would introduce a Google/Apple dependency and some metadata leakage, which conflicts with Deposplit's trust-minimising philosophy.

### App Architecture: Ports & Adapters (Hexagonal)

Both the Android and iOS apps follow the **Ports & Adapters (Hexagonal Architecture)** pattern, applied strictly to the domain and infrastructure layers; the UI layer uses MVVM/MVI as is conventional on each platform.

**Domain (the hexagon core)**
Pure business logic — split/combine rules, share holder state machine, contact management, identity recovery flow. No Android or iOS framework imports. Lives in a plain Kotlin module (Android) or plain Swift package (iOS). Fast, framework-free unit tests only.

**Ports**
Interfaces defined by the domain for everything it needs from the outside world: a secrets store, a share transport, a contact repository, a notification service, etc.

**Adapters** (implement the ports)
Each infrastructure concern is a separate adapter: deposplit.com API client, OS keystore, camera, file picker, NFC, document scanner, cloud storage picker. Swapping or adding an adapter never touches the domain.

**UI layer**
Compose (Android) / SwiftUI (iOS) with ViewModels sitting at the boundary between domain and UI. Treated separately from the hexagon — Compose/SwiftUI's reactive model doesn't map cleanly to a pure port/adapter shape, and the ceremony isn't justified there.

**Navigation** is left as a platform concern and is not forced into the hexagon model.

**Structural enforcement:**
- Android: the hexagon is a pure Kotlin Gradle module; infrastructure modules depend on it, never the reverse
- iOS: the hexagon is a plain Swift package (no UIKit/SwiftUI imports); infrastructure in separate packages or targets

### Share Holder Onboarding

Before Alice can include a contact as a share holder, that contact must have Deposplit installed and Alice must have their public keys. There is no server-mediated invitation flow — contact establishment happens entirely out-of-band (QR code in person, or via a trusted third-party channel such as Signal or Threema).

**Key exchange (adding a contact):**
1. Bob generates his keypairs on first launch of his Deposplit app
2. Bob shares both his public keys with Alice out-of-band — ideally Alice scans Bob's QR code in person, or Bob sends a share link via Signal/Threema
3. Alice adds Bob to her local contact list — the Web app/service is not involved
4. Alice can now deposit shares for Bob

**Contact states in the "Split & Share" screen:**

| State | Condition | Selectable? |
|---|---|---|
| **Ready** | Alice has Bob's Ed25519 + X25519 public keys | Yes |
| **Not added** | Alice has not yet exchanged keys with Bob | No |

**All n holders must be ready (keys exchanged) before Alice can split.** There is no queuing of shares for contacts not yet added.

If a holder later withdraws consent, they do so by deleting Alice's shares locally (recipient-initiated deletion). Existing distributed shares are unaffected; Alice retains Bob's keys and can deposit new shares unless Bob explicitly asks to be removed from her contacts.

**In-person QR verification is the preferred key exchange method** — it is the only method that eliminates TOFU (trust-on-first-use) risk. Verification level is visible when Alice selects contacts and carries weight in identity recovery decisions.

### Secret Input Methods

There are many ways Alice can introduce a secret into Deposplit. Not all need to be implemented in v1; they are listed here for completeness.

| Method | Notes | Priority |
|---|---|---|
| **Type / paste** | Text field or text area | v1 |
| **File upload** | Small files; content treated as raw bytes | v1 |
| **QR code scan** | Decode a QR directly (2FA seeds, crypto keys, WiFi passwords, recovery codes) — distinct from a plain photo | v1 |
| **Share sheet / intent handler** | iOS Share Extension / Android intent — other apps push content to Deposplit without the user switching apps | v1 |
| **Take a photo** | Raw image treated as the secret | v2 |
| **Document scanner + OCR** | Scan a printed sheet (e.g. a printed BitLocker recovery key); both iOS and Android have native document scanning APIs | v2 |
| **Cloud storage picker** | iCloud Drive, Google Drive, Dropbox, etc. via the native OS file picker | v2 |
| **NFC tag read** | Read a secret stored on an NFC tag | later |
| **Voice / dictation** | Explicitly **not planned** — mic access and speech-recognition services are an unacceptable attack surface for a secret-splitting app | — |

### Contacts Management

Deposplit maintains a contact list stored **exclusively on the device** — the Web app/service never stores or indexes user identities or contact relationships.

Each contact is identified by their **Ed25519 public key** (routing identity on the Web app/service) and **X25519 public key** (used by the sender to encrypt shares client-side). Both must be obtained out-of-band before Alice can deposit shares for that contact.

Contact addition methods:
- **QR code scan (preferred):** encodes both Ed25519 + X25519 public keys and the pseudonym directly — no server intermediary, eliminates TOFU risk
- **Out-of-band link:** the app generates a shareable link carrying both public keys; Alice receives it via Signal, Threema, email, etc. Weaker TOFU assurance than an in-person QR scan, but convenient for remote contacts

Each contact record stores: Ed25519 public key, X25519 public key, pseudonym, verification level, date verified, and (BYOR) an optional per-contact `relayBaseUrl` override. All stored locally on the device. *(Planned additions — see "What is next" items 7–10: a stable local `contactId` anchor that survives key changes, and a per-key compromised/revoked flag.)*

Adding a contact is the natural moment to prompt for in-person QR verification.

### Contact Verification

> ⚠ **Superseded by "What is next" item 6 (four-level verification model).** The two-level scheme below is being replaced by a four-level ordinal one (`VERY_LOW`/`LOW`/`HIGH`/`VERY_HIGH`) derived from a trusted-channel × proof-of-life lattice; item 10 further adds a per-key *compromised/revoked* flag and the `min(level, LOW)` downgrade applied to an auto-accepted rotation. Migration: old `UNVERIFIED` → `VERY_LOW`, old `VERIFIED` → `VERY_HIGH`.

Deposplit uses a two-level verification model inspired by Threema:

| Level | How achieved | Meaning |
|---|---|---|
| **Unverified** | Contact added remotely (by pseudonym, invite link, etc.) | "I believe this Deposplit account belongs to this person, but I haven't confirmed it" |
| **Verified** | QR code scanned in person | "I was physically with this person and confirmed their public key is theirs" |

The in-person QR scan encodes the contact's public key (and optionally the pseudonym). Verification level is stored per contact and is visible to the user when reviewing share holders or approving requests.

### Identity Recovery

> ⚠ **Superseded/detailed by "What is next" item 8 (holder-driven metadata reconstitution).** The prose below is the original sketch; the spec walk resolved its `k`-of-`n`-vs-single-approver TBD and specified the mechanism — see item 8. Key corrections: recovery is *holder-driven metadata reconstitution*, not a relay-side "re-association request"; reconstruction is `k`-of-`n` *by construction* (the "single verified approver" applies only to propagating a key change to non-holder contacts); and recovery returns *metadata only*, never shares.

If Alice loses her phone and cannot recover her private key, she generates a new keypair on a new device and initiates a **re-association request**: "please map my new public key to my old one."

Recovery uses **social recovery (k-of-n)**: the same threshold k used when the secret was split must approve the re-association before it takes effect. Verification level influences the trust calculus:
- Approval from a **verified** contact (in-person QR scan) carries stronger assurance than approval from an unverified one
- A single verified approver may be considered sufficient; the exact rule is TBD

Recipients who approve a re-association should be encouraged to verify Alice again in person (re-scan her new QR code) to restore the verified relationship.

## Development Status

### What is done

See [`CHANGELOG.md`](CHANGELOG.md) for the full implementation log.

### What is next

1. **iOS biometric unlock**: The Android app gates `reconstruct()` behind `BiometricPrompt`. The iOS `ShareDetailView` currently reconstructs immediately; it should gate via `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` from the `LocalAuthentication` framework.
2. **End-to-end testing**: Test Android ↔ iOS interop (Android deposits a share, iOS recipient approves PickUp and later Retrieve, Android reconstructs) against a live `sbt run` Web app/service. Now also needs to cover BYOR: two local `sbt run` instances on different ports, one contact configured with a `relayBaseUrl` override pointing at the second instance, verifying deposit/pickup/retrieve/delete correctly route through the override while a no-override contact still round-trips through the default.
3. ~~**Defense in depth — recipient-side signature verification**~~ — **done.** Every `ShareRequest` row now carries `senderSignature` (set at open) and `recipientSignature` (set at response), Ed25519 signatures over `PayloadCanonical`'s byte constructions — independent of, and in addition to, the per-call transport-auth signature. Recipients (and senders reading back responses) independently re-verify these against the counterparty's public key from the local contact record before acting; deposplit.com's own `ShareRequestsService` also verifies them server-side as defense-in-depth. Implemented and tested on the backend (`hexagons/relay`, 89 tests) and Android (`:hexagon`, 31 tests); implemented on iOS but **not yet compiled or test-run** — see `iOS/CLAUDE.md`'s "TODO for Claude on macOS" section.
4. ~~**BYOR — Bring Your Own Relay**~~ — **self-hosted-instance backend done**, Airtable/Google Sheets adapters still future work. `Contact.relayBaseUrl` (a per-contact override, `null` = device default) plus a `ShareRelayResolver` driven port let `ShareService`/`ShareManagement` route any operation through a contact's own relay instead of deposplit.com; fan-out methods (`syncInbox`, `listPendingRequests`, `syncDistributed`, `listSentRequests`) poll every distinct relay referenced across the contact list, deduped, each independently soft-failed so one unreachable relay doesn't blank out the others. The relay override is exchanged out-of-band via the QR payload (bumped to `v:2`, new `relay` field — the *displaying* device's own configured relay) or a manual text field on "add contact". Android and iOS each gained a runtime-configurable "default relay" setting (`RelaySettings` port, a Settings screen) replacing Android's old compile-time `BuildConfig.BASE_URL`/`local.properties` mechanism entirely. Remaining: Airtable/Google Sheets adapters (need a `relayKind` discriminator on `Contact` since those aren't REST-API-shaped like a deposplit.com instance); real multi-device BYOR interop testing (item 2).

   **Deferred design note — relay-routing granularity (per-secret routing rejected).** Motivating scenario: a company mandates its own relay for company secrets only; the user also wants Deposplit for personal secrets, so appears to need per-secret relay selection. **Rejected as wrong-layer / overengineering:**
   - **Per-contact routing already keeps the two worlds apart** in the normal case — personal secrets are split among personal holders, so they only touch the company relay if a *personal* contact is deliberately pointed at it. No per-secret switch is needed to prevent contamination.
   - The only case per-contact can't express — *same holder reached via two different relays depending on the secret* — is fundamentally an **identity/governance boundary, not a property of the secret**. The coherent home is a **company identity** (own keypair, company relay as default, colleagues as its contacts) distinct from the **personal identity**; a secret then inherits its identity's relay + contact scope, making contamination structurally impossible rather than a per-secret toggle to remember.
   - **Governance honesty:** a client-side per-secret choice can't *guarantee* the company anything (a patched client could route personal data onto their relay anyway). The company's real control point is its relay **accepting only company-issued identities** — which already implies a separate company identity.
   - **How the identity boundary is realized:**
     - *OS-level separation (two app instances):* **Android** supports it user-side (Multiple users / Work Profile; Shelter/Island for a self-managed work profile; OEM "dual app"). **iOS** has no user-accessible multi-instance — only enterprise **MDM User Enrollment** (managed APFS volume + Managed Apple ID); a solo user can't. Zero Deposplit code, but the iOS gap is real.
     - *In-app profiles (Deposplit builds multi-identity within one install):* OS-agnostic app-level feature; the natural future answer where iOS-without-MDM otherwise leaves the user stuck. Bigger lift (multiple keypairs, identity switching, per-identity keystore/relay/contact scope).
   - **Billing consequence ("can one profile be Premium, the other Free?"):** *Yes, naturally, for OS-level separate instances* — the IAP entitlement is tied to the store account signed into each instance, so different Google/Apple (or Managed Apple ID) accounts give independent entitlements automatically; a company could even license the work instance's Premium centrally via managed Google Play / Apple Business Manager (a separate managed SKU is cleaner than an IAP for that). *No clean mapping for in-app profiles* — one install = one store account = one app-wide `isPremium()` bit; scoping Premium to a single in-app profile contradicts the store model. This asymmetry is itself an argument to prefer OS-level separation until iOS-without-MDM forces the in-app-profiles question.
   - **Verdict:** not a v1 feature. If Deposplit ever pursues enterprise / work-personal separation, build **profiles/multi-identity**, not per-secret relay selection.
5. **Freemium one-time unlock (optional, future)**: A single **one-time in-app purchase** ("Premium") that both removes the deposit cap and unlocks sender-side BYOR. Enforcement is **client-side only** (consistent with the server-blindness philosophy — the backend never learns payment status), and therefore honor-system by design (a patched/rogue client bypasses it) — keep the paywall light-touch, no heavy voucher/gifting infrastructure. The free/premium line:

   > **FREE:** up to *n* deposited secrets, via **our** (deposplit.com) relay only, default relay config. *(Cap counts **`ACTIVE`** secrets, not lifetime deposits — see item 11: `discardSecret` frees a slot immediately, so a re-split never double-counts.)*
   > **PREMIUM** (one one-time IAP): **unlimited** deposits **and** BYOR — may configure own / per-contact relays for outgoing shares.

   Business-model decisions settled during the spec walk:
   - **Single SKU, not à la carte.** "Unlimited via our relay" and "BYOR" are folded into *one* Premium unlock (one entitlement bit). Two separate SKUs were rejected: because BYOR is per-contact, the two capabilities overlap awkwardly (a user with both default-relay and own-relay contacts would need *both* SKUs to be unlimited everywhere), the tier boundary can't be explained in one sentence, and it doubles the StoreKit/Play + paywall surface for an honor-system nudge. Bundling now doesn't foreclose splitting later; the reverse (merging) is the painful direction.
   - **Recipient-side BYOR stays FREE.** Accepting and returning shares from a contact's own relay is never gated — a free user can be a custodian on a BYOR contact's relay. Rationale: that traffic bypasses deposplit.com entirely (no cost basis to recover), and gating it would charge the *custodian* for the *sender's* relay choice, creating a network externality. Only *originating* beyond *n*, or routing *your own* deposits through *your own* relay, sits behind the unlock.
   - **The BYOR-as-free-escape-valve inversion was considered and set aside** in favor of gating sender-side BYOR (Premium). (Recorded for context in case the ethos argument — reward self-hosting rather than tax it — is revisited.)
   - **No "gift a friend's Premium" mechanism for now.** Verifiable gifting to a specific Ed25519 key would require a signing issuer that sees a payment → recipient-key link (a small, opt-in dent in server-blindness); the zero-mechanism alternative is purely social (reimburse your friend out-of-band). Parked unless a growth loop justifies it.

   Implementation outline:
   - Add a `PurchaseRepository` driven port to the hexagon (`isPremium(): Boolean`, `secretsDepositedCount(): Int`).
   - Add a limit check in the deposit flow (hexagon service or UI layer); gate sender-side relay-override configuration on `isPremium()` too.
   - Implement a StoreKit 2 adapter (iOS) and a Google Play Billing adapter (Android).
   - Show a paywall screen when the free limit is hit in the deposit flow, or when a free user attempts to configure a sender-side relay override.
6. **Four-level contact verification model**: Replace today's two-level (`UNVERIFIED` / `VERIFIED`) scheme with a four-level ordinal one derived from a 2×2 lattice over two independent assurance axes — **trusted channel** (untrusted/trusted) × **proof of life (POL)** (sine/cum). The two incomparable middle cells of the lattice (trusted-channel-but-no-POL vs. POL-but-untrusted-channel) are deliberately **merged** into one rung, so the linear order is simply the **number of independent assurances present**:

   | Level | Assurances | Meaning | Examples |
   |---|---|---|---|
   | `VERY_LOW` | 0 | untrusted channel, no POL (today's `UNVERIFIED`) | e-mail, LinkedIn, website, business card |
   | `LOW` | 1 | *either* a trusted channel *or* POL, not both | Signal message from a previously in-person-verified contact (trusted channel, no live POL); **or** a generic video call where she shows her QR (live POL, untrusted channel) |
   | `HIGH` | 2 | trusted channel *and* POL | Signal **video call** with a verified safety number, showing her QR |
   | `VERY_HIGH` | in-person | physical co-presence (today's `VERIFIED`) | in-person QR scan |

   User-applicable rule: *"count your independent assurances — trusted channel? proof of life? — that's your level (0/1/2), or 3 if you were physically there."* Design notes settled during the spec walk:
   - **Levels are user-asserted context labels, not cryptographic facts.** The app cannot distinguish an e-mailed key from a Signal-relayed key from a video-shown key; even an in-person QR "scan" can't be cryptographically proven (a QR displayed on a video screen scans identically). The cryptographic fact is only *"this key was pinned"*; the level is honest metadata about *how*. UI must let the user pick levels `VERY_LOW`–`HIGH`; `VERY_HIGH` can be defaulted from the in-person scan flow.
   - **The "trusted channel" axis is kept binary** (trusted/untrusted) for usability; the user judges which side a given Threema-green/Signal-verified contact falls on. Grading it further re-explodes the lattice.
   - **The QR/link payload does not change** — verification level is assigned by the *receiving* device from the context in which it obtained the key, never asserted by the sender on the wire.
   - **Migration is clean:** old `UNVERIFIED` → `VERY_LOW`, old `VERIFIED` → `VERY_HIGH`; `LOW`/`HIGH` are net-new middle rungs, so no stored contact is mis-ranked.

   Work items:
   - **Spec**: rewrite the "Contact Verification" section (and the "Ready/Not added" + "Contacts Management" references to verification level) from two-level to this four-level model.
   - **deposplit.com `hexagons/phon`**: expand the `VerificationLevel` value object 2→4, kept ordinal/comparable. *(The `hexagons/relay` backend is untouched — it never stores contacts or verification levels.)*
   - **Android** (`:hexagon` + `:app`): expand the enum, contact record, add-contact level picker + guidance text; on-device data migration for stored contacts.
   - **iOS** (`hexagon` + app): same.
   - **Identity recovery** (spec item under "Identity Recovery"): approver weighting now references four levels — actual rule still TBD (to be walked separately).
7. **Holder-decrypts-at-pickup share-crypto redesign** (supersedes the encrypt-to-recipient *blind-courier* model). Decided during the spec walk. Share encryption's *only* job is keeping the **relay** blind: the relay is the chokepoint that transiently sees all `n` shares of a secret (grouped by `secret_id`), and SSS gives **no** protection to an all-`n` observer — whereas a single holder's `< k` share is already information-theoretically empty. So the encryption is moved to where it earns its keep:
   - **Deposit** (Alice→relay): encrypt each share to the holder's *current* X25519 pubkey (as today).
   - **Pickup** (Bob approves): Bob **decrypts** with his X25519 private key + Alice's X25519 public key (from his contact record for Alice) and stores the **plaintext share** locally. Plaintext at rest is information-theoretically harmless (one share reveals nothing about the secret); it still sits in app-private storage under OS file-based encryption. Relay row cleared as before.
   - **Retrieve** (Alice requests, Bob responds): Bob **re-encrypts** the stored plaintext to the *current* sender's X25519 pubkey (looked up live from his contact record for Alice) and returns that; Alice decrypts with her X25519 private key + Bob's X25519 public key. `reconstruct()` collects `k` approved responses, decrypts each, `combine`s.

   Why this shape:
   - **Relay stays blind at every phase** (ciphertext at deposit, ciphertext at retrieve). The **deposplit.com relay is unchanged** — it still stores/forwards opaque bytes, and `ShareRequestsService`'s server-side `senderSignature`/`recipientSignature` checks still verify over whatever bytes each row carries. This is a **client-only** change (Android + iOS `hexagon`/app); the Play backend and DB schema are untouched.
   - **No stale key pinning.** You only ever encrypt to a party who is present and live, using their current key — so the previously-considered "pin the deposit-time X25519 pubkey" fix is unnecessary and dropped.
   - **Survives holder key rotation** (the holder isn't the decryptor at retrieve; they hold plaintext) **and sender key rotation/loss** (the holder re-encrypts to whoever the sender is *now*). This is what makes social recovery *cryptographically possible at all* — the old blind-courier model silently could not reconstruct after the sender lost her key, because the ciphertext was bound to the lost key.

   Data-model changes (same redesign, affirmed in the walk):
   - `HeldShare`: `ciphertext` → `plaintextShare`; `senderKey` (Ed25519) → `contactId` (stable local UUID that survives the sender's key change). Optional denormalized `pseudonym` snapshot so a share from a since-deleted contact still renders. Rationale: `HeldShare` carries no cryptographic dependency on the sender's key (the holder is a courier for the decrypted share), so `contactId` fully decouples it.
   - `ShareMetadata` (sender side): `recipientKey` (Ed25519) → `contactId`. Linkage now survives the holder's key change; no pinned pubkey needed since the holder re-encrypts fresh at retrieve.
   - **Precondition** (shared with the key-change/recovery design): rotation/recovery must **update the existing contact record in place, preserving its `contactId`** — never create a *new* contact for the rotated identity, or the `contactId` anchor orphans held/distributed shares.

   This redesign removes the *cryptographic* blocker to recovery; the *metadata reconstitution* half is now designed in **item 8**.

   **No migrations** — Deposplit is pre-launch; test relays and test devices will be reset to a clean slate.
8. **Identity recovery — holder-driven metadata reconstitution (pure-social).** Resolves the "Identity Recovery" section's `k`-of-`n`-vs-single-approver TBD *and* the metadata half left open by item 7. Decided during the spec walk.

   **Framing that resolves the TBD** — two acts were conflated:
   - *Reconstruction after key loss* structurally needs `k` holders to accept the new identity and return their shares; one holder supplies one share, so "a single verified approver suffices" is impossible here — it's `k`-of-`n` **by construction**, not a policy knob.
   - *Propagating a key change to non-holder contacts* (people who have Alice in their address book but hold no share of the secret) is a lighter act where a single verified vouch may suffice. The old spec's "single verified approver" belongs *here*, not to reconstruction.

   **Why pure-social (no recovery key):** a recovery key is itself a secret Alice must reliably self-custody — the exact problem Deposplit exists to solve. If she could safely keep a recovery key she could safely keep the original secret and wouldn't need `k`-of-`n` splitting. So social recovery is the only option *consistent with the app's premise*, not a usability compromise.

   **The metadata problem:** on device loss new-Alice loses all local state — not just `ShareMetadata` but her entire contact list (public keys, pseudonyms, verification levels, relay overrides). The relay is no help: rows are keyed by old-Alice's Ed25519 and she can't authenticate as the lost identity. But after item 7 the holders collectively hold everything — each `HeldShare` carries the plaintext share + `secretId` + `label`. So recovery is **reconstitution from holders**, never a relay lookup.
   - *Who are my holders?* Not derivable from the system — holders don't know each other (deliberate; raises the collusion bar). Source of truth = Alice's memory (+ optional catalog backup).
   - *What did I deposit?* Each re-linked holder *is* the catalog for the shares it holds.

   **Mechanism (per holder):**
   1. Alice reaches a remembered holder out-of-band (ideally in person → `VERY_HIGH`) and they re-exchange QR: new-Alice gets the holder's current keys+relay; the holder gets new-Alice's new key.
   2. The **holder's app manually links** the re-presented identity to the *existing* contact ("this new key is my old contact Alice — key change"), updating that record **in place, preserving `contactId`** (per item 7) — which re-associates the held shares.
   3. The holder's app **pushes a metadata-only return** to new-Alice via the relay (new lightweight message type, **no ciphertext**): `{secretId, label, secretCreatedAt, holder-identity, k, n}` per share held. This rebuilds new-Alice's `ShareMetadata`.

   **Recovery returns metadata only — never shares.** Returning shares would create a *mass-reconstruction moment*: every secret decrypted onto one fresh device at once — a fat single-point-in-time target. Metadata-only recovery restores just the *ability* to reconstruct; each secret is later assembled on demand, one at a time, via the normal retrieve flow, inheriting normal on-demand risk instead of concentrating it.

   **Settled details:**
   - **Retrieve keyed on `secretId`** (= `secret_id` in `share_requests`; one UUID generated per `deposit()`, shared across all `n` of a secret's rows), not the transient pickUp relay-row id — `secretId` survives device loss and uniquely locates a holder's share since a holder holds at most one share per `(secretId, sender)`.
   - **Embed `k` and `n` in the pick_up payload** so holders report thresholds at recovery (cross-holder consistency check as a bonus). Cryptographically harmless — SSS never relied on hiding `k`/`n`. Demotes the catalog backup to pure convenience: re-linking any one holder of a secret already tells Alice `k`/`n`, hence how many more holders to find.
   - **Never share co-holder identities** — holders report `k`/`n` but stay ignorant of each other.
   - **Metadata transport = relay-mediated holder push** (works async / for remote re-links / scales; leaks nothing new since `secretId`/`label`/keys are already cleartext on relay rows). Out-of-band-at-re-link (device-to-device QR) stays as a purist alternative.
   - **Optional catalog backup:** a self-managed export of the *non-secret* catalog — contact public keys, pseudonyms, verification levels, `ShareMetadata` — eases "who are my holders" without weakening anything (none of it is a share or a private key). Backing up *private keys* is the opposite extreme (trivial recovery, but reintroduces a secret to guard + a platform dependency) and is out of scope for the trust-minimizing default.
   - **Post-recovery:** Alice re-splits under her new identity to restore a clean distribution (ties to secret lifecycle, item 11).

   Work items: new metadata-only recovery message type (relay + Android + iOS); holder-side "link to existing contact / key-change" UI; `k`/`n` in the pick_up payload + `PayloadCanonical` + `HeldShare`/`ShareMetadata`; `secretId`-keyed retrieve; optional catalog export/import (Android + iOS). **No migrations** (pre-launch, clean-slate reset).
9. **Holder-key-change handling + share-redundancy monitoring.** Post-item-7 a holder's key change is no longer a "share lost" event — it splits in two, each needing a different mechanism.
   - **Proactive rotation (holder keeps data):** only Alice's *routing pointer* to the holder goes stale (the plaintext share is safe). The holder still has the old key, so their app pushes a **signed `rotate(K_old → K_new)`** to contacts via the relay; Alice auto-verifies against the trusted old key and updates the contact record **in place, preserving `contactId`**. Fully automatic.
   - **Device loss (holder loses data):** the held plaintext shares are **gone**, and the holder's new device *cannot notify Alice* (it has no record it ever held them). Genuine redundancy loss, detectable only by Alice actively checking.

   **Health-check / redundancy monitoring — the authoritative mechanism.** Alice's app periodically pings each holder — *"still alive, still holding {secretIds}?"* — the holder acks (signed) the subset it still holds; Alice tracks **n_live vs k** per secret and surfaces per-secret health. Catches lost holders, missed/un-pushed rotations, and silent recipient-initiated deletions. Polling-based (fits #5).

   **Relay-tombstone fast-path — cheap complement, never authoritative.** Recipient-initiated deletion additionally flips the holder's pick_up row to an explicit **"withdrawn by recipient"** state, which Alice picks up via her existing `syncDistributed()` poll — faster notice than the next health-check cycle, at near-zero cost. But the relay is a **mailbox, not a store — it may delete any row at any time** — so this is doubly best-effort (needs the holder's device alive *and* the relay to still hold the row when Alice polls) and must never be relied on:
     - **Row *absence* is never a signal** (could be relay GC, not withdrawal) — `syncDistributed()` keeps its "upsert, never delete" rule.
     - Only an **explicitly observed "withdrawn" tombstone** (if caught before GC) or a **health-check ack / no-ack** counts. The health-check is ground truth; the tombstone is a lossy hint; Alice's local `ShareMetadata` (corrected by health-checks) is her source of truth — never the relay. Tombstone writes are fire-and-forget (the blind relay can't confirm delivery).

   **Repair requires reconstruction (the item-11 hand-off).** Alice can't cheaply "top up" a lost holder — SSS shares come from a specific polynomial, and she doesn't retain the secret/polynomial (retaining it would defeat splitting). So restoring a lost share = **reconstruct (gather k) → re-split to a fresh holder set**. The whole value of health-monitoring is catching a loss **while still ≥ k**, so she can reconstruct-and-re-split *before* a second loss makes the secret permanently unrecoverable.

   **Policy shift:** recipient-initiated deletion moves from "purely local, no message" to "unilateral, but best-effort notifies Alice" — the holder keeps full autonomy (no approval) but can't vanish *silently*.

   Work items: signed rotation-push message type; health-check request/ack message type + n_live/k per-secret health UI; "withdrawn by recipient" row state + tombstone-on-delete + `syncDistributed()` handling; reconstruct-and-re-split repair flow (shared with item 11). Relay + Android + iOS. **No migrations** (pre-launch).
10. **Malicious key substitution + stolen-key revocation.** Closes the key-change thread. Decided during the spec walk.

    **Load-bearing reframe: a stolen key is not a stolen secret.** Even holding Alice's Ed25519 + X25519 private keys, an attacker must still get **`k` holders to approve retrieval**, and the consent model requires each holder to verify *out-of-band* that Alice genuinely asked. So the k-of-n consent layer — not the keypair — is the real backstop; the attacker must defeat `k` humans. This is why retrieval consent exists (not just deletion consent).

    **The one rule for accepting a key change** (unifies items 8 and 9):
    - **Auto-accept only on a valid `K_old` signature** (the item-9 rotation push). Cryptographically sound against outsiders (an attacker without `K_old` can't forge it).
    - On auto-accept, **downgrade the verification level: `level(K_new) = min(level(K_old), LOW)`.** A signed rotation is, in item-6 terms, a *trusted-channel-without-POL* event (1 assurance = LOW): continuity of key control, **not** a fresh personhood check — so it can never carry VERY_HIGH/HIGH forward, and a rotation from an already-VERY_LOW key stays VERY_LOW (continuity from an unverified anchor adds no personhood assurance). Restored to a higher level only by fresh human re-verification (in-person re-scan → VERY_HIGH).
    - Any change **not** backed by `K_old` — i.e. recovery (item 8, old key lost) — requires **human re-verification** and must be surfaced as a high-stakes trust decision, never a silent accept; its level is set fresh from the re-verification context (no carry-forward).

    Unifying principle: *a contact's verification level always reflects the most recent **personhood** assurance about its **current** key; a cryptographic rotation isn't one, so it can never exceed LOW.*

    **Revocation is socially anchored — a compromised key can't revoke itself.** If the attacker holds `K_stolen`, Alice and the attacker are cryptographically indistinguishable (both can sign), so `K_stolen` can't adjudicate competing "trust my new key" claims. Consequences:
    - **No cryptographic revocation.** A relay blocklist can't work (blind relay; a block signed by `K_stolen` is ambiguous), and an unforgeable revocation would need a pre-provisioned recovery key — the self-custody chicken-and-egg rejected in item 8. Rejecting it there commits us to social revocation here (consistent).
    - **Mechanism — a local "compromised/revoked" key flag.** Set via out-of-band notice, it **disables auto-accept** of any rotation signed by that key (forcing fresh human re-verification), so an attacker's rotation push signed by the stolen key is ignored. Any two conflicting "current keys" for one identity → a **conflict surfaced for manual resolution, never auto-resolved.** The tiebreaker is the k-of-n + verification layer (in-person beats a remote attacker).

    **Retrieve-approval hardening.** The attack signature is *key change → quick retrieval*, so the holder's approve-retrieve UI surfaces **"this requester's key changed N days ago"** and urges a fresh out-of-band check. This composes with the downgrade above: a rotated (hence ≤ LOW) key requesting a retrieve automatically lands in the tightest scrutiny.

    **Honest scope limit.** Revocation limits *future* damage only. If the attacker already defeated `k` holders and reconstructed during the exposure window, that secret is **burned** — Deposplit can't un-leak it. Post-compromise, Alice must: run item-8 recovery/rotation + flag the old key compromised; reconstruct + re-split affected secrets (item-9 repair); and **change the underlying secret itself** (rotate the BitLocker key, etc.) for anything that may have leaked. Existing hygiene (biometric-gated reconstruction, Keystore/Secure Enclave private keys) keeps *device* theft from becoming *key* theft, buying time for the social layer.

    Work items: `K_old`-signed rotation with `min(level, LOW)` downgrade (Android + iOS; ties to item 9's rotation push); "compromised/revoked" key flag + conflict-resolution UI + auto-accept suppression; "key changed N days ago" indicator + fresh-OOB nudge on the approve-retrieve screen. **No migrations** (pre-launch).
11. **Secret lifecycle — bounds, sender-side `Secret` record, discard, and the composed re-split.** Decided during the spec walk. This is the anchor items 8 and 9 lean on when they say "reconstruct + re-split," now pinned down. It replaces the sender side's *implicit* model (per-share `ShareMetadata` rows grouped by `secretId` at query time, no per-secret entity, a hardcoded reconstruct threshold) with an explicit one.

    **Bounds — the hard invariant is `2 ≤ k ≤ n ≤ 255`.** `k ≥ 2` is a **hard floor** (`k = 1` isn't secret-*sharing* — any single holder reconstructs alone, defeating the "< k reveals nothing" premise); hence `n ≥ 2`. `n ≤ 255` is field-imposed (x-coordinates live in GF(2⁸), x=0 reserved for the secret). The domain (hexagon `split`/`deposit`) enforces this and throws on violation. **No hard UI ceiling on `n`** — a board-of-directors secret legitimately wants large `n` (with a correspondingly large `k`). Instead, **three soft, non-blocking warning axes** (dismissible "Are you sure?" confirmations; exact thresholds/wording are UI tuning, not load-bearing spec):
    - **Operational burden** (magnitude, *not* a security warning) — large `n` (e.g. `n ≥ 10`, `n ≥ 20`): you must exchange keys with all `n`, approve all `n` pickups, and health-check all `n` (item 9). Copy should make clear this is *work*, not *danger*.
    - **Confidentiality tail** — `k` low relative to `n` (e.g. `k < n/2`, `k < n/3`): a small clique reconstructs behind Alice's back.
    - **Availability tail** — redundancy margin `n − k` small (e.g. `k = n` → *any single lost holder = secret gone forever*; `k = n − 1` → tolerates exactly one loss). This is the redundancy-erosion failure item 9 exists to catch, so warning at split time is the cheapest prevention. Without it the UI would let someone pick `10-of-10` silently, then item 9 alarms the moment a phone dies.

    The two ratio tails are symmetric around a healthy middle: SSS forces a trade-off at fixed `n` — confidentiality rises with `k`, availability rises with `n − k`; you can't push both up without raising `n`.

    **Sender-side `Secret` aggregate.** Today the sender has *nowhere* to store `k` — only per-share `ShareMetadata` rows — which is why `reconstruct()` uses a hardcoded `check(approved.size >= 2)` (literal `2`, not the real `k` — a genuine bug for any `k ≠ 2`). Introduce a per-secret record keyed by `secretId`: **`Secret(secretId, label, k, n, secretCreatedAt, state)`**. `ShareMetadata` is **normalized to reference it** — drops the duplicated `label`/`secretCreatedAt` (single source of truth; the UI has the `Secret` loaded when rendering its shares), keeps what's genuinely per-share: `secretId` (→ `Secret`), `contactId` (the holder), and item 9's per-share health status. `reconstruct(secretId)` then reads `k` from the `Secret` and enforces `approved.size >= k` — the literal `2` becomes correct by construction. Consistent with item 8's decision to also put `k`/`n` in the pick_up payload (sender persists locally, wire carries to holders, holders store in `HeldShare` — same two numbers everywhere).

    **No named "re-split" or "rotate-value" flow — one new primitive, freely composed.** "Value rotation" is *not* a concept the app needs to model: when Alice has a new BitLocker key she just **`deposit(newValue)` + `discardSecret(oldSecretId)`** — Bob can't and needn't distinguish "same computer, rotated key" from "migrated to a new computer, retired the old." Both of the flows considered dissolve into the same primitives, differing only in *whether Alice already holds the value*:
    - **Has the value** (rotation / genuinely new secret): `deposit(value)` + `discardSecret(old)`.
    - **Lacks the value** (item 9 top-up — *same* value, restore redundancy): `reconstruct(old)` to get it back in hand → then `deposit(value)` + `discardSecret(old)`.

    So the lifecycle adds exactly **one new primitive**: **`discardSecret(secretId)`** — a fan-out **sender-initiated `delete`** to every holder of that `secretId` (composed from the existing per-share `delete` request type; each holder must **approve** per the symmetric consent model, so it is *request*, not *compel*, and best-effort — a dark holder never approves, and the relay may GC the request row before they poll). Every `deposit()` already mints a fresh `secretId` (= a fresh polynomial), so the new distribution is automatically distinguishable from the old — **necessary**, because shares from two different polynomials for the same value are *not* interchangeable (`combine()` needs `k` shares from the *same* polynomial). The **`supersedes` link is dropped** (YAGNI): with no rotation concept, nothing knows the new secret "replaces" the old, and there's no natural moment to populate it.

    **`reconstruct()` becomes a pure read (behavior change from current code).** Today `reconstruct()` tears down Alice's local `ShareMetadata` + relay rows after combining — the "Alice forgets, holders remember" asymmetry (holders' `HeldShare`s persist because they're only asked to delete via an approved `delete`). Since reconstruct is now a *step* toward re-split, that auto-teardown is premature. Cleanly separated they are **GET vs DELETE**: `reconstruct` reads the value and changes nothing; `discardSecret` is the *only* teardown path. This also fixes the orphaned-holder problem (Alice no longer silently forgets a distribution she hasn't discarded). **Code TODO: remove the auto-delete from `reconstruct()`.**

    **Exposure honesty.** Re-splitting the *same* value restores **availability, not confidentiality** — old holders' shares still reconstruct the still-live value until they approve their discards. When Alice rotates the *value*, the old value is **dead**, so a lingering old share reconstructs a *retired* secret (real exposure reduction). The residual risk only bites for an unchangeable value (e.g. a seed phrase); there, best-effort `discardSecret` is the honest-but-imperfect mitigation. Same "burned secret" honesty as item 10.

    **Two-state lifecycle, no tombstone.** `state ∈ {ACTIVE, DISCARDING}`. The one fact not derivable from item 9's per-share health is *Alice's intent to discard* — needed so a dropping `n_live` reads as *expected teardown*, not *alarm*:

    | State | Meaning | Effect |
    |---|---|---|
    | `ACTIVE` | Distributed and live | Health-monitored (item 9) |
    | `DISCARDING` | `discardSecret` issued; deletes outstanding | Health alarms **suppressed** (dropping `n_live` is the goal); UI shows per-holder teardown progress |

    When all holders confirm deletion, or Alice **force-forgets locally** (a dark holder will never approve), the `Secret` record is **removed** — no persistent `DISCARDED` tombstone (pure UI nicety; the residual-share caveat is stated anyway). **No `DRAFT` state** — `deposit()` is atomic (split + distribute).

    **Health-alarm thresholds (feeds item 9).** Reconstruction needs `k` shares, so the alarm fires at **`n_live <= k`** — firing only at `n_live < k` is *too late* (already unrecoverable). `n_live == k` is the **last actionable moment** (can still gather `k` → reconstruct → re-split → restore margin); `n_live < k` is a post-mortem (only remedy left is changing the underlying secret, item 10):

    | Condition | Level | Call to action |
    |---|---|---|
    | `n_live >= k + 2` | healthy | — |
    | `n_live == k + 1` | caution | margin of one — re-split soon |
    | `n_live == k` | **critical** | reconstruct + re-split **now** — last recoverable moment |
    | `n_live < k` | lost | unrecoverable — rotate the underlying secret (item 10) |
    | *(while `state == DISCARDING`)* | suppressed | teardown is intentional |

    **Freemium-cap interaction (resolves C4).** The free "up to `n` deposited secrets" cap counts **`ACTIVE` secrets only** — *not* lifetime deposits (which would punish good hygiene, i.e. retiring/rotating secrets, and is unenforceable in spirit anyway). `discardSecret` flips a secret to `DISCARDING` **immediately** (before holders finish confirming), so it leaves the `ACTIVE` count at once — which means a **re-split never double-counts**: the old secret has already left `ACTIVE` by the time the re-split's new `deposit` lands, so a free user restoring redundancy on their `n`-th secret is never blocked by their own in-flight teardown.

    Work items: `Secret` aggregate (`k`/`n`/`state`) + `ShareMetadata` normalized to reference it; `reconstruct()` enforces `>= k` (remove hardcoded `2`) **and** becomes non-destructive; `discardSecret(secretId)` fan-out primitive + "discard secret" UI; `ACTIVE`/`DISCARDING` state + health-alarm suppression; split-time three-axis warnings; graduated `n_live` health alarm (feeds item 9); free-cap counts `ACTIVE` only. Hexagon (Android + iOS; relay untouched — it never stores `Secret`/`ShareMetadata`). **No migrations** (pre-launch, clean-slate reset).

## Build & Test Commands

### deposplit.com/ (Scala + Play + sbt)

```bash
# from deposplit.com/
sbt run          # start the Play dev server (auto-reloads on file change)
sbt run -Dconfig.file=conf/localhost.conf # with the dev config
sbt test         # run all tests (hexagon + root)
sbt compile      # compile without running
sbt relay/test   # test the relay (domain) hexagon subproject only
sbt dist         # produce a production distribution zip
```

### Android/ (Kotlin 2.4, AGP 9.x, JVM 21 bytecode, runs on Java 25+)

```bash
# from Android/
./gradlew assembleDebug  # build debug APK
./gradlew test           # JVM unit tests (no device needed)
./gradlew connectedAndroidTest   # instrumented tests (requires device or emulator)
./gradlew test --tests "com.deposplit.shamir.ShamirTest"  # single test class
```

### iOS/ (Swift Package Manager — Swift 6.2.3)

`Package.swift` lives in `iOS/hexagon/`, not at the `iOS/` repo root — the root holds `Deposplit.xcodeproj` (the app target, built/tested via `xcodebuild`). Commands below run from `iOS/hexagon/`, or from `iOS/` with `--package-path hexagon`:

```bash
# from iOS/hexagon/
swift build              # compile
swift test               # run all tests
swift test --filter ShamirSecretSharingTests  # single test target
```

> **Note:** Swift on Windows writes to the Windows Console API, so its output is not captured by Git Bash. Run `swift test` from VS Code (Swift extension) or a native Windows terminal (PowerShell / Windows Terminal) instead.

## Continuous Integration

Each of the three repos has its own `.github/workflows/test.yml` and `.github/dependabot.yml`:

| Repo | Workflow job | Runs | Runner |
|---|---|---|---|
| `deposplit.com/` | `sbt` | `sbt test` | `ubuntu-latest` |
| `Android/` | `gradle` | `./gradlew test` (`:hexagon` + `:app` unit tests) | `ubuntu-latest` |
| `iOS/` | `swift` | `swift test` (`working-directory: hexagon`) | `macos-latest` |

All three trigger on push to any branch and on pull requests targeting `main`, run with `permissions: contents: read`, cancel superseded runs via a `concurrency` group, and pin third-party actions to a commit SHA (with a version comment) rather than a mutable tag. None of the test suites needs external services: deposplit.com's `sbt test` runs against an in-memory H2 database (`conf/test.conf`), and the Android/iOS jobs are JVM/Swift unit tests only — no emulator, simulator, or device required. The iOS job does not build or test the `Deposplit.xcodeproj` app target, since that needs a simulator; only the `hexagon` Swift package is covered.

`dependabot.yml` runs weekly and covers:
- All three repos: `github-actions` (the pinned action SHAs above).
- `Android/`: also `gradle`, scoped to the repo root — covers `build.gradle.kts` in the root, `app`, and `hexagon` modules, and the `gradle/libs.versions.toml` version catalog.
- `iOS/`: also `swift`, scoped to `/hexagon` where `Package.swift` lives. Currently a no-op, since `hexagon/Package.swift` declares no external dependencies yet.
- `deposplit.com/`: sbt is not a Dependabot-supported ecosystem, so the Scala library and webjar (`bootstrap`, `popperjs__core`) dependencies declared in `build.sbt` are not covered.
