# Deposplit

A secret-sharing app based on **Shamir's Secret Sharing (SSS)**. A secret is split into *n* shares, of which *k* are required to reconstruct it. Each share is sent to one of *n* contacts via the **deposplit.com Web app/service**; the secret is reassembled when at least *k* holders cooperate.

## Repository structure

The [Deposplit GitHub organization](https://github.com/Deposplit) contains independent repositories, each cloned into a corresponding subfolder of the local `Deposplit/` workspace (which is itself not a git repository):

| Folder | Repository | Purpose |
|---|---|---|
| `deposplit.com/` | [Deposplit/deposplit.com](https://github.com/Deposplit/deposplit.com) | Project hub, landing page, cross-project documentation, and Web app/service server |
| `Android/` | [Deposplit/Android](https://github.com/Deposplit/Android) | Kotlin SSS library + Android app |
| `iOS/` | [Deposplit/iOS](https://github.com/Deposplit/iOS) | Swift SSS library + iOS app (SwiftUI, iOS 26+) |

## CLAUDE.md layout

Claude Code discovers `CLAUDE.md` files by walking up the directory tree from the working directory. The cross-project guidance lives in this repo (`deposplit.com/CLAUDE.md`) and is the source of truth. The workspace root (`Deposplit/CLAUDE.md`) contains a single `@`-import that loads it, so launching `claude` from `Deposplit/` automatically picks up the full context. Platform-specific guidance lives in `Android/CLAUDE.md` and `iOS/CLAUDE.md` respectively.

## Why a custom Web app/service?

Deposplit's protocol consists of exactly four message types (deposit / list / retrieval / removal). A dedicated deposplit.com REST API with end-to-end encryption (BouncyCastle on Android/JVM, Swift Crypto on iOS) is the right fit:

- **Server is cryptographically blind.** Share content is encrypted on the sender's device to the recipient's X25519 public key before it ever leaves the device. The Web app/service stores and forwards ciphertext only. The recipient decrypts to plaintext locally at pickup and re-encrypts fresh at retrieval, so a full server breach yields nothing — it would need to compromise *k* holders' devices or defeat *k* holders' retrieval-consent instead.
- **No federation needed.** Recipients must install Deposplit anyway, so cross-server communication adds no user value.
- **Lean.** The protocol needs four message types; heavier transports (Matrix, XMPP) bring megabytes of SDK for features Deposplit does not use.
- **Share encryption uses X25519 + HKDF-SHA-256 + ChaCha20-Poly1305** — one-shot encrypted payloads between known parties. Implemented with BouncyCastle on Android/JVM and Swift Crypto on iOS; no native libsodium.

Rejected alternatives:

| Option | Reason rejected |
|---|---|
| Matrix | Heavyweight for a 4-message protocol; matrix.org DCR restrictions create friction; federation adds no value since recipients must install Deposplit |
| XMPP + OMEMO | Older, more fragmented ecosystem; weaker mobile SDKs |
| Signal Protocol (libsignal) | AGPL-3.0 — incompatible with a more permissive app licence; Double Ratchet is designed for continuous conversations; Deposplit's one-shot deposits do not benefit from per-message key ratcheting |
| Nostr | NIP-44 E2EE is newer and less battle-tested; relay reliability varies |
| P2P (WebRTC, Bluetooth, DHT) | Async delivery requires persistent storage; true P2P cannot reliably hold shares for offline recipients over days or months |

## Web App/Service tech stack

| Concern | Choice | Notes |
|---|---|---|
| Language / framework | Scala + Play 3 | sbt build; `hexagon` subproject (pure Scala, no Play) + root Play app (adapters, controllers, Twirl views) |
| Database | PostgreSQL | Relational data model with FK constraints and ACID transactions; `bytea` for opaque share ciphertext; native UUID type for `secret_id`; row-level security as defense-in-depth |
| DB access | Anorm | SQL-first, minimal abstraction; fits cleanly in the adapter layer. Slick is an acceptable alternative. |
| DB schema | Play Evolutions (`conf/evolutions/default/1.sql`) | `share_requests` (four transaction types: `deposit`, `retrieval`, `removal`, `inventory`) + `key_rotations` (item 9's signed rotation push — no consent phase, no `secretId`, so it doesn't fit `share_requests`' shape) |
| Dev / test DB | H2 (file-backed, `./.devDBs/deposplit`) | No PostgreSQL instance required locally; data persists across `sbt run` restarts |
| API spec | OpenAPI 3.0 (`conf/openapi.yaml`) | |
| API serialisation | Play JSON (`play-json`) | Bundled with Play; no explicit dependency needed |
| Landing page templating | Twirl (built into Play) | |
| Ed25519 verification | BouncyCastle (`hexagon` subproject) | Signature verification is a domain concern; no libsodium on the server — share content is forwarded as opaque bytes |

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

Three of the four request types are modelled as consent requests — Alice requests something of Bob; Bob can approve or deny. The fourth is a holder-initiated push with no consent phase. All four live in one table, `share_requests`:

| Type | Direction | Description |
|---|---|---|
| `deposit` | Sender → recipient | Alice deposits a share (ciphertext included); Bob approves to receive it and the relay delivers ciphertext once, then clears it |
| `retrieval` | Sender ↔ recipient | Alice requests a specific share back; Bob approves (sends ciphertext from local storage) or denies |
| `removal` | Sender ↔ recipient | Alice requests removal of a share; Bob approves or denies |
| `inventory` | Holder → owner | Bob pushes a metadata-only report about a share of his back to Alice, so she can rebuild her records after losing her old identity's key; self-approves at creation, no consent phase |

Recipient-initiated deletion is unilateral — no approval required — but as of item 9 (relay implemented, not yet wired into any client) it is no longer purely silent: `POST /share-requests/withdraw` flips the holder's now-cleared deposit row to a `withdrawn` tombstone instead of deleting it, so the sender's next poll can catch the withdrawal as a best-effort, fire-and-forget courtesy — never authoritative, since the relay may still garbage-collect the row.

Item 9 also adds a **signed key-rotation push** — deliberately *not* a fifth `share_requests` transaction type (no `secretId`, no consent phase), so it lives in its own small `key_rotations` table (`POST`/`GET /key-rotations`, `DELETE /key-rotations/{id}`): a holder proactively tells a contact "I am now this key, previously that key," signed by the old key to prove continuity. See `CLAUDE.md` "What is next" item 9 for the full design and `TODO.md` for implementation status.

**Consent model:**
- *Deposit* — recipient must approve to receive the share. The relay holds the ciphertext until Bob approves; it is delivered once and then cleared from the relay.
- *Retrieval* — recipient must approve. Allows out-of-band verification (e.g. a phone call) before returning a share, protecting against an attacker who has stolen the sender's device.
- *Sender-initiated deletion* — recipient must approve. The sender cannot force deletion.
- *Recipient-initiated deletion* — unilateral.

**Notifications (v0.1):** clients poll for pending events on app open and periodically while foregrounded. There is no WebSocket or push channel. Background push via FCM/APNs is deferred.

The full REST API is specified in `conf/openapi.yaml` (OpenAPI 3.0).

## App architecture: Ports & Adapters (Hexagonal)

Both apps follow the **Ports & Adapters** pattern, applied strictly to the domain and infrastructure layers. The UI layer uses MVVM/MVI as is conventional on each platform.

**Domain (the hexagon core)** — pure business logic: split/combine rules, share holder state machine, contact management, identity recovery. No framework imports. Lives in a plain Kotlin module (Android) or plain Swift package (iOS); tested with fast, framework-free unit tests.

**Ports** — interfaces defined by the domain for everything it needs from outside: secrets store, share transport, contact repository, notification service, etc.

**Adapters** — implement the ports for specific infrastructure: deposplit.com API client, OS keychain, camera, file picker, NFC, document scanner, cloud storage. Swapping or adding an adapter never touches the domain.

**UI layer** — Compose (Android) / SwiftUI (iOS) with ViewModels at the boundary. Treated separately from the hexagon; Compose/SwiftUI's reactive model doesn't map cleanly to port/adapter shapes and the ceremony isn't justified there. Navigation is also left as a platform concern.

**Structural enforcement:**
- Android: the hexagon is a pure Kotlin Gradle module (`:hexagon`) — infrastructure modules depend on it, never the reverse. Driving ports (`Identity`, `ContactManagement`, `ShareManagement`) are implemented by hexagon services (`IdentityService`, `ContactService`, `ShareService`). `ShareEncryption` is an intra-hexagon service interface — both its consumer (`ShareService`) and implementer (`IdentityService`) are inside the hexagon. Driven ports (`IdentityStore`, `ContactRepository`, `ShareRepository`, `ShareRelay`) are implemented by adapters in `:app`.
- iOS: the hexagon is a local Swift Package (`iOS/hexagon/`) — the compiler enforces the boundary; the package has no `Security`, `UIKit`, `SwiftUI`, or `URLSession` dependencies so any accidental import is a build error. Driving ports and hexagon services mirror the Android structure.

## Share holder onboarding

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

All n holders must be ready (keys exchanged) before Alice can split — no queuing of shares for contacts not yet added.

If a holder later withdraws consent, they do so by deleting Alice's shares locally (recipient-initiated deletion). Existing distributed shares are unaffected.

In-person QR verification is the preferred key exchange method — it is the only method that eliminates TOFU risk. Verification level is visible when selecting contacts and carries weight in identity recovery.

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

Deposplit maintains a contact list stored **exclusively on the device** — the Web app/service never stores or indexes user identities or contact relationships.

Each contact is identified by their **Ed25519 public key** (routing identity on the Web app/service) and **X25519 public key** (share encryption). Both are obtained out-of-band before Alice can deposit shares for a contact.

Contact addition:
- **QR code scan (preferred):** encodes both public keys + pseudonym directly — no server intermediary, eliminates TOFU risk
- **Out-of-band link:** a shareable link carrying both public keys, sent via Signal, Threema, email, etc.

Each contact record stores: Ed25519 public key, X25519 public key, pseudonym, verification level, date verified. All stored locally on the device. Adding a contact is the natural moment to prompt for in-person QR verification.

## Contact verification

Deposplit uses a **four-level ordinal model** (supersedes the original two-level, Threema-inspired one), derived from a trusted-channel × proof-of-life lattice — the order is simply the count of independent assurances present:

| Level | Assurances | How achieved | Meaning |
|---|---|---|---|
| **Very Low** | 0 | Contact added remotely with no live check (e-mail, LinkedIn, a business card) | "I believe this account belongs to this person, but I haven't confirmed it" |
| **Low** | 1 | *Either* a trusted channel *or* live proof, not both | One independent assurance |
| **High** | 2 | Both a trusted channel *and* live proof (e.g. a verified-safety-number video call, showing their QR) | Two independent assurances |
| **Very High** | in-person | QR code scanned in person | "I was physically with this person and confirmed their public key is theirs" |

Levels are user-asserted context labels, not cryptographic facts — the app can't distinguish an e-mailed key from a video-shown one, so the UI lets the user pick a level. Manual key entry only offers `Very Low`/`Low`/`High`; `Very High` is reserved for the in-person QR scan flow (which defaults to it), since physical co-presence can't be asserted by typing a key in by hand. Verification level is stored per contact and visible when reviewing share holders or approving requests.

## Identity recovery

If Alice loses her phone and cannot recover her private key, she generates a new keypair on a new device and sends a re-association request to her recipients: "please map my new public key to my old one."

Recovery requires **k-of-n social approval** — the same threshold k as the original secret split. Verification level informs the trust decision: a single recipient at a high verification level approving the request carries stronger assurance than multiple approvals at a lower level. The exact quorum rule is TBD.

Once re-associated, recipients are encouraged to re-scan Alice's QR code in person to restore the verified relationship.

## Continuous Integration

Each repo (`deposplit.com/`, `Android/`, `iOS/`) has its own `.github/workflows/test.yml` that runs the platform's test suite — `sbt test`, `./gradlew test`, and `swift test` against the `iOS/hexagon` Swift package, respectively — on every push and on pull requests targeting `main`. `.github/dependabot.yml` keeps GitHub Actions, Gradle, and Swift Package Manager dependencies current on a weekly schedule.
