# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Deposplit** is a secret-sharing app based on **Shamir's Secret Sharing (SSS)**. A secret is split into *n* shares, of which *k* are required to reconstruct the original secret. The app sends each share to one of *n* contacts and later reassembles the secret when at least *k* holders cooperate.

## How We Got Here

Deposplit's architecture evolved through several design sessions:

1. **The Signal question (Feb 2025):** The project started by exploring whether a third-party app could piggyback on Signal — its contacts, groups, and messaging infrastructure. Signal is intentionally an appliance, not a platform: it exposes no SDK or inter-app API.

2. **Matrix adopted as transport (Feb–Mar 2026):** Matrix was identified as the natural fit — designed to be built upon, with Android/iOS SDKs, arbitrary custom message types, E2EE via Double Ratchet, and federation. The SSS libraries (Kotlin and Swift ports of the Privy TypeScript reference) were built and tested. An Android scaffold was completed with a working OIDC sign-in flow (Chrome Custom Tab → matrix.org → deep-link callback).

3. **DCR friction with matrix.org (Mar 2026):** matrix.org's Matrix Authentication Service rejected Deposplit's Dynamic Client Registration attempts (`invalid_redirect_uri`). This triggered a re-evaluation of the transport layer.

4. **Pivot to custom backend (Mar 2026):** Matrix is heavyweight for Deposplit's actual protocol (4 message types). Since recipients must install Deposplit, federation between homeservers adds no user value. A custom deposplit.com backend with libsodium E2EE was chosen: simpler, leaner, and the server provably cannot read share content regardless of breach.

## Architecture Decisions

### Communication Layer: Custom Backend

The transport layer is a **custom deposplit.com REST/WebSocket API** with end-to-end encryption provided by **libsodium** (`crypto_box`: X25519 key agreement + XSalsa20-Poly1305 AEAD, ISC licence).

Key design decisions:
- **User identity is a keypair.** At first launch the device generates an X25519 keypair. The user picks a pseudonym (no email, no phone number required). Registration uploads only the pseudonym and public key to deposplit.com — the private key never leaves the device.
- **Server blindness.** All share content is encrypted to the recipient's public key before leaving the sender's device. The backend stores only ciphertext and cannot read shares regardless of a breach. Reconstructing the original secret requires compromising at least *k* recipients' private keys, which live only on their devices.
- **No federation needed.** Recipients must install Deposplit, so cross-server communication adds no user value. Deposplit operates a single canonical backend at deposplit.com.

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
| `deposplit.com/` | [Deposplit/deposplit.com](https://github.com/Deposplit/deposplit.com) | Project hub, landing page, and cross-project documentation |
| `Android/` | [Deposplit/Android](https://github.com/Deposplit/Android) | Kotlin SSS library + Android app (single `:app` Gradle module) |
| `iOS/` | [Deposplit/iOS](https://github.com/Deposplit/iOS) | Swift SSS library (iOS app not yet scaffolded) |
| `Backend/` | [Deposplit/Backend](https://github.com/Deposplit/Backend) | deposplit.com server (not yet scaffolded) |

### CLAUDE.md Layout

Claude Code discovers `CLAUDE.md` files by walking up the directory tree from the working directory. The cross-project guidance lives here (`deposplit.com/CLAUDE.md`) and is the source of truth. The workspace root (`Deposplit/CLAUDE.md`) contains a single `@`-import that loads it, so launching `claude` from `Deposplit/` automatically picks up the full context. Platform-specific guidance lives in `Android/CLAUDE.md` and `iOS/CLAUDE.md` respectively.

### Cryptography

- Secret splitting: **Shamir's Secret Sharing** (SSS)
- Parameters: *n* total shares, *k*-of-*n* threshold for reconstruction
- Transport encryption: **libsodium** `crypto_box` (X25519 + XSalsa20-Poly1305)

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

#### Transport Encryption: libsodium

libsodium's `crypto_box` is used for all share content encrypted in transit and at rest on the backend:

- **Key agreement**: X25519 (Curve25519 Diffie-Hellman)
- **Encryption**: XSalsa20-Poly1305 (authenticated encryption)
- **Licence**: ISC (permissive)
- **Rationale**: fits the actual threat model (one-shot encrypted payloads between known parties), battle-tested, easy to use correctly, no AGPL encumbrance

Each share is encrypted by the sender to the recipient's public key before leaving the device. The backend stores ciphertext only. A full backend breach yields nothing without also compromising at least *k* recipients' private keys.

#### Implementation Status

The SSS ports are **complete and fully tested**:

| Library | Module | Public API |
|---|---|---|
| `Android/` | `com.deposplit.shamir` | `split(secret: ByteArray, shares: Int, threshold: Int): List<ByteArray>` / `combine(shares: List<ByteArray>): ByteArray` — throws `IllegalArgumentException` |
| `iOS/` | `ShamirSecretSharing` | `split(secret: [UInt8], shares: Int, threshold: Int) throws -> [[UInt8]]` / `combine(shares: [[UInt8]]) throws -> [UInt8]` — throws `ShamirError` |

The libsodium integration (Android + iOS + Backend) is **not yet implemented**.

#### Cross-Platform Compatibility

Both test suites contain three identical hand-derived test vectors (in `ShamirTest.kt` and `ShamirSecretSharingTests.swift`) that verify `combine()` byte-for-byte against the same inputs. The vectors use the polynomial `f(x) = secret_byte + 0x01·x` in GF(2⁸) with x-coordinates `[1, 2]` — the simplest non-trivial 2-of-2 case — and were verified by hand against the GF(2⁸) arithmetic tables.

### Android App

#### Minimum SDK: API 29 (Android 10)

The Android app targets **`minSdk = 29`**, not the Android Studio default of API 24. This was a deliberate choice for a security-sensitive app:

| API | Feature | Relevance |
|---|---|---|
| 28 | **`BiometricPrompt`** (native) | Gate secret reconstruction behind biometric auth |
| 28 | **StrongBox Keymaster** (`setIsStrongBoxBacked(true)`) | Keys stored in dedicated security chip, not just TEE |
| 28 | Cleartext traffic disabled by default | No accidental plaintext traffic to the backend |
| 29 | **Scoped Storage** | Relevant for the file-upload secret input method |
| 29 | **TLS 1.3** enabled by default | Baseline transport security for backend comms |

API 29 still covers >90% of active Android devices, which is acceptable for a niche security app. Do not lower `minSdk` without revisiting these dependencies.

Note: `BiometricPrompt` and StrongBox require runtime capability checks regardless of `minSdk` — `BiometricManager.canAuthenticate()` and `setIsStrongBoxBacked(true)` can throw `StrongBoxUnavailableException` on devices lacking the hardware.

#### Authentication / Registration

Registration is **keypair-first** — no OIDC, no password, no email.

Flow:
1. On first launch the device generates an X25519 keypair via libsodium
2. The user picks a pseudonym (display name only — no personal information required)
3. The app registers with deposplit.com: pseudonym + public key
4. The private key is stored in the Android Keystore and never leaves the device

Session state (the "is registered" flag) is persisted via plain `SharedPreferences`. The private key is managed by the Android Keystore — the app never handles raw key material directly.

Identity *is* the keypair. This integrates directly with the k-of-n social recovery design: if Alice loses her device, she generates a new keypair on a new device and initiates a re-association request that existing contacts approve.

The `MatrixAuthAdapter` (OIDC-based, now obsolete) is to be replaced by a `DeposplitAuthAdapter` implementing the same `AuthPort` interface.

#### UI toolkit: Jetpack Compose + Material 3

Use **Jetpack Compose** (not XML/Views) for all UI. The "Empty Activity" template in Android Studio is the Compose template and is the correct starting point for new app scaffolding.

#### Build toolchain: AGP 9.x + Kotlin 2.x

AGP 9.x integrates Kotlin compilation directly — it registers the `kotlin` Gradle extension itself. Do **not** apply `org.jetbrains.kotlin.android`; doing so causes a "extension already registered" conflict. The Android Studio template deliberately omits it.

Consequences:
- `kotlinOptions { }` is **not available** (it requires `kotlin.android`)
- Set the Kotlin JVM target via `compileOptions` only — AGP 9.x propagates `targetCompatibility` to the Kotlin compiler automatically
- `kotlin.plugin.compose` (the Compose compiler plugin) is still required and does not conflict

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

**Consent model:**
- *Retrieval* — the recipient must approve. This allows out-of-band verification (e.g. a phone call) that the sender genuinely requested reconstruction and is not an attacker who stole their device.
- *Sender-initiated deletion* — the recipient must approve. The sender cannot force deletion.
- *Recipient-initiated deletion* — unilateral, no approval needed.

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
- Android: the domain is a pure Kotlin Gradle module; infrastructure modules depend on it, never the reverse
- iOS: the domain is a plain Swift package (no UIKit/SwiftUI imports); infrastructure in separate packages or targets

### Share Holder Onboarding

Before Alice can include a contact as a share holder, that contact must have Deposplit installed **and** have explicitly accepted a share holder invitation from Alice. Only then do they appear as selectable in the "Split & Share" flow.

**Invitation flow:**
1. Alice adds Bob to her Deposplit contacts
2. Deposplit sends Bob a backend notification explaining what Deposplit is, what holding a share entails, and inviting him to accept or decline
3. If Bob already has Deposplit, his app surfaces the pending invitation immediately
4. If Bob doesn't have Deposplit yet, the invitation waits in his backend inbox; once he installs Deposplit the invitation surfaces automatically
5. Once Bob accepts, he appears as a ready (selectable) holder in Alice's contact list

**Contact states in the "Split & Share" screen:**
- **Ready** — has Deposplit, has accepted Alice's invitation → selectable
- **Pending** — invited but no response yet → shown greyed out/disabled so Alice knows they exist but can't be selected yet
- **Declined / not invited** — not shown, or shown separately

**All n holders must be ready before Alice can split.** There is no queuing of shares for pending holders.

If a ready holder later withdraws consent (deletes Alice's shares and revokes acceptance), they drop back to a non-ready state. Existing distributed shares are unaffected, but Alice cannot include that contact in new splits until they re-accept.

**In-person verification is encouraged but not required** as a prerequisite for holding shares. Verification level is visible when Alice selects contacts, so she can make an informed choice. Unverified contacts can hold shares; verified contacts carry more weight in identity recovery.

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

Deposplit maintains a contact list stored on the deposplit.com backend and cached locally on each device. Each contact is identified by their **X25519 public key**, which is the canonical identity anchor.

Contact lookup:
- **QR code scan (preferred):** encodes the contact's public key + pseudonym directly — no server intermediary, resistant to TOFU attacks
- **Pseudonym search:** the backend resolves a pseudonym to a public key; the user should verify via subsequent QR scan or out-of-band confirmation

Each contact record stores: public key, pseudonym, verification level, date verified.

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
- **Android app scaffold**: Jetpack Compose + Material 3 app with a sign-in screen and Ports & Adapters skeleton (`AuthPort`, `SignInViewModel`, `SignInScreen`, `HomeScreen` placeholder, NavHost). The `MatrixAuthAdapter` (OIDC-based) is present but **obsolete** — it is to be replaced by a `DeposplitAuthAdapter` for keypair-based registration.

### What is next

In rough priority order:

1. **Android**: Replace `MatrixAuthAdapter` with `DeposplitAuthAdapter` — keypair generation via libsodium, pseudonym registration against the deposplit.com API; remove `matrix-rust-sdk` dependency
2. **Backend**: Scaffold the deposplit.com server — user registration (pseudonym + public key), share storage and retrieval (ciphertext only)
3. **Android**: Home screen — list secrets distributed / shares held
4. **Android**: Implement the four backend protocol message types (deposit, list, retrieve, delete)
5. **Android**: Wire `Shamir.split()` / `Shamir.combine()` into the secret distribution flow
6. **Android**: Contact management backed by the deposplit.com contacts API
7. **iOS**: Scaffold the iOS app (SwiftUI + deposplit.com API client)
8. **Android**: Domain module extraction — split `:app` into `:domain` + `:app`

## Build & Test Commands

### Android/ (Kotlin 2.2, AGP 9.x, JVM 17 bytecode, runs on Java 25+)

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
