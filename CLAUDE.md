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

6. **Unified single-table relay:** The two-table schema (`shares` + `share_requests`) was collapsed into a single `share_requests` table with three transaction types: `deposit`, `retrieval`, and `removal`. All three follow the same symmetric consent model — Alice requests something of Bob; Bob can approve or deny. Every row is self-describing with embedded `sender_key` and `recipient_key`, making the relay fully stateless. The DB schema now has one table.

## Architecture Decisions

### Communication Layer: Custom Web App/Service

The transport layer is a **custom deposplit.com REST API** with end-to-end encryption. No native crypto libraries are used on any platform — everything is implemented using each platform's standard crypto stack (BouncyCastle on Android/JVM, Swift Crypto / CryptoKit on iOS).

Key design decisions:
- **User identity is two keypairs.** At first launch the device generates an X25519 keypair (share encryption) and an Ed25519 keypair (API authentication). The user picks a pseudonym (display name only, stored locally on the device — never sent to the Web app/service). No server registration is required: the keypair IS the identity. Contacts exchange both public keys out-of-band — ideally in person via QR code, or via a trusted third-party channel (Signal, Threema, email).
- **Server is an opaque relay.** The Web app/service stores and forwards ciphertext only. It never participates in key agreement and cannot decrypt share content regardless of a breach. Holders decrypt each share to plaintext at pickup and store it locally (see "Transport Encryption" below — item 7 of "What is next"), so reconstructing the original secret requires compromising *k* holders' devices or defeating *k* holders' retrieve-consent — not the relay, and not any private key the relay ever sees.
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

Holder-decrypts-at-pickup (implemented — see "What is next" item 7): each leg of a share's journey is encrypted using whichever pair of *current* keys is live at that moment, never a key pinned back at deposit time. This is what makes k-of-n social recovery cryptographically possible — no participant's ability to decrypt is bound to a keypair from the past.

1. **Deposit** (Alice → relay): Alice encrypts the share to the holder's *current* X25519 public key. The relay stores and forwards this ciphertext opaquely.
2. **Pickup** (holder approves): the holder decrypts with their own X25519 private key + Alice's X25519 public key (from their local contact record for Alice), and stores the resulting **plaintext** share locally — the ciphertext itself is discarded. This is safe: a single holder's `< k` share is information-theoretically empty on its own, and the plaintext still sits behind the OS's file-based encryption. The relay row is cleared as soon as the holder has it.
3. **Retrieval** (Alice requests, holder responds): the holder re-encrypts the stored plaintext to the *current* sender's X25519 public key (looked up live, not the key Alice had at deposit time) and returns that ciphertext; Alice decrypts with her own private key + the holder's current public key.

Both the deposit and retrieval legs use the same standard static-static DH box construction:

1. **Key agreement**: X25519(my_private_key, their_public_key) → 32-byte shared secret
2. **Key derivation**: HKDF-SHA-256(ikm=shared_secret, salt=nonce, info=`"deposplit-share"`) → 32-byte symmetric key
3. **Encryption**: ChaCha20-Poly1305(key, nonce, plaintext) → ciphertext + 16-byte tag
4. **Wire format**: `nonce(12 bytes) || ciphertext+tag`

The Web app/service stores and forwards ciphertext only, at both deposit and retrieve — it is never in a position to decrypt. A full Web app/service breach yields nothing without also compromising *k* holders' devices (where the plaintext shares live) or defeating *k* holders' retrieve-consent.

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

There are four request types exchanged via the deposplit.com Web app/service API. The first three follow the same symmetric consent model — Alice requests something of Bob; Bob can approve or deny. The fourth, added by item 8, is a holder-initiated push with no consent phase at all:

| Type | Direction | Payload | Purpose |
|---|---|---|---|
| `deposit` | Sender → recipient | `secret_id`, `label`, `secret_created_at`, `k`, `n`, encrypted share bytes | **Deposit** a share; Bob approves to receive it |
| `retrieval` | Sender → recipient → sender | Request: references `secretId` (item 8; previously the transient Deposit relay-row id). Response: share bytes from Bob's local storage | **Retrieve** a specific share |
| `removal` | Sender → recipient | Request: references Deposit ID. Response: ack | **Remove** a share (sender-initiated, requires Bob's approval) |
| `inventory` | Holder → owner | `secret_id`, `label`, `secret_created_at`, `k`, `n` — no ciphertext | Reports what a holder still guards for a secret whose owner lost her local state (item 8); self-approves at creation, no PATCH phase |

> ⚠ **Still to come — see "What is next" items 9–12** (item 8 is now implemented, see above). Item 9's **signed rotation push** (`key_rotations`, a dedicated table — not a fifth `ShareTransactionType`) and its **"withdrawn by recipient"** row state are implemented across the relay and all three clients (2026-08-18) — every client's receive-side auto-accept already applies item 10's `min(level, LOW)` downgrade; a **holder-initiated custodial-heartbeat push** (item 9 reshaped by item 12 — replaces the originally-sketched pull "health-check request/ack") and the rest of item 10 (the compromised/revoked key flag, conflict-resolution UI, "key changed N days ago" retrieve-approval indicator) remain unbuilt everywhere.

**Recipient-initiated deletion** is unilateral (no approval needed). The recipient can delete individual shares or all shares from a given sender at any time. *(Revised — see "What is next" item 9: it stays unilateral but is no longer purely silent; the holder's app additionally writes a best-effort "withdrawn by recipient" tombstone so the sender isn't blindsided by silent redundancy erosion.)*

**The Web app/service is a pure relay — ciphertext is ephemeral:** the relay's role — store/forward opaque ciphertext, blind at every phase — is unchanged by item 7's holder-decrypts-at-pickup redesign; only *who decrypts when* changed (see "Transport Encryption" above).

Deposit flow:
- **Request sub-phase** (sender → relay): Alice opens a Deposit request with the share encrypted to the holder's current X25519 public key; the relay stores it.
- **Response sub-phase** (relay → recipient): Bob approves the Deposit; the relay delivers the ciphertext once and clears it from the relay row. Bob decrypts it immediately with his X25519 private key + Alice's X25519 public key and stores the resulting **plaintext** share on his device — the ciphertext itself is not retained.

Retrieval flow:
- **Request sub-phase** (sender → relay): Alice opens a Retrieval request referencing the Deposit ID; the relay stores it as pending.
- **Response sub-phase** (recipient → relay → sender): Bob approves by re-encrypting his stored plaintext share to Alice's *current* X25519 public key and sending that; the relay stores it temporarily. Alice polls, fetches the ciphertext, decrypts it with her own X25519 private key + Bob's current X25519 public key, then deletes the Deposit row (which cascade-deletes the Retrieval/Removal rows).

Every row is self-describing — it embeds both `sender_key` and `recipient_key`. The relay never needs to look up any other row to authorize a request.

Consequence: a relay database wipe after all recipients have picked up their shares does not destroy the secret — the shares (now held as plaintext) live on the recipients' devices. The relay is a mailbox, not a store.

**Consent model:**
- *Retrieval* — the recipient must approve. This allows out-of-band verification (e.g. a phone call) that the sender genuinely requested reconstruction and is not an attacker who stole their device.
- *Sender-initiated deletion* — the recipient must approve. The sender cannot force deletion.
- *Recipient-initiated deletion* — unilateral, no approval needed.

**Notification delivery — polling only (v0.1):**
There is no WebSocket or push notification channel. Clients poll for pending events on app open and periodically while foregrounded (`GET /share-requests?role=recipient&state=pending`, etc.). Event frequency is low enough that polling is sufficient. Background push via FCM/APNs is deferred — it would introduce a Google/Apple dependency and some metadata leakage, which conflicts with Deposplit's trust-minimising philosophy. *(Extended by "What is next" item 12: holders additionally emit an opportunistic, foreground-only **custodial-heartbeat push** on this same poll cycle — no background wake — so senders can monitor share redundancy; cadence and staleness rules are pinned there.)*

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

Each contact record stores: a stable local `contactId` (item 7 — survives key changes, anchors `HeldShare`/`ShareMetadata`), Ed25519 public key, X25519 public key, pseudonym, verification level, date verified, and (BYOR) an optional per-contact `relayBaseUrl` override. All stored locally on the device. Item 8 added `ContactManagement.updateContact(contactId, newKeys?, newLevel?)`, a contact-update-in-place primitive used both for benign key rotation/recovery relinking and, on Android/iOS, a "Relink (Key Changed)" UI action. *(Planned addition — see "What is next" item 10: a per-key compromised/revoked flag.)*

Adding a contact is the natural moment to prompt for in-person QR verification.

### Contact Verification

> ⚠ **Item 10 will further extend this** with a per-key *compromised/revoked* flag and a `min(level, LOW)` downgrade applied to an auto-accepted rotation — not yet implemented.

Deposplit uses a **four-level ordinal verification model** (superseded the original two-level, Threema-inspired scheme — see "What is next" item 6), derived from a trusted-channel × proof-of-life lattice. The two incomparable middle cells of that lattice (trusted-channel-but-no-proof-of-life vs. proof-of-life-but-untrusted-channel) are deliberately merged into one rung, so the order is simply the count of independent assurances present:

| Level | Assurances | How achieved | Meaning |
|---|---|---|---|
| **Very Low** | 0 | Contact added remotely with no live check (e-mail, LinkedIn, a business card) | "I believe this Deposplit account belongs to this person, but I haven't confirmed it" |
| **Low** | 1 | *Either* a trusted channel *or* live proof, not both (a Signal message from a previously in-person-verified contact; or a generic video call where they show their QR) | One independent assurance |
| **High** | 2 | Both a trusted channel *and* live proof (e.g. a Signal video call with a verified safety number, showing their QR) | Two independent assurances |
| **Very High** | in-person | QR code scanned in person | "I was physically with this person and confirmed their public key is theirs" |

Levels are **user-asserted context labels, not cryptographic facts** — the app cannot distinguish an e-mailed key from a Signal-relayed key from a video-shown key, so the UI lets the user pick a level rather than inferring one. Manual key entry only offers `Very Low`/`Low`/`High`: physical co-presence can't be asserted by typing a key in by hand, so `Very High` is reserved for the in-person QR scan flow, which defaults to it. The QR/link payload itself carries only public keys and pseudonym — verification level is never asserted by the sender on the wire, only assigned locally by the receiving device from the context in which it obtained the key. Verification level is stored per contact and is visible to the user when reviewing share holders or approving requests.

### Identity Recovery

> ⚠ **Superseded/detailed by "What is next" item 8 (holder-driven metadata reconstitution — implemented).** The prose below is the original sketch; the spec walk resolved its `k`-of-`n`-vs-single-approver TBD and specified the mechanism, now built — see item 8. Key corrections: recovery is *holder-driven metadata reconstitution*, not a relay-side "re-association request"; reconstruction is `k`-of-`n` *by construction*; and recovery returns *metadata only*, never shares. The "single verified approver" idea (line below) was **retired** by the C5 walk — non-holder key-change propagation uses no vouch, just a **contact-update-in-place** (`updateContact`, preserving `contactId`) with the level re-chosen fresh on any key change; see item 8's "Non-holder propagation — resolved (C5 walk)".

If Alice loses her phone and cannot recover her private key, she generates a new keypair on a new device and initiates a **re-association request**: "please map my new public key to my old one."

Recovery uses **social recovery (k-of-n)**: the same threshold k used when the secret was split must approve the re-association before it takes effect. Verification level (now the four-level model — see "Contact Verification") influences the trust calculus:
- Approval from a contact at a **higher** verification level carries stronger assurance than approval from one at a lower level
- A single approver at a sufficiently high level may be considered sufficient; the exact rule is TBD

Recipients who approve a re-association should be encouraged to verify Alice again in person (re-scan her new QR code) to restore the verified relationship.

## Development Status

### What is done

See [`CHANGELOG.md`](CHANGELOG.md) for the full implementation log.

### What is next

The items below capture *design rationale* — why each decision was made, and what it changes. For the *implementation checklist* (what's left, per platform, with progress state), see [`TODO.md`](TODO.md).

1. **iOS biometric unlock**: The Android app gates `reconstruct()` behind `BiometricPrompt`. The iOS `ShareDetailView` currently reconstructs immediately; it should gate via `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` from the `LocalAuthentication` framework.
2. **End-to-end testing**: Test Android ↔ iOS interop (Android deposits a share, iOS recipient approves the Deposit and later a Retrieval, Android reconstructs) against a live `sbt run` Web app/service. Now also needs to cover BYOR: two local `sbt run` instances on different ports, one contact configured with a `relayBaseUrl` override pointing at the second instance, verifying deposit/pickup/retrieval/removal correctly route through the override while a no-override contact still round-trips through the default.
3. ~~**Defense in depth — recipient-side signature verification**~~ — **done.** Every `ShareRequest` row now carries `senderSignature` (set at open) and `recipientSignature` (set at response), Ed25519 signatures over `PayloadCanonical`'s byte constructions — independent of, and in addition to, the per-call transport-auth signature. Recipients (and senders reading back responses) independently re-verify these against the counterparty's public key from the local contact record before acting; deposplit.com's own `ShareRequestsService` also verifies them server-side as defense-in-depth. Implemented and tested on the backend (`hexagons/relay`, 89 tests) and Android (`:hexagon`, 31 tests); implemented on iOS but **not yet compiled or test-run** — see `iOS/CLAUDE.md`'s "TODO for Claude on macOS" section.
4. ~~**BYOR — Bring Your Own Relay**~~ — **self-hosted-instance backend done**, Airtable/Google Sheets adapters still future work. `Contact.relayBaseUrl` (a per-contact override, `null` = device default) plus a `ShareRelayResolver` driven port let `ShareService`/`ShareManagement` route any operation through a contact's own relay instead of deposplit.com; fan-out methods (`syncInbox`, `listPendingRequests`, `syncDistributed`, `listSentRequests`) poll every distinct relay referenced across the contact list, deduped, each independently soft-failed so one unreachable relay doesn't blank out the others. The relay override is exchanged out-of-band via the QR payload (bumped to `v:2`, new `relay` field — the *displaying* device's own configured relay) or a manual text field on "add contact". Android and iOS each gained a runtime-configurable "default relay" setting (`RelaySettings` port, a Settings screen) replacing Android's old compile-time `BuildConfig.BASE_URL`/`local.properties` mechanism entirely. Remaining: Airtable/Google Sheets adapters (need a `relayKind` discriminator on `Contact` since those aren't REST-API-shaped like a deposplit.com instance); real multi-device BYOR interop testing (item 2).

   **Deferred pending adoption (decided Aug 2026).** The non-REST relay-kinds (Airtable, Google Sheets, …) remain a *wanted* direction but are **explicitly parked until the default deposplit.com relay has demonstrated real, sufficient user adoption** — no design refinement or `relayKind` work before then. Rationale: the feature only earns its considerable per-kind adapter + wire-shape complexity once there's evidence people are actually using Deposplit at all; refining it earlier is speculative surface area. This is a demand gate, not a rejection — revisit when default-relay usage justifies it.

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

   **Work items:** tracked in `TODO.md` (item 5).
6. ~~**Four-level contact verification model**~~ — **done.** Replaced the old two-level (`UNVERIFIED` / `VERIFIED`) scheme with a four-level ordinal one derived from a 2×2 lattice over two independent assurance axes — **trusted channel** (untrusted/trusted) × **proof of life (POL)** (sine/cum). The two incomparable middle cells of the lattice (trusted-channel-but-no-POL vs. POL-but-untrusted-channel) are deliberately **merged** into one rung, so the linear order is simply the **number of independent assurances present**:

   | Level | Assurances | Meaning | Examples |
   |---|---|---|---|
   | `VERY_LOW` | 0 | untrusted channel, no POL (the old scheme's `UNVERIFIED`) | e-mail, LinkedIn, website, business card |
   | `LOW` | 1 | *either* a trusted channel *or* POL, not both | Signal message from a previously in-person-verified contact (trusted channel, no live POL); **or** a generic video call where she shows her QR (live POL, untrusted channel) |
   | `HIGH` | 2 | trusted channel *and* POL | Signal **video call** with a verified safety number, showing her QR |
   | `VERY_HIGH` | in-person | physical co-presence (the old scheme's `VERIFIED`) | in-person QR scan |

   User-applicable rule: *"count your independent assurances — trusted channel? proof of life? — that's your level (0/1/2), or 3 if you were physically there."* Design notes settled during the spec walk:
   - **Levels are user-asserted context labels, not cryptographic facts.** The app cannot distinguish an e-mailed key from a Signal-relayed key from a video-shown key; even an in-person QR "scan" can't be cryptographically proven (a QR displayed on a video screen scans identically). The cryptographic fact is only *"this key was pinned"*; the level is honest metadata about *how*. UI must let the user pick levels `VERY_LOW`–`HIGH`; `VERY_HIGH` can be defaulted from the in-person scan flow.
   - **The "trusted channel" axis is kept binary** (trusted/untrusted) for usability; the user judges which side a given Threema-green/Signal-verified contact falls on. Grading it further re-explodes the lattice.
   - **The QR/link payload does not change** — verification level is assigned by the *receiving* device from the context in which it obtained the key, never asserted by the sender on the wire.
   - **No migration code** — Deposplit is pre-launch; the relay DB and all on-device contact stores reset clean rather than being upgraded in place. (Old `UNVERIFIED`/`VERIFIED` would map conceptually onto `VERY_LOW`/`VERY_HIGH`, but no decode-time compatibility shim was written for it.)

   **Implemented** across all three hexagons: `deposplit.com/hexagons/phon` (`VerificationLevel extends Ordered[VerificationLevel]`, ordinal `compare`), Android `:hexagon` (Kotlin enums are ordinal-`Comparable` for free), iOS `hexagon` (`Comparable` via a private `rank`). `ContactService.addManually`/`addFromQr` on Android and iOS take an explicit `verificationLevel` argument; the domain layer rejects a manually-entered `VERY_HIGH` (physical co-presence can't be asserted by typing a key in by hand). Android and iOS gained an add-contact verification-level picker (manual entry offers `VERY_LOW`/`LOW`/`HIGH` with guidance text; QR scan defaults to `VERY_HIGH`) and a color-coded level badge on the contacts/deposit-recipient lists. phon kept its existing default-by-flow assignment (no picker UI — its scope was intentionally narrower) and its `contactsTable` view now shows all four level names via new `conf/messages`/`conf/messages.de` keys.

   **Work items:** tracked in `TODO.md` (item 6).
7. ~~**Holder-decrypts-at-pickup share-crypto redesign**~~ — **done** (supersedes the encrypt-to-recipient *blind-courier* model). Decided during the spec walk. Share encryption's *only* job is keeping the **relay** blind: the relay is the chokepoint that transiently sees all `n` shares of a secret (grouped by `secret_id`), and SSS gives **no** protection to an all-`n` observer — whereas a single holder's `< k` share is already information-theoretically empty. So the encryption is moved to where it earns its keep:
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

   **Implemented** on iOS `hexagon` and Android `:hexagon`: `HeldShare.senderKey`/`ciphertext` → `contactId`/`senderPseudonym`/`plaintextShare`, `ShareMetadata.recipientKey` → `contactId`, and a new `ContactRepository.getById` on both platforms (and on `phon`'s, see below). `ShareService.syncInbox()` now decrypts at pickup and `respond()` re-encrypts fresh to the requester's current key at retrieve; `reconstruct()` needed no change at all — it already decrypted with the sender's own identity + the holder's current `xPublicKey`, which is exactly item 7's retrieve-side contract. The `phon` hexagon picked up the same field renames and pickup/retrieve crypto flow for cross-platform *consistency*, deliberately short of full *parity* — it skipped the denormalized `senderPseudonym` snapshot, since its held-shares view doesn't render a sender name at all yet.

   **No migrations** — Deposplit is pre-launch; test relays and test devices will be reset to a clean slate.
8. ~~**Identity recovery — holder-driven metadata reconstitution (pure-social).**~~ — **done.** Resolves the "Identity Recovery" section's `k`-of-`n`-vs-single-approver TBD *and* the metadata half left open by item 7. Decided during the spec walk.

   **Framing that resolves the TBD** — two acts were conflated:
   - *Reconstruction after key loss* structurally needs `k` holders to accept the new identity and return their shares; one holder supplies one share, so "a single verified approver suffices" is impossible here — it's `k`-of-`n` **by construction**, not a policy knob.
   - *Propagating a key change to non-holder contacts* (people who have Alice in their address book but hold no share of the secret) is a lighter act. The old spec's "single verified approver" belongs *here*, not to reconstruction — but it was **retired** in the C5 walk (see below), not formalized.

   **Non-holder propagation — resolved (C5 walk).** No transitive vouch / web-of-trust. The stakes are genuinely low: after item 7 a non-holder is typically someone *Alice holds a share for*, and on device loss that plaintext share is already gone regardless of key propagation (detected via item 9 health-check, repaired by reconstruct-and-re-split) — so propagating Alice's new key restores only the *future* ability to interact, with no live secret depending on it, and item 10's `k`-of-`n` consent backstop still guards any actual reconstruction. Therefore:
   - **Mechanism = contact-update-in-place, no relay involvement.** Alice tells Bob out-of-band "I have new keys — *update* my existing contact entry, don't delete-and-re-add." Bob's app runs the **same `updateContact(contactId, newKeys?, newLevel?)` primitive items 7–9 already mandate** (preserving `contactId` so held/distributed shares don't orphan). Opportunistic timing; no new message type.
   - **A key change *forces* re-choosing the verification level** (no silent inherit) — the old level attests the now-dead key; Bob re-asserts it fresh from the channel he used, per item 6 (in-person → `VERY_HIGH`, a Signal message → `LOW`, …), consistent with item 10's no-carry-forward and its "level always reflects the most recent personhood assurance about the *current* key" principle.
   - **UI steers to *update*, not delete+add** — delete+add mints a fresh `contactId` and orphans the shares (item-7/8 anchor).
   - Transitive vouch rejected as web-of-trust (the TOFU-style trust delegation Deposplit deliberately avoids) and disproportionate to the low stakes. "Single verified approver" retired.
   - **Boundary:** this governs the *benign* recovery path; if Bob had flagged Alice's old key compromised/revoked, the same OOB "update my keys" is instead item 10's high-stakes conflict to resolve manually. Clean handoff, no overlap.

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
   - **Embed `k` and `n` in the deposit payload** so holders report thresholds at recovery (cross-holder consistency check as a bonus). Cryptographically harmless — SSS never relied on hiding `k`/`n`. Demotes the catalog backup to pure convenience: re-linking any one holder of a secret already tells Alice `k`/`n`, hence how many more holders to find.
   - **Never share co-holder identities** — holders report `k`/`n` but stay ignorant of each other.
   - **Metadata transport = relay-mediated holder push** (works async / for remote re-links / scales; leaks nothing new since `secretId`/`label`/keys are already cleartext on relay rows). Out-of-band-at-re-link (device-to-device QR) stays as a purist alternative.
   - **Optional catalog backup:** a self-managed export of the *non-secret* catalog — contact public keys, pseudonyms, verification levels, `ShareMetadata` — eases "who are my holders" without weakening anything (none of it is a share or a private key). Backing up *private keys* is the opposite extreme (trivial recovery, but reintroduces a secret to guard + a platform dependency) and is out of scope for the trust-minimizing default.
   - **Post-recovery:** Alice re-splits under her new identity to restore a clean distribution (ties to secret lifecycle, item 11).

   **Implemented** on the relay (`hexagons/relay` + `app/controllers/api`), Android `:hexagon`/`:app`, iOS `hexagon`/`Deposplit`, and (for cross-platform *consistency*, not full parity) `deposplit.com/hexagons/phon`. The relay gained a fourth `ShareTransactionType` (`inventory`/`Inventory` — named `recovery_metadata`/`RecoveryMetadata` at the time, renamed by the Aug 2026 `ShareTransactionType` cleanup, see `TODO.md`'s cross-cutting chores) — unlike the other three it is **not consent-gated**: `ShareRequestsService.openShareRequest` creates it directly in `Approved` state (no pending phase, no conflict check — a holder may push repeatedly), and the recipient deletes it once consumed via the existing `deleteShareRequestById`. `k`/`n` were added as columns on `share_requests` (required and bounds-checked for `deposit`/`inventory`, forbidden for `retrieval`/`removal`) and appended to the end of `PayloadCanonical.forOpen`'s signed byte sequence on all four platforms — appending, not inserting, kept the existing cross-platform byte-vector tests's pre-item-8 fields undisturbed; only the fixture, its recomputed BouncyCastle-vs-BouncyCastle-vs-CryptoKit signature, and the trailing two lines changed. `HeldShare` gained `k`/`n` fields so a holder can report thresholds during recovery without needing a `Secret` record of its own.

   The **retrieve re-key** (checklist item 4) turned out to need less code than expected: `secretId` was already a top-level column on every `ShareRequest`/relay row, so the holder's `respond()` simply switched its plaintext lookup and post-delete-approval cleanup from matching on `request.shareId` (the sender's local id — meaningless to the holder after recovery, since a recovered `ShareMetadata.id` is a freshly generated UUID with no relay-row counterpart) to matching on `request.secretId` (globally unique per deposit, stable across identity loss). `ShareRepository.getPlaintextShare` was renamed from `shareId:` to `secretId:` accordingly on all four platforms.

   `ContactManagement` gained `updateContact(contactId, newKeys?, newLevel?)` on Android/iOS — fetches the existing contact, requires a fresh `verificationLevel` whenever either key changes (enforced by a domain error, never a silent carry-forward), and saves back preserving `id`/`pseudonym`/`addedAt`/`relayBaseUrl`. phon's version omits the `verificationLevel` parameter (no picker UI, consistent with item 6's narrower phon scope) and defaults a key change to `VeryHigh`, mirroring `addFromQr`'s existing default for its analogous re-scan-in-person flow.

   `ShareManagement` gained `pushRecoveryMetadata(contactId)` (holder side: opens an `inventory` push for every `HeldShare` held from that contact) and a private `processRecoveryMetadata()` (owner side: consumes pending `inventory`/`Approved` pushes addressed to this device — verified against a *known* contact's `senderSignature` before being trusted — upserting a `Secret` and a `ShareMetadata` row per push, then deleting the consumed relay row), called from the tail of `syncInbox()` alongside the existing deposit processing loop. Android/iOS additionally gained a "Relink (Key Changed)" UI action on the Contacts screen (a QR re-scan flow distinct from "Add Contact" — it calls `updateContact` then `pushRecoveryMetadata` rather than minting a new contact) and a Settings-screen "Catalog Backup" section (`CatalogManagement.exportCatalog`/`importCatalog`, upsert-if-absent-by-id — an existing local record is never overwritten by an imported one) using each platform's native file picker (SwiftUI `.fileExporter`/`ShareLink`/`.fileImporter` on iOS, Storage Access Framework `CreateDocument`/`OpenDocument` on Android). phon picked up the `Catalog` aggregate and `CatalogManagement`/`CatalogService` primitive for consistency but has no export/import UI (its minimal HTMX views have no plumbing for a file picker).

   **No migrations** — Deposplit is pre-launch; test relays and test devices will be reset to a clean slate.
9. **Holder-key-change handling + share-redundancy monitoring.** Post-item-7 a holder's key change is no longer a "share lost" event — it splits in two, each needing a different mechanism.
   - **Proactive rotation (holder keeps data):** only Alice's *routing pointer* to the holder goes stale (the plaintext share is safe). The holder still has the old key, so their app pushes a **signed `rotate(K_old → K_new)`** to contacts via the relay; Alice auto-verifies against the trusted old key and updates the contact record **in place, preserving `contactId`**. Fully automatic — *on the receiving end*. ⚠ **What triggers the holder's own key regeneration is intentionally unspecified here and not yet built anywhere.** This is a deliberate "I still have my device and my old keys, but I want fresh ones" action — the opposite case from item 8's device-*loss* recovery — and nothing in the app today lets a user do that (`IdentityStore` has no "replace my keypair, keep everything else" capability, and there's no UI for it). Scoped as its own follow-up, separate from the relay/receive-side plumbing landing per-platform below — see `TODO.md` item 9's note.
   - **Device loss (holder loses data):** the held plaintext shares are **gone**, and the holder's new device *cannot notify Alice* (it has no record it ever held them). Genuine redundancy loss, detectable only by Alice actively checking.

   **Health-check / redundancy monitoring — the authoritative mechanism.** *(⚠ Pull-vs-push reshaped by item 12: the "Alice pings, holder acks" framing below is **superseded** — monitoring is now a holder-initiated signed **custodial-heartbeat push** ("still guarding {secretIds} for you"), default-on and holder-disableable, with a signed opt-out notice. The detection semantics here — n_live vs k, sustained absence = loss — are unchanged; only who initiates flips.)* Each holder's app proactively reassures Alice which subset it still holds; Alice tracks **n_live vs k** per secret and surfaces per-secret health. Catches lost holders, missed/un-pushed rotations, and silent recipient-initiated deletions. Polling-based (cadence pinned in item 12).

   **Relay-tombstone fast-path — cheap complement, never authoritative.** Recipient-initiated deletion additionally flips the holder's deposit row to an explicit **"withdrawn by recipient"** state, which Alice picks up via her existing `syncDistributed()` poll — faster notice than the next health-check cycle, at near-zero cost. But the relay is a **mailbox, not a store — it may delete any row at any time** — so this is doubly best-effort (needs the holder's device alive *and* the relay to still hold the row when Alice polls) and must never be relied on:
     - **Row *absence* is never a signal** (could be relay GC, not withdrawal) — `syncDistributed()` keeps its "upsert, never delete" rule.
     - Only an **explicitly observed "withdrawn" tombstone** (if caught before GC) or a **heartbeat / sustained no-heartbeat** (item 12) counts. The heartbeat is ground truth; the tombstone is a lossy hint; Alice's local `ShareMetadata` (corrected by heartbeats) is her source of truth — never the relay. Tombstone writes are fire-and-forget (the blind relay can't confirm delivery).

   **Repair requires reconstruction (the item-11 hand-off).** Alice can't cheaply "top up" a lost holder — SSS shares come from a specific polynomial, and she doesn't retain the secret/polynomial (retaining it would defeat splitting). So restoring a lost share = **reconstruct (gather k) → re-split to a fresh holder set**. The whole value of health-monitoring is catching a loss **while still ≥ k**, so she can reconstruct-and-re-split *before* a second loss makes the secret permanently unrecoverable.

   **Policy shift:** recipient-initiated deletion moves from "purely local, no message" to "unilateral, but best-effort notifies Alice" — the holder keeps full autonomy (no approval) but can't vanish *silently*.

   **Implemented everywhere (2026-08-18): relay, iOS, Android, and (for consistency, not full parity) phon.** Both pieces above landed on the Web app/service:
   - The rotation push is **not** a fifth `ShareTransactionType` case. It carries no `secretId` and has no consent phase, so folding it into `share_requests` would mean a nullable `secret_id` plus a pile of forever-`NULL` share-specific columns — the same abstraction-fit problem the `ShareTransactionType` rename (see `CHANGELOG.md`) had just cleaned up. It lives in its own small table instead, `key_rotations(id, old_ed25519_key, recipient_key, new_ed25519_key, new_x25519_key, signature, created_at)`, with its own `KeyRotations` port and `POST`/`GET /key-rotations` + `DELETE /key-rotations/{id}` endpoints — no `state` column, since (like `inventory`) it's a fire-and-forget push with nothing to approve: the recipient polls, auto-verifies `signature` against `oldEd25519Key` (the trusted key it already knows this contact by), and deletes the row once consumed. `newX25519Key` rides alongside `newEd25519Key` since both keypairs are generated together at first launch and rotate together — the relay never performs key agreement with it, only routes it opaquely, same as ciphertext.
   - The withdrawn tombstone **is** a `ShareRequestState` addition (`Pending`/`Approved`/`Denied`/`Withdrawn`), reached via a new `POST /share-requests/withdraw` — deliberately a new endpoint/port method rather than repurposing the existing bulk `deleteShareRequests`, since that method's two real call sites (Removal-approval cleanup, Deposit-delete cascade) both want a genuine hard delete, not a tombstone.
   - **iOS, Android, and phon** all picked up both pieces per the scope split above (receive-side + `pushRotation(to:)` primitive; withdraw wired both ways), landing on identical designs independently across the three clients — `ShareRelay` gained the four new methods directly rather than a separate port on each (same physical relay, same BYOR routing, so a second port/resolver would have bought nothing); `ShareService` gained a `ContactManagement` dependency on each so its new `processRotations()` can auto-verify an incoming notice and call `updateContact` with item 10's `min(level, LOW)` downgrade baked in (Kotlin's ordinal-`Comparable` enums let Android express this as a bare `minOf`; phon's `Ordered[VerificationLevel]` needed an explicit `if`). phon's `updateContact` needed an actual signature change (a new `verificationLevel` parameter it previously lacked entirely) rather than just plumbing — the one place item 9 collided with item 6's "no picker UI" phon simplification on a genuine correctness question, not UI polish, since auto-accepting a rotation at phon's old unconditional `VeryHigh` default would have been actively wrong per item 10's rule.
   - phon skipped only UI (no withdraw button, no rotation-push trigger — consistent with every prior item's precedent, since phon's minimal HTMX views have no plumbing for any of that already).
   - See `TODO.md` item 9 for the full implementation notes (files touched, test counts, the cross-platform `forRotation` signature vector all four platforms now agree on).

   **Work items:** tracked in `TODO.md` (item 9; the heartbeat push itself is reshaped by item 12). **No migrations** (pre-launch).
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

    **Work items:** tracked in `TODO.md` (item 10). **No migrations** (pre-launch).
11. ~~**Secret lifecycle — bounds, sender-side `Secret` record, discard, and the composed re-split.**~~ — **done.** Decided during the spec walk. This is the anchor items 8 and 9 lean on when they say "reconstruct + re-split," now pinned down. It replaces the sender side's *implicit* model (per-share `ShareMetadata` rows grouped by `secretId` at query time, no per-secret entity, a hardcoded reconstruct threshold) with an explicit one.

    **Bounds — the hard invariant is `2 ≤ k ≤ n ≤ 255`.** `k ≥ 2` is a **hard floor** (`k = 1` isn't secret-*sharing* — any single holder reconstructs alone, defeating the "< k reveals nothing" premise); hence `n ≥ 2`. `n ≤ 255` is field-imposed (x-coordinates live in GF(2⁸), x=0 reserved for the secret). The domain (hexagon `split`/`deposit`) enforces this and throws on violation. **No hard UI ceiling on `n`** — a board-of-directors secret legitimately wants large `n` (with a correspondingly large `k`). Instead, **three soft, non-blocking warning axes** (dismissible "Are you sure?" confirmations; exact thresholds/wording are UI tuning, not load-bearing spec):
    - **Operational burden** (magnitude, *not* a security warning) — large `n` (e.g. `n ≥ 10`, `n ≥ 20`): you must exchange keys with all `n`, approve all `n` pickups, and health-check all `n` (item 9). Copy should make clear this is *work*, not *danger*.
    - **Confidentiality tail** — `k` low relative to `n` (e.g. `k < n/2`, `k < n/3`): a small clique reconstructs behind Alice's back.
    - **Availability tail** — redundancy margin `n − k` small (e.g. `k = n` → *any single lost holder = secret gone forever*; `k = n − 1` → tolerates exactly one loss). This is the redundancy-erosion failure item 9 exists to catch, so warning at split time is the cheapest prevention. Without it the UI would let someone pick `10-of-10` silently, then item 9 alarms the moment a phone dies.

    The two ratio tails are symmetric around a healthy middle: SSS forces a trade-off at fixed `n` — confidentiality rises with `k`, availability rises with `n − k`; you can't push both up without raising `n`.

    **Sender-side `Secret` aggregate.** Today the sender has *nowhere* to store `k` — only per-share `ShareMetadata` rows — which is why `reconstruct()` uses a hardcoded `check(approved.size >= 2)` (literal `2`, not the real `k` — a genuine bug for any `k ≠ 2`). Introduce a per-secret record keyed by `secretId`: **`Secret(secretId, label, k, n, secretCreatedAt, state)`**. `ShareMetadata` is **normalized to reference it** — drops the duplicated `label`/`secretCreatedAt` (single source of truth; the UI has the `Secret` loaded when rendering its shares), keeps what's genuinely per-share: `secretId` (→ `Secret`), `contactId` (the holder), and item 9's per-share health status. `reconstruct(secretId)` then reads `k` from the `Secret` and enforces `approved.size >= k` — the literal `2` becomes correct by construction. *(Its fan-out/collection semantics — fan out beyond `k` to the item-12 fresh set, first `k` consistent shares win, surplus cross-checked for integrity — are specified in item 13.)* Consistent with item 8's decision to also put `k`/`n` in the deposit payload (sender persists locally, wire carries to holders, holders store in `HeldShare` — same two numbers everywhere).

    **No named "re-split" or "rotate-value" flow — one new primitive, freely composed.** "Value rotation" is *not* a concept the app needs to model: when Alice has a new BitLocker key she just **`deposit(newValue)` + `discardSecret(oldSecretId)`** — Bob can't and needn't distinguish "same computer, rotated key" from "migrated to a new computer, retired the old." Both of the flows considered dissolve into the same primitives, differing only in *whether Alice already holds the value*:
    - **Has the value** (rotation / genuinely new secret): `deposit(value)` + `discardSecret(old)`.
    - **Lacks the value** (item 9 top-up — *same* value, restore redundancy): `reconstruct(old)` to get it back in hand → then `deposit(value)` + `discardSecret(old)`.

    So the lifecycle adds exactly **one new primitive**: **`discardSecret(secretId)`** — a fan-out **sender-initiated `removal`** to every holder of that `secretId` (composed from the existing per-share `removal` request type; each holder must **approve** per the symmetric consent model, so it is *request*, not *compel*, and best-effort — a dark holder never approves, and the relay may GC the request row before they poll). Every `deposit()` already mints a fresh `secretId` (= a fresh polynomial), so the new distribution is automatically distinguishable from the old — **necessary**, because shares from two different polynomials for the same value are *not* interchangeable (`combine()` needs `k` shares from the *same* polynomial). The **`supersedes` link is dropped** (YAGNI): with no rotation concept, nothing knows the new secret "replaces" the old, and there's no natural moment to populate it.

    **`reconstruct()` becomes a pure read (behavior change from current code).** Today `reconstruct()` tears down Alice's local `ShareMetadata` + relay rows after combining — the "Alice forgets, holders remember" asymmetry (holders' `HeldShare`s persist because they're only asked to delete via an approved `removal`). Since reconstruct is now a *step* toward re-split, that auto-teardown is premature. Cleanly separated they are **GET vs DELETE**: `reconstruct` reads the value and changes nothing; `discardSecret` is the *only* teardown path. This also fixes the orphaned-holder problem (Alice no longer silently forgets a distribution she hasn't discarded). **Code TODO: remove the auto-delete from `reconstruct()`.**

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

    **Implemented** on iOS `hexagon`, Android `:hexagon`, and (for cross-platform *consistency*, not full parity) `phon`. `Secret(id, label, k, n, secretCreatedAt, state)` is a new sender-side aggregate persisted by a new `SecretRepository` driven port (`LocalSecretRepository` on Android/iOS, `FileSecretRepository` on phon); `ShareMetadata` dropped `label`/`secretCreatedAt` down to `id`/`secretId`/`contactId`. `deposit()` now also saves a `Secret`; `reconstruct(secretId)` reads `k` from it (the literal `check(approved.size >= 2)` is gone) and is a pure read — the auto-teardown of local `ShareMetadata` and relay rows was removed entirely. `discardSecret(secretId)` is the one new primitive: flips the `Secret` to `DISCARDING` immediately, fans out a `removal` request to every holder (reusing the existing per-share `openRequest`), and a new `reconcileDiscarding()` step (run at the end of every `syncDistributed()`) cleans up each holder's `ShareMetadata` row and relay row as its `removal` is approved, removing the `Secret` record once none remain; `forceForgetSecret(secretId)` is the local-only escape hatch for a holder who will never respond. Android/iOS also gained: a graduated `n_live`-vs-`k` health badge on each secret's card (healthy/caution/critical/lost, suppressed while `DISCARDING`) — `n_live` here is a pre-item-9/12 proxy (the count of holders still locally tracked), refined later by item 12's freshness model; and three non-blocking "Are you sure?" warnings at deposit time (operational burden, confidentiality tail, availability tail) with concrete thresholds chosen as UI tuning, not spec. iOS additionally restructured its Distributed tab from a flat per-share list into per-secret grouped cards (bringing it to parity with Android's existing grouping), since the health badge and discard action are naturally secret-level, not share-level. phon picked up the `Secret` aggregate, the `reconstruct`/`discardSecret`/`forceForgetSecret` primitives, and simplified its existing manual per-share delete-fan-out (`deleteMySecret`) to call `discardSecret` directly — but skipped the health badge and split-time warning UI, since its minimal HTMX views don't have plumbing for either yet. The `2 ≤ k ≤ n ≤ 255` bound was already enforced by `split()`/`combine()` on all three platforms before this item — no new code needed there.

    **Work items:** tracked in `TODO.md` (item 11). Hexagon (Android + iOS; relay untouched — it never stores `Secret`/`ShareMetadata`). **No migrations** (pre-launch, clean-slate reset).
12. **Polling, staleness & relay-TTL cadence — the custodial-heartbeat model.** Decided during the spec walk. Pins down how items 5 (polling) and 9 (redundancy monitoring) cash out into concrete cadence rules, and **flips item 9's health-check from a pull to a push.** No new relay behavior — the relay stays a blind mailbox; this is client cadence + one message-type reshape.

    **Custody monitoring is a holder-initiated push, not a sender-initiated pull (supersedes item 9's "health-check request/ack").** Item 9 framed monitoring as Alice *pinging* each holder ("still holding?") and the holder auto-acking — which puts the holder in the psychological position of an *audited suspect* whose app answers behind his back. Flipped: **Bob's app proactively reassures Alice — "still guarding {secretIds} for you"** — recasting him as an *active custodian reporting in*. Mechanically equivalent for detection (both read *sustained absence* as the loss signal; a device-loss replacement has no `HeldShare`s, so it sends nothing / can't ack — silence either way), so the swap is free and buys the better framing plus holder autonomy by construction (volunteering info means *not* volunteering is the natural opt-out).
    - **Signed.** A heartbeat is an Ed25519 signature over the reported `secretIds` + timestamp, verifiable against the holder's key in the sender's contact record — a blind relay can't forge "Bob says he's fine."
    - **Default-on, holder-disableable.** Ships enabled (the cooperative default that keeps the system healthy); the holder can silence it globally or per-sender.
    - **Opting out is itself a signed notice** ("my silence from here on is not a loss signal"), so Alice distinguishes *"holder chose privacy — status unknown by choice"* from *"holder went dark — possible loss"* and does **not** false-alarm on a healthy-but-private holder. An unmonitorable holder is surfaced as a standing advisory (a redundancy risk Alice may choose to re-split away from), never a loss alarm.
    - **No pull mechanism at all — the model is purely one-directional (holder → sender).** A "refresh now" was considered and dropped: it resurrects the audit flavor, and the *retrieve flow already is* the on-demand check (and a stronger one — it asks for actual bytes, not just liveness). The only moment Alice needs a fresh live read is when she is about to reconstruct, and that is exactly when she opens retrieves to `k` holders.

    **Cadence — opportunistic, foreground-only, never background.** The heartbeat **piggybacks the holder's existing inbox poll** (the item-5 v0.1 model: poll on app-open + periodically while foregrounded, no FCM/APNs). When the app polls and it has been longer than the heartbeat interval since it last reported to a given sender, it emits one fresh heartbeat **coalesced per-sender** (covering all that sender's `secretIds` — O(senders), not O(shares)). **No background wake:** background scheduling would need the push infrastructure item 5 rejects (Google/Apple dependency) or unreliable OS budgets — and a custodian who never opens the app for weeks *is* a genuine redundancy risk Alice should see, so opportunistic heartbeat surfaces that truthfully rather than papering over it. The load-bearing part is **not the interval number** (UI tuning, like item 11's warning thresholds) but two guarantees: **(a)** the interval must be meaningfully shorter than the window in which two independent holder-losses could occur (so Alice catches the first loss while still ≥ `k`); **(b)** a **single** missed beat is never treated as loss — only sustained absence past a multiple of the interval.

    **Staleness — a second health axis, orthogonal to item 11's count, and the early warning for it.** Alice's `n_live` is only ever as fresh as the last heartbeat per holder, so the UI must never present a weeks-old "healthy" as live truth. Two axes:
    - *Count axis (item 11):* `n_live` vs `k` → healthy / caution / critical / lost.
    - *Freshness axis (new):* how recently each contributing holder proved custody.

    Tied together by one rule — **`n_live` counts only holders confirmed within a "loss-threshold" window** (not lifetime-ever-confirmed). *Any* signed proof-of-custody refreshes a holder's freshness clock — a heartbeat, **or** a pickup approval, **or** a retrieve approval — so a holder Alice recently retrieved from is automatically fresh. Three display buckets:

    | Bucket | Condition | Counts toward `n_live`? | Alarm |
    |---|---|---|---|
    | **Confirmed** | proof-of-custody within threshold | Yes | none (sub-flag "getting stale" as it nears the threshold — the early nudge) |
    | **Unmonitored by choice** | holder sent the opt-out notice | No — shown separately | no loss alarm; standing "N holders unmonitored" advisory |
    | **Silent / overdue** | expected heartbeats, none past threshold | **No — drops out of `n_live`** | feeds the item-11 count alarm |

    - **Dropping out at the threshold** (not merely annotating): the point of monitoring is to catch erosion *early*, so a long-silent holder must be reflected in the number Alice plans against, not hidden in a footnote.
    - **Reversible:** a long-silent holder who reopens their app and heartbeats pops straight back to *Confirmed* and `n_live` recovers — so "presumed lost" is a presumption, not a verdict, which makes erring toward alarming safe (false-positive cost = Alice nudges a holder, not an irreversible re-split).
    - Freshness surfaces *before* a holder crosses out of `n_live` ("Bob hasn't checked in for 20 days"), giving Alice a nudge while she is still comfortably ≥ `k`.

    **Relay-row TTL — a UX courtesy, never a correctness dependency.** The relay is a mailbox that may GC any row anytime; that is the reliability ceiling, not an operational spec. Two retention *classes* (operator tuning, non-load-bearing): consent-gated **action requests** (deposit / retrieval / removal) retained **generously** (days–weeks) so an offline counterparty can still act; **fire-and-forget pushes** (heartbeat / rotation / recovery-metadata-return / tombstone) **short / until-consumed / latest-wins** (a heartbeat is latest-wins per (holder, sender)). The load-bearing claim is the invariant that makes *any* TTL safe: **correctness comes from idempotent re-emission + "absence-is-never-a-signal" (item 9), not from retention** — every push self-re-emits on the next opportunistic poll, every action-request is re-issuable, and a missing row is only ever "GC'd or never-sent," never "done" or "lost." The relay may GC aggressively under quota pressure; it only ever adds latency.
    - **The `deposit` "only copy in transit" case, resolved.** A `deposit` carries the sole copy of the encrypted share; GC before pickup would appear to lose it, and item 11 does not retain the polynomial to cheaply re-mint one share. Fix: **Alice's app retains each encrypted-to-holder blob until that holder's pickup is confirmed, then discards it** — so a GC'd-before-pickup row is a cheap re-deposit from the retained copy. This is **information-theoretically safe**: under item 7 each blob is encrypted to a *holder's* X25519 pubkey, so Alice **cannot decrypt it herself** (she lacks the holders' private keys) — retaining all `n` is `n` opaque forward-only blobs, *not* Alice transiently holding a reconstructable secret.
    - **"Pickup confirmed" = either channel**, mirroring item 9's ground-truth/hint layering: **(1) relay-observed (fast, ephemeral)** — `syncDistributed()` sees the `deposit` row accepted (ciphertext delivered and cleared), which the relay may GC before Alice ever polls; **(2) heartbeat-attested (durable, GC-immune)** — the holder's signed heartbeat enumerates `secretId` X among shares held, proof-of-custody independent of any relay row. Either discards the retained blob. The retention window opens at deposit and closes at *first* confirmation; a *later* loss (holder had it, then device died) is not a re-deposit case but the item-9 reconstruct-and-re-split path — the two failure modes never overlap.

    **Nonce / auth window unchanged (5 min).** The replay-protection nonce window and the row TTL are **independent clocks that never interact**: the nonce clocks a single request *in flight* (replay protection); the TTL is *storage retention* of an already-accepted row. A deposit row may persist for weeks (TTL) though the request that created it was authenticated within 5 minutes (nonce); each re-emission (heartbeat, re-deposit, retrieve) is a fresh signed request minting a fresh nonce, so long retention and opportunistic re-emission never stress the window. Left at 5 minutes — a standard clock-skew allowance nothing in this item pushes on.

    **Work items:** tracked in `TODO.md` (item 12). Relay untouched (blind mailbox); Android + iOS hexagon/app. **No migrations** (pre-launch).
13. **Retrieve fan-out beyond `k` + reconstruction integrity (over-determination).** Decided during the spec walk. Refines item 11's `reconstruct()` / the retrieve flow; touches item 10 (malicious holder) and item 12 (health-informed targeting). **Client-only; relay untouched.**

    **Fan out beyond `k`, first `k` win — use the redundancy SSS already paid for.** Requesting shares from exactly `k` holders blocks the moment one is dark or slow to approve (each retrieve needs the holder's out-of-band consent, item 10), forcing serial escalation. Instead, `reconstruct(secretId)` **fans out to the health-informed fresh set** — item 12's `Confirmed` holders — widening to stale / unmonitored holders only if fewer than `k` are fresh, and **collects until `k` consistent shares are in.** Using `n_live` avoids wasting asks (and consent prompts) on known-dark holders; reconstruction is a rare event, so occasionally bothering the whole fresh set is an acceptable cost for latency and availability. Tolerates up to (fan-out − `k`) non-responders with no serial round-trips.

    **Over-determination gives integrity that plain Shamir lacks.** Classic Shamir has **zero** built-in integrity — *any* `k` shares define *a* degree-(`k`−1) polynomial and yield *a* secret, silently wrong if one share is corrupt or a holder lies. Extra responses beyond `k` fix this:
    - **`k`+1 consistent** → **detect** that a share is bad (the `k`+1 points won't fit a single degree-(`k`−1) polynomial).
    - **larger margin `m`** (responses collected − `k`) → **identify and exclude** the liar and still reconstruct correctly (Reed-Solomon bound: margin `m` corrects `⌊m/2⌋` bad shares, detects up to `m`).

    This lands on item 10's malicious-holder threat. The `recipientSignature` on a retrieve response proves the returned share is **authentic** (it came from that holder), **not correct** (the value they were originally handed) — a malicious holder signs bad bytes perfectly — so signatures don't substitute for the cross-check.

    **Integrity is over-determination only — no stored per-share commitment (rejected).** A per-share commitment (`H(share)`, or a hardware-keyed HMAC, stored in `ShareMetadata`) was considered as an alternative integrity mechanism — verify each returned share individually, needing only `k` reachable holders — and **rejected.** A plain hash of a **low-entropy** secret's tiny share (a 1-byte secret → a 256-value share) is brute-forceable, so **exfiltrating Alice's stored commitments** (a stolen backup / cloud sync / file-reading malware — not even a live device) would let an attacker recover all `n` shares and **reconstruct, bypassing the holder-consent layer** — re-introducing the exact "Alice's device holds something reconstructable" risk splitting exists to remove. A hardware-keyed HMAC (`K_device` non-extractable in Keystore/Secure Enclave) *would* close the offline-exfiltration hole, but adds on-device state + crypto for a benefit over-determination already provides. **Keeping Alice's device holding *nothing* that pins her shares is simpler and more faithful to the trust-minimising premise**, so integrity rests on over-fetching live responses, never on stored local data.

    **Consequence — reconstruct wants a healthy live margin, reinforcing item 12.** Detecting/excluding a lying holder needs `k`+1 / `k`+2 *live* responders, so integrity degrades exactly when redundancy is already thin — another reason item 12's health monitoring (catch erosion while comfortably ≥ `k`) is load-bearing, not a nicety. A secret at `n_live == k` can still be reconstructed but **without any integrity cross-check** (no margin to compare against) — surfaced honestly to Alice.

    **Work items:** tracked in `TODO.md` (item 13). Hexagon (Android + iOS); relay untouched. **No migrations** (pre-launch).

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
