# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Deposplit** is a secret-sharing app based on **Shamir's Secret Sharing (SSS)**. A secret is split into *n* shares, of which *k* are required to reconstruct the original secret. The app sends each share to one of *n* contacts and later reassembles the secret when at least *k* holders cooperate.

## Architecture Decisions

### Communication Layer: Matrix

The transport and contact management layer is **Matrix** (not Signal, Threema, or similar). Key reasons:
- Matrix is designed to be built upon — it has proper Android and iOS SDKs
- Supports sending arbitrary structured/binary payloads as custom message types
- Provides E2EE via Double Ratchet (same algorithm as Signal), contact/DM management, and group rooms
- The Matrix.org Foundation (non-profit) stewards the protocol; Element (commercial) drives development

**Recipients must install Deposplit.** Generic Matrix clients (e.g., Element) are not supported as share holders. This is required to enable structured share management, automated retrieval, and the consent flows described below.

### Repository Structure

The [Deposplit GitHub organization](https://github.com/Deposplit) contains independent repositories, each cloned into a corresponding subfolder of the local `Deposplit/` workspace (which is itself not a git repository):

| Folder | Repository | Purpose |
|---|---|---|
| `deposplit.com/` | [Deposplit/deposplit.com](https://github.com/Deposplit/deposplit.com) | Project hub, landing page, and cross-project documentation |
| `Android/` | [Deposplit/Android](https://github.com/Deposplit/Android) | Kotlin/JVM SSS library (and future Android app) |
| `iOS/` | [Deposplit/iOS](https://github.com/Deposplit/iOS) | Swift SSS library (and future iOS app) |

Future Android and iOS app subprojects will live in `Android/` and `iOS/` respectively and depend on the existing SSS libraries.

### CLAUDE.md Layout

Claude Code discovers `CLAUDE.md` files by walking up the directory tree from the working directory. The cross-project guidance lives here (`deposplit.com/CLAUDE.md`) and is the source of truth. The workspace root (`Deposplit/CLAUDE.md`) contains a single `@`-import that loads it, so launching `claude` from `Deposplit/` automatically picks up the full context. Platform-specific guidance lives in `Android/CLAUDE.md` and `iOS/CLAUDE.md` respectively.

### Cryptography

- Secret splitting: **Shamir's Secret Sharing** (SSS)
- Parameters: *n* total shares, *k*-of-*n* threshold for reconstruction
- Transport encryption: provided by Matrix (Double Ratchet / E2EE)

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

#### Implementation Status

Both ports are **complete and fully tested**:

| Library | Module | Public API |
|---|---|---|
| `Android/` | `com.deposplit.shamir` | `split(secret: ByteArray, shares: Int, threshold: Int): List<ByteArray>` / `combine(shares: List<ByteArray>): ByteArray` — throws `IllegalArgumentException` |
| `iOS/` | `ShamirSecretSharing` | `split(secret: [UInt8], shares: Int, threshold: Int) throws -> [[UInt8]]` / `combine(shares: [[UInt8]]) throws -> [UInt8]` — throws `ShamirError` |

#### Cross-Platform Compatibility

Both test suites contain three identical hand-derived test vectors (in `ShamirTest.kt` and `ShamirSecretSharingTests.swift`) that verify `combine()` byte-for-byte against the same inputs. The vectors use the polynomial `f(x) = secret_byte + 0x01·x` in GF(2⁸) with x-coordinates `[1, 2]` — the simplest non-trivial 2-of-2 case — and were verified by hand against the GF(2⁸) arithmetic tables.

### App Protocol

Secrets are identified by a **UUID** generated at split time. The human-readable label (e.g. "BitLocker key") is display-only metadata — two secrets with the same label are distinguished by their UUIDs.

There are four Matrix message types:

| # | Direction | Payload | Purpose |
|---|---|---|---|
| 1 | Sender → recipient | `secret_id` (UUID), `label`, `created_at`, share bytes | **Deposit** a share with a recipient |
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
Each infrastructure concern is a separate adapter: Matrix SDK, OS keychain, camera, file picker, NFC, document scanner, cloud storage picker. Swapping or adding an adapter never touches the domain.

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
2. Deposplit sends Bob a Matrix message explaining what Deposplit is, what holding a share entails, and inviting him to accept or decline
3. If Bob already has Deposplit, his app surfaces the pending invitation immediately
4. If Bob doesn't have Deposplit yet, the message waits in his Matrix inbox; once he installs Deposplit the invitation surfaces automatically
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

Matrix has no native address book. The closest built-in primitive is the **`m.direct` account data event** — a `{ userId → [roomId, …] }` map stored on the homeserver that marks certain rooms as DMs. It syncs to all devices automatically via the Matrix sync API.

Deposplit maintains its own curated contact list stored as a **`com.deposplit.contacts` custom account data event** on the homeserver (also synced automatically). The flow:

1. When adding a contact, Deposplit reads `m.direct` and presents those Matrix users as candidates
2. The user selects a subset → written to `com.deposplit.contacts`
3. All Deposplit features (deposit, list, retrieve, delete, identity recovery) show only this curated list — never the full Matrix DM list

Each contact record stores: Matrix ID, display name (may override Matrix profile), verification level, date verified.

Adding a contact is the natural moment to prompt for in-person QR verification.

### Contact Verification

Deposplit uses a two-level verification model inspired by Threema:

| Level | How achieved | Meaning |
|---|---|---|
| **Unverified** | Contact added remotely (by Matrix ID, invite link, etc.) | "I believe this Matrix account belongs to this person, but I haven't confirmed it" |
| **Verified** | QR code scanned in person | "I was physically with this person and confirmed their Matrix account is theirs" |

The in-person QR scan encodes the contact's Matrix ID (and optionally a fingerprint of their Matrix device keys). Verification level is stored per contact and is visible to the user when reviewing share holders or approving requests.

### Identity Recovery

If Alice loses her phone and cannot recover her Matrix account, she creates a new account and initiates a **re-association request**: "please map my new Matrix ID to my old one."

Recovery uses **social recovery (k-of-n)**: the same threshold k used when the secret was split must approve the re-association before it takes effect. Verification level influences the trust calculus:
- Approval from a **verified** contact (in-person QR scan) carries stronger assurance than approval from an unverified one
- A single verified approver may be considered sufficient; the exact rule is TBD

Recipients who approve a re-association should be encouraged to verify Alice again in person (re-scan her new QR code) to restore the verified relationship.

## Build & Test Commands

### Android/ (Kotlin/JVM library — Gradle 9.3.1, Kotlin 2.2, JVM 17 bytecode, runs on Java 25)

```bash
# from Android/
./gradlew build          # compile + test
./gradlew test           # run tests only
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
