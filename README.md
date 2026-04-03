# Deposplit

A secret-sharing app based on **Shamir's Secret Sharing (SSS)**. A secret is split into *n* shares, of which *k* are required to reconstruct it. Each share is sent to one of *n* contacts via the **deposplit.com backend**; the secret is reassembled when at least *k* holders cooperate.

## Repository structure

The [Deposplit GitHub organization](https://github.com/Deposplit) contains independent repositories, each cloned into a corresponding subfolder of the local `Deposplit/` workspace (which is itself not a git repository):

| Folder | Repository | Purpose |
|---|---|---|
| `deposplit.com/` | [Deposplit/deposplit.com](https://github.com/Deposplit/deposplit.com) | Project hub, landing page, cross-project documentation, and backend server |
| `Android/` | [Deposplit/Android](https://github.com/Deposplit/Android) | Kotlin SSS library + Android app |
| `iOS/` | [Deposplit/iOS](https://github.com/Deposplit/iOS) | Swift SSS library (iOS app not yet scaffolded) |

## CLAUDE.md layout

Claude Code discovers `CLAUDE.md` files by walking up the directory tree from the working directory. The cross-project guidance lives in this repo (`deposplit.com/CLAUDE.md`) and is the source of truth. The workspace root (`Deposplit/CLAUDE.md`) contains a single `@`-import that loads it, so launching `claude` from `Deposplit/` automatically picks up the full context. Platform-specific guidance lives in `Android/CLAUDE.md` and `iOS/CLAUDE.md` respectively.

## Why a custom backend?

Deposplit's protocol consists of exactly four message types (deposit / list / retrieve / delete). A dedicated deposplit.com REST/WebSocket API with libsodium end-to-end encryption is the right fit:

- **Server is cryptographically blind.** Share content is encrypted on the sender's device to the recipient's X25519 public key before it ever leaves the device. The backend stores and forwards ciphertext only — a full server breach yields nothing without also compromising at least *k* recipients' private keys.
- **No federation needed.** Recipients must install Deposplit anyway, so cross-server communication adds no user value.
- **Lean.** The protocol needs four message types; heavier transports (Matrix, XMPP) bring megabytes of SDK for features Deposplit does not use.
- **libsodium** (`crypto_box`: X25519 + XSalsa20-Poly1305, ISC licence) fits the actual threat model — one-shot encrypted payloads between known parties — and is easy to use correctly.

Rejected alternatives:

| Option | Reason rejected |
|---|---|
| Matrix | Heavyweight for a 4-message protocol; matrix.org DCR restrictions create friction; federation adds no value since recipients must install Deposplit |
| XMPP + OMEMO | Older, more fragmented ecosystem; weaker mobile SDKs |
| Signal Protocol (libsignal) | AGPL-3.0 — incompatible with a more permissive app licence; Double Ratchet is designed for continuous conversations; Deposplit's one-shot deposits do not benefit from per-message key ratcheting |
| Nostr | NIP-44 E2EE is newer and less battle-tested; relay reliability varies |
| P2P (WebRTC, Bluetooth, DHT) | Async delivery requires persistent storage; true P2P cannot reliably hold shares for offline recipients over days or months |

## Why native apps, not a web app?

For Deposplit specifically, the native-vs-web trade-off breaks down as follows.

**Where a web app works well**

- Splitting a secret and distributing shares is a one-shot interactive action — no background process needed, fits a web tab naturally.
- Initiating reconstruction is similarly session-bound.
- The SSS reference implementation ([privy-io/shamir-secret-sharing](https://github.com/privy-io/shamir-secret-sharing)) is already TypeScript, so there would be zero porting effort for the crypto layer.

**Where a web app falls short**

- *Persistent key storage*: Private keys live in IndexedDB on the web, which users routinely clear. Native apps write to the OS keychain/secure enclave. An app where losing your keys means losing access to your secret is a poor fit for ephemeral browser storage.
- *Background reception*: If Deposplit needs to receive an incoming reconstruction request while the app isn't open, a browser tab can't do that. Service workers help but are fragile and limited on mobile.
- *Security posture*: Browser-based crypto is exposed to XSS, malicious extensions, and the shared JS execution environment — a meaningful concern for a secret-splitting app. Native apps benefit from OS-level process isolation.
- *Mobile UX*: A PWA for something security-critical is a notably worse experience than a native app with biometric unlock, background tasks, and keychain integration.

**Practical breakdown by scenario**

| Scenario | Web viable? |
|---|---|
| Desktop "coordinator" — split a secret once, no background operation needed | Yes |
| Mobile share *holder* — must receive and respond to requests | No (native strongly preferred) |
| Long-lived secret vault requiring persistent key storage | No |
| One-off secret distribution (e.g., send a password to 3 colleagues) | Yes |

The architectural sweet spot is native apps for the persistent/receiver role, with a possible lightweight web tool for one-shot split-and-send flows — similar to how tools like Bitwarden Send have web tooling for simple operations but recommend native clients for vault management.

## Share holder experience

**Recipients must install Deposplit.** This enables:

- Structured share storage — the recipient's app organises shares by sender and label
- Automated retrieval — the app can respond to a reconstruction request without the human needing to locate the right share manually
- A consent model that makes accidental or malicious disclosure harder

## Protocol

Secrets are identified by a **UUID** generated at split time. The human-readable label (e.g. "BitLocker key") is display-only metadata.

| Message | Direction | Description |
|---|---|---|
| **Deposit** | Sender → recipient | Delivers a share with its `secret_id`, `label`, and `created_at` |
| **List** | Sender ↔ recipient | Sender asks "what shares do you hold for me?"; recipient replies with `{secret_id, label, created_at}` entries — no share bytes |
| **Retrieve** | Sender ↔ recipient | Sender requests a specific `secret_id`; recipient approves or denies, then sends share bytes or a rejection |
| **Delete** (sender-initiated) | Sender ↔ recipient | Sender requests deletion of a share; recipient approves or denies |

Recipient-initiated deletion is purely local — no message required. The recipient can delete individual shares or all shares from a given sender at any time without approval.

**Consent model:**
- *Retrieval* — recipient must approve. Allows out-of-band verification (e.g. a phone call) before returning a share, protecting against an attacker who has stolen the sender's device.
- *Sender-initiated deletion* — recipient must approve. The sender cannot force deletion.
- *Recipient-initiated deletion* — unilateral.

## App architecture: Ports & Adapters (Hexagonal)

Both apps follow the **Ports & Adapters** pattern, applied strictly to the domain and infrastructure layers. The UI layer uses MVVM/MVI as is conventional on each platform.

**Domain (the hexagon core)** — pure business logic: split/combine rules, share holder state machine, contact management, identity recovery. No framework imports. Lives in a plain Kotlin module (Android) or plain Swift package (iOS); tested with fast, framework-free unit tests.

**Ports** — interfaces defined by the domain for everything it needs from outside: secrets store, share transport, contact repository, notification service, etc.

**Adapters** — implement the ports for specific infrastructure: deposplit.com API client, OS keychain, camera, file picker, NFC, document scanner, cloud storage. Swapping or adding an adapter never touches the domain.

**UI layer** — Compose (Android) / SwiftUI (iOS) with ViewModels at the boundary. Treated separately from the hexagon; Compose/SwiftUI's reactive model doesn't map cleanly to port/adapter shapes and the ceremony isn't justified there. Navigation is also left as a platform concern.

**Structural enforcement:**
- Android: domain is a pure Kotlin Gradle module — infrastructure modules depend on it, never the reverse
- iOS: domain is a plain Swift package with no UIKit/SwiftUI imports

## Share holder onboarding

Before Alice can include a contact as a share holder, that contact must have Deposplit installed and have explicitly accepted a share holder invitation from Alice.

**Invitation flow:**
1. Alice adds Bob to her Deposplit contacts
2. Deposplit sends Bob a backend notification explaining Deposplit, what holding a share means, and inviting him to accept or decline
3. If Bob already has Deposplit, his app surfaces the invitation immediately; if not, it waits in his backend inbox and surfaces once he installs Deposplit
4. Once Bob accepts, he appears as ready in Alice's contact list

**Contact states in the "Split & Share" screen:**

| State | Condition | Selectable? |
|---|---|---|
| **Ready** | Has Deposplit, accepted Alice's invitation | Yes |
| **Pending** | Invited, no response yet | No — shown greyed out so Alice knows they haven't been forgotten |
| **Declined / not invited** | — | Not shown (or shown separately) |

All n holders must be ready before Alice can split — no queuing of shares for pending holders.

If a ready holder later withdraws consent, they drop back to non-ready. Existing distributed shares are unaffected; Alice cannot include them in new splits until they re-accept.

In-person verification is encouraged but not required to hold shares. Verification level is visible when selecting contacts and carries weight in identity recovery.

## Secret input methods

Deposplit supports multiple ways for Alice to introduce a secret. Not all are planned for v1.

| Method | Notes | Priority |
|---|---|---|
| **Type / paste** | Text field or text area | v1 |
| **File upload** | Small files; content treated as raw bytes | v1 |
| **QR code scan** | Decode a QR directly (2FA seeds, crypto keys, WiFi passwords, recovery codes) | v1 |
| **Share sheet / intent** | iOS Share Extension / Android intent — other apps push content to Deposplit directly | v1 |
| **Take a photo** | Raw image treated as the secret | v2 |
| **Document scanner + OCR** | Scan a printed sheet (e.g. a printed recovery key); native OS APIs on both platforms | v2 |
| **Cloud storage picker** | iCloud Drive, Google Drive, Dropbox, etc. via the native OS file picker | v2 |
| **NFC tag read** | Read a secret stored on an NFC tag | later |
| **Voice / dictation** | **Not planned** — mic access and speech-recognition services are an unacceptable attack surface | — |

## Contacts management

Deposplit maintains a contact list stored on the deposplit.com backend and cached locally on each device. Each contact is identified by their **X25519 public key**, which is the canonical identity anchor.

Contact lookup:
- **QR code scan (preferred):** encodes the contact's public key + pseudonym directly — no server intermediary, resistant to TOFU attacks
- **Pseudonym search:** the backend resolves a pseudonym to a public key; the user should verify via subsequent QR scan or out-of-band confirmation

Each contact record stores: public key, pseudonym, verification level, date verified. Adding a contact is the natural moment to prompt for in-person QR verification.

## Contact verification

Deposplit uses a two-level model inspired by Threema:

| Level | How achieved | Meaning |
|---|---|---|
| **Unverified** | Contact added remotely (by pseudonym, invite link, etc.) | "I believe this account belongs to this person" |
| **Verified** | QR code scanned in person | "I was physically with this person and confirmed their public key is theirs" |

Verification level is stored per contact and visible when reviewing share holders or approving requests.

## Identity recovery

If Alice loses her phone and cannot recover her private key, she generates a new keypair on a new device and sends a re-association request to her recipients: "please map my new public key to my old one."

Recovery requires **k-of-n social approval** — the same threshold k as the original secret split. Verification level informs the trust decision: a single in-person-verified recipient approving the request carries stronger assurance than multiple unverified approvals. The exact quorum rule is TBD.

Once re-associated, recipients are encouraged to re-scan Alice's QR code in person to restore the verified relationship.
