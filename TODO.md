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

- [ ] `R` `phon` **`phon` has no `forOpen` byte vector.** `hexagons/phon` carries a fourth independent `PayloadCanonical`, but only relay, Android and iOS are pinned to the shared fixed-seed vector; phon's own tests exercise `forOpen` structurally, so it could drift from the other three and stay green. Copying `PayloadCanonicalVectorTests` into `hexagons/phon/src/test/scala/value_objects/svo/` is ~40 lines. Its doc comment no longer claims a vector test it hasn't got.
- [ ] `doc` arrows overlap in the C4 system-context and container diagrams in [docs/architecture.md](docs/architecture.md). Cosmetic, deliberately deferred. Mermaid's C4 renderer offers little layout control — `UpdateLayoutConfig` with `$c4ShapeInRow`/`$c4BoundaryInRow` is the usual lever, and converting a diagram to a styled `flowchart` gives full control at the cost of the C4 shape vocabulary.

## Parked

Not rejected — waiting on something.

- [-] `R` `A` `I` **Airtable / Google Sheets relay kinds.** Wanted, but gated on the default relay demonstrating real adoption. Each non-REST kind needs its own adapter and wire shape, plus a `relayKind` discriminator on `Contact`; that is speculative surface area until people are actually using Deposplit. No design work before then.
- [-] `R` `phon` `A` `I` **Relay row TTL.** Two retention classes — generous for consent-gated action requests, short and latest-wins for fire-and-forget pushes. No TTL or collection job exists; this is deployment configuration, not application logic, and no correctness property depends on *having* one — though one depends on how it is introduced, below. See "absence is never a signal" in [docs/protocol.md](docs/protocol.md). **Collection cannot land alone.** A `deposit` row carries the only copy of a share in transit, and the sender's retained blob is the prerequisite for surviving its collection — but nothing re-deposits from that blob today, so shipping a collection job without the client half would introduce exactly the loss the retention exists to prevent. The client half is bigger than it looks: a re-deposit mints a fresh request id relay-side, while the blob's `id` *is* the original request id — the key `ShareMetadata`, `syncDistributed` and `isRetentionStillPending` all join on — so both local records need re-keying. It need not detect collection, though. Attempt the re-deposit unconditionally and let `hasActiveDeposit` answer `Conflict` while the row is still there, which keeps "absence is never a signal" intact.
- [-] `A` **Android lint in CI.** `.github/workflows/test.yml` runs `./gradlew test` only, so `:app:lintDebug` never fires there. Running it by hand found 29 untranslated German strings and a `CAMERA` permission that implicitly marked camera hardware required, hiding the app from Chromebooks; both are fixed and the tree is now at 0 errors, 26 warnings. A second workflow step would keep it that way, chiefly guarding `MissingTranslation` and `NewApi` — the latter being the only thing standing between `compileSdk 37` and `minSdk 29`. Waiting on an appetite for the cost: lint's checks change with AGP, so a Dependabot bump can fail a pull request for reasons unrelated to its own change. A baseline file would avoid that by freezing today's findings as acceptable, which is worse than an occasional red PR.
