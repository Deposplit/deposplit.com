# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Deposplit** is a secret-sharing app based on **Shamir's Secret Sharing (SSS)**. A secret is split into *n* shares, of which *k* are required to reconstruct the original secret. The app sends each share to one of *n* contacts and later reassembles the secret when at least *k* holders cooperate.

## How We Got Here

Deposplit's architecture evolved through several design sessions:

1. **The Signal question:** The project started by exploring whether a third-party app could piggyback on Signal — its contacts, groups, and messaging infrastructure. Signal is intentionally an appliance, not a platform: it exposes no SDK or inter-app API.

2. **Matrix adopted as transport:** Matrix was identified as the natural fit — designed to be built upon, with Android/iOS SDKs, arbitrary custom message types, E2EE via Double Ratchet, and federation. The SSS libraries (Kotlin and Swift ports of the Privy TypeScript reference) were built and tested. An Android scaffold was completed with a working OIDC sign-in flow (Chrome Custom Tab → matrix.org → deep-link callback).

3. **DCR friction with matrix.org:** matrix.org's Matrix Authentication Service rejected Deposplit's Dynamic Client Registration attempts (`invalid_redirect_uri`). This triggered a re-evaluation of the transport layer.

4. **Pivot to custom backend:** Matrix is heavyweight for Deposplit's actual protocol (4 message types). Since recipients must install Deposplit, federation between homeservers adds no user value. A custom deposplit.com backend with E2EE was chosen: simpler, leaner, and the server provably cannot read share content regardless of breach.

5. **Backend redesigned as a stateless relay:** The initial backend design included a `users` registry and a `contacts` table with a server-mediated invitation flow. This violated the trust-minimising philosophy: the server knew who the users were and who knew whom. The backend was simplified to a pure relay with no user registration and no contact storage. Key exchange happens out-of-band (QR code in person, or via Signal/Threema). The server authenticates callers by verifying Ed25519 signatures against the public key supplied in each request header — no pre-registration required. The DB schema shrank from four tables (`users`, `contacts`, `shares`, `share_requests`) to two (`shares`, `share_requests`).

## Architecture Decisions

### Communication Layer: Custom Backend

The transport layer is a **custom deposplit.com REST API** with end-to-end encryption. No native crypto libraries are used on any platform — everything is implemented using each platform's standard crypto stack (BouncyCastle on Android/JVM, Swift Crypto / CryptoKit on iOS).

Key design decisions:
- **User identity is two keypairs.** At first launch the device generates an X25519 keypair (share encryption) and an Ed25519 keypair (API authentication). The user picks a pseudonym (display name only, stored locally on the device — never sent to the backend). No server registration is required: the keypair IS the identity. Contacts exchange both public keys out-of-band — ideally in person via QR code, or via a trusted third-party channel (Signal, Threema, email).
- **Server is an opaque relay.** The backend stores and forwards ciphertext only. It never participates in key agreement and cannot decrypt share content regardless of a breach. Reconstructing the original secret requires compromising at least *k* recipients' X25519 private keys, which live only on their devices.
- **Library-agnostic authentication protocol.** API requests are authenticated via Ed25519 signatures (RFC 8032) over a canonical request representation. Mobile clients sign with BouncyCastle (Android) or Swift Crypto (iOS); the backend verifies with BouncyCastle (`Ed25519Signer`). Ed25519 is deterministic and fully specified — cross-library interoperability proves the protocol is correctly defined, not a coincidence of using the same library. The canonical signing string is:
  ```
  nonce || "\n" || UPPERCASE(method) || "\n" || path_with_query || "\n" || hex(SHA-256(body))
  ```
  where `body` is the empty string for requests without a body. Three request headers carry the authentication material: `X-Deposplit-Public-Key` (caller's Ed25519 public key, base64url), `X-Deposplit-Nonce` (per-request unique string in the form `<unix-ms>.<random>`; server rejects requests whose embedded timestamp is more than 5 minutes old), `X-Deposplit-Signature` (base64url-encoded signature).
- **No federation needed.** Recipients must install Deposplit, so cross-server communication adds no user value. Deposplit operates a single canonical backend at deposplit.com.
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
| `deposplit.com/` | [Deposplit/deposplit.com](https://github.com/Deposplit/deposplit.com) | Project hub, landing page, cross-project documentation, and backend server |
| `Android/` | [Deposplit/Android](https://github.com/Deposplit/Android) | Kotlin SSS library + Android app (`:hexagon` + `:app` Gradle modules) |
| `iOS/` | [Deposplit/iOS](https://github.com/Deposplit/iOS) | Swift SSS library + iOS app (SwiftUI, iOS 26+) |

### Backend Tech Stack: Scala + Play

The `deposplit.com` repository is a **Play Framework (Scala)** application built with **sbt**. It serves two distinct concerns:

- **Landing page / GUI**: server-side rendered with **Twirl** templates
- **REST API**: consumed by the Android and iOS apps (spec at `conf/openapi.yaml`)

Architecture follows **Ports & Adapters** enforced by sbt's multi-project build, mirroring the approach in the global CLAUDE.md:

| sbt subproject | Role |
|---|---|
| `hexagon` | Pure Scala library — business logic, port interfaces, no Play/framework imports. Packages: `value_objects`, `driving_ports`, `driven_ports.persistence`, `services` |
| root (Play app) | Adapters (DB, backend API controllers), Twirl views, routes |

The `hexagon` subproject has **no dependency on Play** or any infrastructure library. The root Play project depends on `hexagon`; `hexagon` must never depend on the root. This enforces the hexagonal boundary at the build level.

The hexagon and root both programme **synchronously (blocking)** — no Scala `Future`s. This keeps the code straightforward and stack traces readable; with Java virtual threads becoming mainstream, blocking I/O will carry negligible cost.

**Key library choices:**
- **sbt** build tool (use standard `build.sbt` and `project/` Scala/sbt files)
- **Play JSON** (`play-json`) for API serialisation — bundled with Play
- **Twirl** (built into Play) for the landing page
- **BouncyCastle** (`bcprov-jdk18on`) for Ed25519 signature verification — declared in `hexagon/build.sbt` because signature verification is a domain concern; no native libsodium on the server — share content passes through as opaque bytes
- **PostgreSQL** for persistent storage — see rationale below
- **H2** as an in-memory database for development and testing (no PostgreSQL instance required locally); configured with `MODE=PostgreSQL` in `conf/localhost.conf`. H2 compatibility constraints to keep in mind when editing the evolutions script: use `TIMESTAMP WITH TIME ZONE` not `TIMESTAMPTZ`; place `DEFAULT expr` before `PRIMARY KEY` in column definitions; avoid semicolons inside `--` line comments (H2 tokenises them as statement terminators); partial indexes (`WHERE` clause) are not supported — the one-pending-request-per-type constraint is enforced at the application level in `SharesService` instead and must be added to production PostgreSQL manually (see comment in `1.sql`). The `NULL`-able `ciphertext` and `picked_up_at` columns in `shares`, and the `ciphertext` column in `share_requests`, use standard nullable `BYTEA` / `TIMESTAMP WITH TIME ZONE` — H2 handles these without special treatment
- **Anorm** for database access (preferred over Slick) — SQL-first, minimal abstraction, fits cleanly in the adapter layer of the hexagonal architecture; Slick (type-safe DSL) is an acceptable alternative if type-safe query composition is preferred
- **Play Evolutions** for schema migrations — initial schema at `conf/evolutions/default/1.sql` (two tables: `shares`, `share_requests`)
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
- Share encryption: **X25519 + HKDF-SHA-256 + ChaCha20-Poly1305** — mobile only; backend never decrypts
- API authentication: **Ed25519** signatures (RFC 8032) — BouncyCastle on Android, Swift Crypto on iOS, BouncyCastle on backend

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

Each share is encrypted by the sender to the recipient's X25519 public key before leaving the device. The construction is a standard static-static DH box:

1. **Key agreement**: X25519(sender_private_key, recipient_public_key) → 32-byte shared secret
2. **Key derivation**: HKDF-SHA-256(ikm=shared_secret, salt=nonce, info=`"deposplit-share"`) → 32-byte symmetric key
3. **Encryption**: ChaCha20-Poly1305(key, nonce, plaintext) → ciphertext + 16-byte tag
4. **Wire format**: `nonce(12 bytes) || ciphertext+tag`

The backend stores ciphertext only. A full backend breach yields nothing without also compromising at least *k* recipients' X25519 private keys.

**Why no native crypto library (libsodium was the original choice, rejected Apr 2026):**

| Criterion | libsodium | BouncyCastle + Swift Crypto |
|---|---|---|
| Android native `.so` | Required (JNA/lazysodium, complex ABI setup) | Not required — pure JVM |
| iOS | Required (or manual Swift port) | Swift Crypto (Apple-maintained, no native deps) |
| Backend | Not needed | Already used (BouncyCastle) |
| Cipher available everywhere | XSalsa20-Poly1305 missing from Swift Crypto | ChaCha20-Poly1305 in all three stacks |
| Auditability | Opaque prebuilt binaries | Open-source, platform-standard |

BouncyCastle provides `X25519Agreement`, `HKDFBytesGenerator`, and `ChaCha20Poly1305` on Android and the backend. Swift Crypto provides `Curve25519.KeyAgreement`, `HKDF`, and `ChaChaPoly` on iOS.

#### Implementation Status

The SSS ports are **complete and fully tested**:

| Library | Module | Public API |
|---|---|---|
| `Android/` | `com.deposplit.shamir` | `split(secret: ByteArray, shares: Int, threshold: Int): List<ByteArray>` / `combine(shares: List<ByteArray>): ByteArray` — throws `IllegalArgumentException` |
| `iOS/` | `ShamirSecretSharing` | `split(secret: [UInt8], shares: Int, threshold: Int) throws -> [[UInt8]]` / `combine(shares: [[UInt8]]) throws -> [UInt8]` — throws `ShamirError` |

The Android `DeposplitAuthAdapter` uses **BouncyCastle** (`bcprov-jdk18on`) for all crypto — no native libraries, no JNA. The iOS equivalent uses **Swift Crypto**. The backend uses BouncyCastle for Ed25519 verification and passes share ciphertext through opaquely.

#### Cross-Platform Compatibility

Both test suites contain three identical hand-derived test vectors (in `ShamirTest.kt` and `ShamirSecretSharingTests.swift`) that verify `combine()` byte-for-byte against the same inputs. The vectors use the polynomial `f(x) = secret_byte + 0x01·x` in GF(2⁸) with x-coordinates `[1, 2]` — the simplest non-trivial 2-of-2 case — and were verified by hand against the GF(2⁸) arithmetic tables.

### App Protocol

Secrets are identified by a **UUID** generated at split time. The human-readable label (e.g. "BitLocker key") is display-only metadata — two secrets with the same label are distinguished by their UUIDs.

There are four message types exchanged via the deposplit.com backend API:

| # | Direction | Payload | Purpose |
|---|---|---|---|
| 1 | Sender → recipient | `secret_id` (UUID), `label`, `created_at`, share bytes (encrypted to recipient's public key) | **Deposit** a share with a recipient |
| 2 | Sender → recipient → sender | Request: sender identity. Response: list of `{secret_id, label, created_at}` — **no share bytes** | **List** shares the recipient holds for the sender |
| 3 | Sender → recipient → sender | Request: `secret_id`. Response: share bytes or denial | **Retrieve** a specific share |
| 4 | Sender → recipient → sender | Request: `secret_id` (or all shares). Response: ack or denial | **Delete** a share (sender-initiated) |

**Recipient-initiated deletion** is purely local — no message is needed. The recipient can unilaterally delete individual shares or all shares from a given sender at any time.

**The backend is a pure relay — ciphertext is ephemeral:**

Message 1 has two sub-phases, both mediated by the relay:
- **Deposit sub-phase** (sender → relay): Alice posts the ciphertext; the relay stores it temporarily.
- **Pickup sub-phase** (relay → recipient): Bob explicitly fetches his share (`GET /shares/:shareId`); the relay delivers the ciphertext once, then clears it (`shares.ciphertext` set to NULL, `shares.picked_up_at` recorded). The ciphertext now lives only on Bob's device.

Message 3 (Retrieve) is symmetric:
- **Request sub-phase** (sender → relay): Alice opens a retrieve request; the relay stores it as pending.
- **Response sub-phase** (recipient → relay → sender): Bob approves and **sends the ciphertext from his local storage** in the response body; the relay stores it temporarily in `share_requests.ciphertext`. Alice polls, fetches the ciphertext, then deletes the share row (which cascade-deletes the request row) to clean up the relay.

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
3. Alice adds Bob to her local contact list — the backend is not involved
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

Deposplit maintains a contact list stored **exclusively on the device** — the backend never stores or indexes user identities or contact relationships.

Each contact is identified by their **Ed25519 public key** (routing identity on the backend) and **X25519 public key** (used by the sender to encrypt shares client-side). Both must be obtained out-of-band before Alice can deposit shares for that contact.

Contact addition methods:
- **QR code scan (preferred):** encodes both Ed25519 + X25519 public keys and the pseudonym directly — no server intermediary, eliminates TOFU risk
- **Out-of-band link:** the app generates a shareable link carrying both public keys; Alice receives it via Signal, Threema, email, etc. Weaker TOFU assurance than an in-person QR scan, but convenient for remote contacts

Each contact record stores: Ed25519 public key, X25519 public key, pseudonym, verification level, date verified. All stored locally on the device.

Adding a contact is the natural moment to prompt for in-person QR verification.

### Contact Verification

Deposplit uses a two-level verification model inspired by Threema:

| Level | How achieved | Meaning |
|---|---|---|
| **Unverified** | Contact added remotely (by pseudonym, invite link, etc.) | "I believe this Deposplit account belongs to this person, but I haven't confirmed it" |
| **Verified** | QR code scanned in person | "I was physically with this person and confirmed their public key is theirs" |

The in-person QR scan encodes the contact's public key (and optionally the pseudonym). Verification level is stored per contact and is visible to the user when reviewing share holders or approving requests.

### Identity Recovery

If Alice loses her phone and cannot recover her private key, she generates a new keypair on a new device and initiates a **re-association request**: "please map my new public key to my old one."

Recovery uses **social recovery (k-of-n)**: the same threshold k used when the secret was split must approve the re-association before it takes effect. Verification level influences the trust calculus:
- Approval from a **verified** contact (in-person QR scan) carries stronger assurance than approval from an unverified one
- A single verified approver may be considered sufficient; the exact rule is TBD

Recipients who approve a re-association should be encouraged to verify Alice again in person (re-scan her new QR code) to restore the verified relationship.

## Development Status

### What is done

- **SSS libraries**: both the Kotlin (`Android/`) and Swift (`iOS/`) ports of Shamir's Secret Sharing are **complete and fully tested**.
- **Design**: the full app layer is designed — backend protocol (4 message types + consent model), share holder onboarding, contact verification, identity recovery, contacts management, secret input methods, Ports & Adapters architecture. All documented in this file.
- **Android registration**: `DeposplitAuthAdapter` generates X25519 + Ed25519 keypairs via BouncyCastle (`bcprov-jdk18on`); private keys are wrapped with an AES-256-GCM master key in the Android Keystore (`deposplit_master`) and stored encrypted in `SharedPreferences`; pseudonym stored plaintext. `SignInScreen` / `SignInViewModel` collect a pseudonym and call `register()`. `MatrixAuthAdapter` deleted; `matrix-rust-sdk` dependency removed.
- **iOS app**: Full feature-parity implementation with Android. SwiftUI app targeting iOS 26+ with `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor`. All screens implemented: registration (`SignInView`), home with three tabs (Distributed/Held/Requests), contacts management with QR scan (`DataScannerViewController`) and manual entry, deposit flow (`Shamir.split` + `auth.encrypt` + `transport.depositShare`), recipient consent flows (approve/deny), sender-side reconstruction (`auth.decrypt` + `Shamir.combine`), QR display (CoreImage). Crypto via CryptoKit: X25519 key agreement + `hkdfDerivedSymmetricKey(using: SHA256.self, ...)` + `ChaChaPoly`. Ed25519 signing via `Curve25519.Signing.PrivateKey`. Private keys in Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`). API adapter uses `URLSession` + `SHA256.hash(data:)` for body hash. Uses `PBXFileSystemSynchronizedRootGroup` (Xcode 16+): no need to edit `project.pbxproj` when adding files.
- **deposplit.com hexagon domain**: the `hexagon` sbt subproject is fully implemented — value objects (`SecretId`, `Label`, `PublicKey`, `Nonce`, `Signature`, `Share`, `ShareMetadata`, `ShareRequest`, `ShareRequestType`, `ShareRequestState`, `Error`), driving port (`Shares`), driven port (`ShareRepository`), and service (`SharesService`). 47 munit tests pass, including live Ed25519 verification round-trips via BouncyCastle.
- **deposplit.com REST API**: the root Play app's adapter layer is fully implemented — `AnormShareRepository` (Anorm + PostgreSQL/H2), `SharesController`, `ShareRequestsController`, `AuthHelper`, `Module` (Guice bindings), and Play routes. The backend implements the **pure relay model**: `shares.ciphertext` is cleared on recipient pickup (`GET /shares/:shareId`); `share_requests.ciphertext` holds the ciphertext the recipient sends back when approving a retrieve request; `shares.picked_up_at` records delivery. `Error.BadRequest` maps to HTTP 400. All 87 tests pass (56 hexagon + 31 root). OpenAPI spec at `conf/openapi.yaml`.
- **Android home screen**: three-tab screen (Distributed / Held / Requests) backed by `HomeViewModel` and `RequestsViewModel`; `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` reloads both ViewModels each time the screen resumes (including on return from sub-screens, where `init` does not re-run because the ViewModel is already alive). Loading, error, and empty states handled per tab.
- **Android API adapter**: `DeposplitApiAdapter` implements all 7 operations (`depositShare`, `listShares`, `deleteShare`, `openShareRequest`, `listShareRequests`, `getShareRequest`, `respondToShareRequest`) via `HttpURLConnection`; Ed25519 request signing with canonical string `nonce\nMETHOD\npath_with_query\nhex(sha256(body))`; `kotlinx.serialization` JSON; base64url for keys, standard base64 for ciphertext. Wired into `DeposplitApp` as `shareTransport`.
- **Android contact management**: `Contact` domain model + `ContactRepository` port interface; `LocalContactRepository` stores contacts as JSON in `filesDir` with `@Synchronized` thread safety; `ContactsScreen` (list + delete per item, FAB navigates to add), `AddContactScreen` (manual pseudonym + Ed25519/X25519 base64url key entry with validation). Contacts icon in `HomeScreen` TopAppBar navigates to the list.
- **Android deposit flow**: `AuthPort.encrypt()` uses X25519+HKDF-SHA-256+ChaCha20-Poly1305 via BouncyCastle; `DepositScreen` / `DepositViewModel` collect label, secret text, contact selection (≥2), and threshold (≥2), call `Shamir.split()` then `auth.encrypt()` per share then `transport.depositShare()` for each recipient. FAB on `HomeScreen` navigates to the deposit screen.
- **Android recipient consent flows**: `RequestsViewModel` polls `listShareRequests(RECIPIENT, PENDING)` and `contactRepository.getAll()` on load; `respond()` calls `respondToShareRequest()` then reloads. `RecipientRequestsTab` shows a per-request card with type badge (Retrieve/Delete), sender pseudonym (looked up by Ed25519 key), and Deny/Approve buttons with per-request in-progress state. Surfaced as a third "Requests" tab in `HomeScreen` alongside Distributed/Held; Refresh button calls the active tab's ViewModel.
- **Android sender-side consent flows**: `AuthPort.decrypt()` uses X25519+HKDF-SHA-256+ChaCha20-Poly1305 via BouncyCastle. `ShareDetailScreen` / `ShareDetailViewModel` opened by tapping a Distributed share: shows recipient name, request state (Pending/Approved/Denied) per type, buttons to open RETRIEVE/DELETE requests (re-open on Denied), and a Reconstruct section (shown when ≥2 approved retrieve shares exist for the secretId) that decrypts each approved ciphertext via `auth.decrypt()`, calls `Shamir.combine()`, and displays the secret.
- **Android QR contact onboarding**: `QrPayload` encodes/decodes `{"v":1,"pseudonym":"...","ed":"...","x":"..."}` (base64url keys, ZXing 3.5.3). `QrDisplayScreen` / `QrDisplayViewModel` generate a 512×512 `QRCodeWriter` bitmap of the user's own public keys on `Dispatchers.Default`; reachable via QR icon in `HomeScreen` TopAppBar. `QrScanScreen` / `QrScanViewModel` use CameraX 1.6.0 `PreviewView` + `ImageAnalysis` with `PlanarYUVLuminanceSource` → `QRCodeReader` per YUV frame; `AtomicBoolean hasScanned` prevents duplicate saves; successfully scanned contacts are saved with `VerificationLevel.VERIFIED`. Scanner reachable via icon in `ContactsScreen` TopAppBar. Runtime CAMERA permission requested on first open.
- **Android hexagon module**: `:app` split into `:hexagon` (pure Kotlin/JVM, `org.jetbrains.kotlin.jvm` plugin, JVM 21) + `:app` (AGP, depends on `:hexagon`). Moved to `:hexagon`: `Shamir`, `AuthPort`, `ShareTransport` (incl. `Role`/`ShareRequestType`/`ShareRequestState`/`ShareMetadata`/`ShareRequest`), `Contact`/`VerificationLevel`/`ContactRepository`, and `ShamirTest`. Adapters (`DeposplitAuthAdapter`, `DeposplitApiAdapter`, `LocalContactRepository`) and all UI stay in `:app`. Package names unchanged. 18 hexagon tests pass; `:app:assembleDebug` green.
- **Android biometric unlock**: `ShareDetailScreen` gates `viewModel.reconstruct()` behind `BiometricPrompt` via a `com.deposplit.ui.biometric.BiometricGate` helper (suspend `authenticate(activity, title, subtitle)` built on `suspendCancellableCoroutine`). Authenticators are `BIOMETRIC_STRONG | DEVICE_CREDENTIAL` on API 30+ and `BIOMETRIC_STRONG` on API 29 (combination unsupported there). Availability is probed on screen entry; `NoneEnrolled`/`NoHardware`/`Unavailable` each render an explanatory message in place of the Reconstruct button. `MainActivity` promoted from `ComponentActivity` to `FragmentActivity` (required by `BiometricPrompt`). Dependency: `androidx.biometric:biometric:1.1.0`; `USE_BIOMETRIC` permission added.
- **Android UI fixes (Apr 2026 end-to-end testing)**: `ContactsScreen` gained a back-navigation arrow (was missing); `QrScanScreen` CameraX listener body wrapped in `runCatching` to prevent main-thread crash when camera is unavailable (e.g., on emulators without camera support); `ContactsScreen` reloads contacts on `ON_RESUME` (same pattern as `HomeScreen`) so newly added contacts appear when returning from `AddContactScreen` or `QrScanScreen`.
- **Backend config fixes (Apr 2026 end-to-end testing)**: `application.conf` AllowedHostsFilter corrected from `"10.0.2.2"` to `"10.0.2.2:9000"` — Play matches the full `Host` header including port for non-standard ports. Base URL in `DeposplitApp` corrected from `http://10.0.2.2:9000/v1` to `http://10.0.2.2:9000` (routes have no `/v1` prefix). `localhost.conf` changed from in-memory H2 (`jdbc:h2:mem:...`) to file-backed H2 (`jdbc:h2:./target/deposplit-dev;...`) so data survives `sbt run` restarts during development.
- **Android relay protocol implementation**: `ShareTransport` port extended with `pickUpShare(shareId: UUID): ByteArray` and `respondToShareRequest(..., ciphertext: ByteArray?)`. `DeposplitApiAdapter` implements `pickUpShare` via `GET /shares/:shareId` and passes ciphertext in the PATCH body on retrieve approve. `HeldShare` value type and `ShareRepository` port added to `:hexagon/api`; `LocalShareRepository` (`:app/shares`) persists ciphertext + metadata as JSON in `filesDir/shares.json`. `HomeViewModel` polls relay inbox on each load, auto-picks up new shares, and reads the Held tab from local storage. `RequestsViewModel.respond()` reads ciphertext from local storage when approving a retrieve request, and deletes the local share on delete approve. `ShareDetailViewModel.reconstruct()` best-effort-deletes relay rows after a successful reconstruction.
- **iOS relay protocol implementation**: Same relay protocol changes as Android, adapted for Swift/CryptoKit. `ShareTransport` protocol extended with `pickUpShare(shareId:)` and `respondToShareRequest(..., ciphertext:)`. `HeldShare` struct and `ShareRepository` protocol added in `shares/HeldShare.swift`; `LocalShareRepository` persists ciphertext + metadata as JSON in `Documents/shares.json`. `HomeViewModel` polls relay inbox on load, auto-picks up new shares, reads Held tab from local storage. `RequestsViewModel.respond()` reads ciphertext from local storage on retrieve approve, deletes local share on delete approve. `ShareDetailViewModel.reconstruct()` best-effort-deletes relay rows after reconstruction.
- **Android home UX improvements**: Home screen tabs renamed to "My Shared Secrets" (was "Distributed") and "Their Secret Shares" (was "Held"). `ShareMetadata` extended with `pickedUpAt: String?` (populated from the relay response). "My Shared Secrets" tab groups shares by `secretId`: one expandable card per logical secret showing per-holder delivery status and retrieve-request state; a "Request Retrieval" button opens requests for all holders in one tap (`HomeViewModel.requestAll`); tapping an individual holder still navigates to `ShareDetailScreen`. "Their Secret Shares" tab shows each held share's label, date, and sender pseudonym (looked up from contacts); three `FilterChip` controls sort the list by date, label, or sender (`HomeViewModel.setHeldSortOrder`). Recipient-initiated deletion: a delete icon on each held share card opens a confirmation dialog with "Delete" (this share only) and, when the same sender has multiple shares, "Delete all shares from [name]" (`HomeViewModel.deleteSingleShare` / `deleteAllFromSender`).

### What is next

1. **iOS biometric unlock**: The Android app gates `reconstruct()` behind `BiometricPrompt`. The iOS `ShareDetailView` currently reconstructs immediately; it should gate via `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` from the `LocalAuthentication` framework.
2. **End-to-end testing**: Test Android ↔ iOS interop (Android deposits a share, iOS recipient approves retrieval, Android reconstructs) against a live `sbt run` backend.

## Build & Test Commands

### deposplit.com/ (Scala + Play + sbt)

```bash
# from deposplit.com/
sbt run          # start the Play dev server (auto-reloads on file change)
sbt run -Dconfig.file=conf/localhost.conf # with the dev config
sbt test         # run all tests (hexagon + root)
sbt compile      # compile without running
sbt hexagon/test # test hexagon subproject only
sbt dist         # produce a production distribution zip
```

### Android/ (Kotlin 2.3, AGP 9.x, JVM 21 bytecode, runs on Java 25+)

```bash
# from Android/
./gradlew assembleDebug  # build debug APK
./gradlew test           # JVM unit tests (no device needed)
./gradlew connectedAndroidTest   # instrumented tests (requires device or emulator)
./gradlew test --tests "com.deposplit.shamir.ShamirTest"  # single test class
```

### iOS/ (Swift Package Manager — Swift 6.2.3)

```bash
# from iOS/
swift build              # compile
swift test               # run all tests
swift test --filter ShamirSecretSharingTests  # single test target
```

> **Note:** Swift on Windows writes to the Windows Console API, so its output is not captured by Git Bash. Run `swift test` from VS Code (Swift extension) or a native Windows terminal (PowerShell / Windows Terminal) instead.
