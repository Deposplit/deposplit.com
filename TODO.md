# Deposplit — open work

What is left, across all three repositories. One board rather than three, because the work
fans out across them and a solo developer plus an agent is better served by a single list.

Shipped work is not recorded here. What the system does and why is documented in
[`docs/`](docs/); how it got there is in the git history.

**Status:** `[ ]` not started · `[~]` in progress · `[-]` parked
**Scope:** `R` relay/backend · `phon` phone emulator · `A` Android · `I` iOS · `doc` documentation

---

## End-to-end interop testing

Nothing here is blocked; it needs devices and an hour. Flows are written up in
[docs/testing.md](docs/testing.md).

- [ ] `A` `I` Android deposits → iOS approves → retrieval → Android reconstructs, against a live `sbt run`
- [ ] `A` `I` BYOR variant: two relays on different ports, one contact with a `relayBaseUrl` override, one without — verify routing and independent soft-failure
- [ ] `A` `I` reconstruction integrity with a surplus holder: confirm the advisory reports the margin honestly

## Persist the secret's MIME type

`Secret` treats the payload as opaque bytes with no content type; the only hint is the
free-text `label`. Both apps force-decode reconstructed secrets as UTF-8 — iOS falls back
to base64, **Android has no fallback at all** and would visibly mangle a binary secret
today. This is the data-model prerequisite for supporting anything but text.

A free MIME string on `Secret`, defaulting to `"text/plain"`, threaded through the same
journey as `k`/`n`: sender record → deposit payload → holder's `HeldShare` → `inventory`
recovery push. No sniffing or validation against the actual bytes — sender-supplied and
best-effort, the same trust level `label` already has.

- [ ] `R` new `mime_type` column on `share_requests` (edit `1.sql` in place)
- [ ] `R` `PayloadCanonical.forOpen` gains `mimeType` **appended at the tail** — append-only, see [docs/protocol.md](docs/protocol.md)
- [ ] `R` `ShareRequestsService` and the Anorm repository carry it through; `conf/openapi.yaml` updated
- [ ] `R` `phon` `A` `I` cross-platform `forOpen` vector tests updated in lockstep
- [ ] `phon` `A` `I` `Secret` gains `mimeType`; `deposit()` gains an optional trailing param defaulting to `"text/plain"`
- [ ] `phon` `A` `I` `HeldShare` gains `mimeType` alongside `k`/`n`
- [ ] `phon` `A` `I` `inventory` payload gains `mimeType`, so recovery restores it
- [ ] `A` `I` reconstruct render fork: `text/*` unchanged; `image/*` via the platform's own sandboxed decoder only; anything else or a decode failure → generic "binary data" view with export, never a crash
- [ ] `doc` note in the render fork that a mismatched or malicious `mimeType` is a rendering-only risk, never a confidentiality one, and must fail safe onto the binary view

## Support more secret types — pictures and audio

Placeholder for the actual input and rendering work once the MIME groundwork above is in.
The "secret input methods" already contemplated: file upload, QR-code scan of a secret,
share-sheet/intent handler, photo capture, document scanner with OCR, cloud-storage picker,
NFC. Voice dictation is **not** planned — microphone access and speech-recognition services
are an unacceptable attack surface for this app.

- [ ] `A` `I` decide which input methods land first, and their per-platform APIs
- [ ] `A` `I` image capture/selection end to end, including size limits
- [ ] `A` `I` audio file selection end to end
- [ ] `doc` update [docs/security.md](docs/security.md) if larger payloads change any assumption

## Freemium one-time unlock

A single one-time in-app purchase removing the deposit cap and unlocking sender-side BYOR.
Enforcement is client-side only and therefore honour-system by design — the relay never
learns payment status. Keep the paywall light-touch.

Free: up to *n* **`ACTIVE`** secrets (not lifetime deposits, so discarding frees a slot
immediately and a re-split never double-counts), via deposplit.com only. Premium: unlimited,
plus per-contact relay overrides for outgoing shares. Accepting shares from someone else's
relay is always free — that traffic never touches deposplit.com, and charging a custodian
for the sender's choice would be backwards.

- [ ] `A` `I` `PurchaseRepository` port (`isPremium()`, `secretsDepositedCount()`)
- [ ] `A` `I` deposit-flow limit check; gate sender-side relay override on `isPremium()`
- [ ] `I` StoreKit 2 adapter
- [ ] `A` Google Play Billing adapter
- [ ] `A` `I` paywall screen, on free-cap hit or on a free user configuring an override
- [ ] `A` `I` free cap counts `ACTIVE` secrets only — enforce against `Secret` state
- [ ] `A` `I` **debug-only fake-Premium `PurchaseRepository`.** Gating the Settings relay editor removes the only way to point a dev build at a local relay. Mirror Android's existing `SKIP_BIOMETRIC` pattern: a `local.properties`/`BuildConfig` flag, real enforcement always on in release.

## Chores

- [ ] `I` **Verify the pickup reorder on a Mac.** `syncInbox` now decrypts and stores before it approves, so a failed pickup no longer consumes the relay's only copy of the ciphertext. Android and phon are verified; the Swift was written by mirroring the verified Kotlin and has never been compiled, because the Windows development machine has no Swift toolchain. Run `swift build` and `swift test` from `iOS/hexagon/` and confirm `syncInboxLeavesADepositPendingWhenTheShareCannotBeDecrypted` passes — and that it goes red if the old respond-then-decrypt order is put back, which is how the Android and phon equivalents were checked.
- [ ] `doc` arrows overlap in the C4 system-context and container diagrams in [docs/architecture.md](docs/architecture.md). Cosmetic, deliberately deferred. Mermaid's C4 renderer offers little layout control — `UpdateLayoutConfig` with `$c4ShapeInRow`/`$c4BoundaryInRow` is the usual lever, and converting a diagram to a styled `flowchart` gives full control at the cost of the C4 shape vocabulary.

## Parked

Not rejected — waiting on something.

- [-] `R` `A` `I` **Airtable / Google Sheets relay kinds.** Wanted, but gated on the default relay demonstrating real adoption. Each non-REST kind needs its own adapter and wire shape, plus a `relayKind` discriminator on `Contact`; that is speculative surface area until people are actually using Deposplit. No design work before then.
- [-] `A` `I` **Holder rotates keys mid-flight.** If a holder rotates between a deposit and their pickup, the ciphertext is encrypted to a key whose private half they no longer hold, and decryption fails. This is about key *version*, not algorithm. No longer a data-loss item: pickup now stores before it approves, so a failed decrypt leaves the deposit pending and retrying, and the sender sees a holder who never confirms rather than one who confirmed and then went quiet. What is left is the remedy. Today it is `reconstruct()` + re-deposit through Repair, which needs *k* live holders; the cheaper candidate is `activateKeyPair` keeping the previous `decKey` one generation deep and trying it only on a decrypt failure, which closes the realistic window without a full keyring. That touches `IdentityStore` on both platforms and means an old private key lingering at rest, so it is a security decision rather than a refactor — hence still parked. Related: `regenerateIdentity` drains the inbox before rotating, but best-effort and silently, so a drain that fails takes the decision away from the user.
- [-] `R` `phon` `A` `I` **Relay row TTL.** Two retention classes — generous for consent-gated action requests, short and latest-wins for fire-and-forget pushes. No TTL or collection job exists; this is deployment configuration, not application logic, and correctness never depends on it. See "absence is never a signal" in [docs/protocol.md](docs/protocol.md). **Collection cannot land alone.** A `deposit` row carries the only copy of a share in transit, and the sender's retained blob is the prerequisite for surviving its collection — but nothing re-deposits from that blob today, so shipping a collection job without the client half would introduce exactly the loss the retention exists to prevent. The client half is bigger than it looks: a re-deposit mints a fresh request id relay-side, while the blob's `id` *is* the original request id — the key `ShareMetadata`, `syncDistributed` and `isRetentionStillPending` all join on — so both local records need re-keying. It need not detect collection, though. Attempt the re-deposit unconditionally and let `hasActiveDeposit` answer `Conflict` while the row is still there, which keeps "absence is never a signal" intact.
- [-] `A` **Android lint in CI.** `.github/workflows/test.yml` runs `./gradlew test` only, so `:app:lintDebug` never fires there. Running it by hand found 29 untranslated German strings and a `CAMERA` permission that implicitly marked camera hardware required, hiding the app from Chromebooks; both are fixed and the tree is now at 0 errors, 26 warnings. A second workflow step would keep it that way, chiefly guarding `MissingTranslation` and `NewApi` — the latter being the only thing standing between `compileSdk 37` and `minSdk 29`. Waiting on an appetite for the cost: lint's checks change with AGP, so a Dependabot bump can fail a pull request for reasons unrelated to its own change. A baseline file would avoid that by freezing today's findings as acceptable, which is worse than an occasional red PR.
