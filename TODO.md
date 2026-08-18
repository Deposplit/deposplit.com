# Deposplit — Implementation TODO

Tracks **implementation state** for the design decisions captured in
[`deposplit.com/CLAUDE.md`](CLAUDE.md) → *"What is next"* (items 1–13).

**Division of labour:** `CLAUDE.md` holds the *design rationale* (the *why* — long,
cross-referenced); this file holds *what's left, per platform* (the *what* — terse,
checkbox-driven). Work graduates: unchecked here → checked here → recorded in the
relevant repo's `CHANGELOG.md`. Shipped history is **not** repeated here — see the
`CHANGELOG.md` files.

Central on purpose: the items fan out across three repos, and a solo developer + agent
is better served by one board than three. This file lives in the hub repo
(`deposplit.com/`); Android/iOS work items are tracked here too.

**Status:** `[ ]` not started · `[~]` in progress · `[x]` done · `[-]` n/a / parked
**Scope tags:** `R` deposplit.com relay/backend · `phon` deposplit.com phone emulator ·
`A` Android · `I` iOS · `doc` CLAUDE.md/README/CHANGELOG

> ⚠ **Item 9 is mostly shipped; items 10 and 12 are fully shipped; 13 is design-complete but not
> yet built** (items 6, 7, 8, and 11 shipped too — see below). `No migrations` throughout —
> Deposplit is pre-launch; test relays and devices reset to a clean slate.

---

## Where to start / dependencies

- **Item 7 (holder-decrypts-at-pickup crypto redesign) is done** — it was the foundation:
  it reshaped `HeldShare` and `ShareMetadata` onto a `contactId` anchor, and items
  **8, 9, 12, 13** all assume its model. Shipped on Android, iOS, and (for consistency,
  not full parity) phon.
- **Item 6 (four-level verification) is done** — it was independent of the crypto redesign
  (enum + UI, no crypto dependency), so it shipped first as a low-risk warm-up. Item 10 leans
  on its levels (the `min(level, LOW)` downgrade).
- **Item 11 (secret lifecycle) is done** — its `reconstruct()` bug-fixes (enforce real `k`,
  stop auto-teardown) and its `Secret` aggregate are shipped; items 9 and 13 build on it.
  Used the `contactId` anchor item 7 already introduced. Shipped on Android, iOS, and
  (for consistency, not full parity) phon.
- **Item 8 (identity recovery) is done** — the relay gained a fourth, non-consent-gated
  `inventory` transaction type and `k`/`n` columns on `share_requests`; `retrieval` is
  now matched on `secretId` instead of the transient deposit relay-row id; `ContactManagement
  .updateContact` and `ShareManagement.pushRecoveryMetadata`/`processRecoveryMetadata` are new
  primitives; catalog export/import shipped on Android/iOS with UI, and as a primitive-only
  port on phon. Shipped on the relay, Android, iOS, and (for consistency, not full parity) phon.
  Item 9's rotation push and item 10's revocation both build on this item's `updateContact`.
- **Item 9's rotation push and withdraw tombstone are done** — shipped on the relay (a dedicated
  `key_rotations` table, `POST /share-requests/withdraw`), Android, iOS, and (for consistency,
  not full parity) phon, all landing on the same design independently. Item 10's `min(level,
  LOW)` downgrade is already baked into every client's rotation-receive path, not deferred to
  item 10 itself. **Not** shipped under item 9: the pull-style health-check (superseded by item
  12's heartbeat push before it was ever built) and the "regenerate my own identity" trigger that
  would let a user actually *originate* a rotation from the UI (deliberately scoped out — see
  `CLAUDE.md` item 9's "Proactive rotation" bullet). The "reconstruct-and-re-split repair flow"
  checklist line needs no new code (already composes from item 11's primitives) but has no
  dedicated UI trigger yet.
- **Item 10 (stolen-key revocation) is done** — 100% client-local, no relay work. The
  `min(level, LOW)` downgrade was already shipped under item 9; item 10 added the local
  "compromised/revoked" key flag (`Contact.revokedEdKeys`), the `KeyConflict` record captured
  *before* a gated rotation notice is deleted (never leaning on relay retention), never-auto-
  resolved conflict UI, and the retrieve-approval "key changed N days ago" indicator. Shipped on
  Android and iOS with full UI; phon picked up the data-model + gating logic for consistency,
  no HTMX UI.
- **Item 12 (custodial-heartbeat cadence) is done** — closes the last piece item 9 left open by
  flipping its health-check from a sender-pull to a holder-push. Relay gained a `custody_heartbeats`
  table (latest-wins upsert per `(holder_key, owner_key)`, never consumed-and-deleted, unlike
  `inventory`/`key_rotations`). Every client gained deposit-blob retention until first-observed
  pickup confirmation (relay-observed *or* heartbeat-attested), a freshness clock on `ShareMetadata`
  (`lastConfirmedAt`), and opt-out capture on `Contact`. Shipped on the relay, Android, iOS, and
  (for consistency, not full parity) phon.
- Rough dependency order for what's left: **9 (repair-flow UI trigger + regenerate-identity
  trigger) → 13**.

---

## Open items with remaining work (1–5)

### Item 1 — iOS biometric unlock · [CLAUDE.md#1](CLAUDE.md)
- [ ] `I` gate `reconstruct()` behind `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` in `ShareDetailView` (Android already gates via `BiometricPrompt`)

### Item 2 — End-to-end interop testing · [CLAUDE.md#2](CLAUDE.md)
- [ ] Android deposits → iOS approves the Deposit → later a Retrieval → Android reconstructs, against a live `sbt run`
- [ ] BYOR variant: two local `sbt run` instances on different ports; one contact with a `relayBaseUrl` override → verify deposit/pickup/retrieve/delete route through the override while a no-override contact still round-trips through the default

### Item 3 — Recipient-side signature verification · [CLAUDE.md#3](CLAUDE.md) · *mostly done*
- [x] `R` `senderSignature`/`recipientSignature` over `PayloadCanonical`, server-side re-verify (89 tests)
- [x] `A` sign + re-verify against local contact key (31 tests)
- [~] `I` implemented but **not yet compiled / test-run** — needs a real `swift build`/`swift test` on macOS (see `iOS/CLAUDE.md` "TODO for Claude on macOS")

### Item 4 — BYOR (Bring Your Own Relay) · [CLAUDE.md#4](CLAUDE.md) · *self-hosted done*
- [x] `R` `A` `I` self-hosted-instance BYOR: `Contact.relayBaseUrl`, `ShareRelayResolver`, deduped fan-out, QR `v:2` `relay` field, runtime "default relay" setting
- [ ] `A` `I` real multi-device BYOR interop testing (ties to item 2)
- [-] `R` `A` `I` Airtable / Google Sheets relay-kinds (`relayKind` discriminator) — **PARKED pending real default-relay adoption** (demand gate, not rejection)

### Item 5 — Freemium one-time unlock · [CLAUDE.md#5](CLAUDE.md) · *future*
- [ ] `A` `I` `PurchaseRepository` port (`isPremium()`, `secretsDepositedCount()`)
- [ ] `A` `I` deposit-flow limit check + gate sender-side relay-override on `isPremium()`
- [ ] `I` StoreKit 2 adapter · [ ] `A` Google Play Billing adapter
- [ ] `A` `I` paywall screen (on free-cap hit, or free user configuring a sender-side relay override)
- [ ] free cap counts **`ACTIVE`** secrets only (see item 11) — enforce against `Secret` state

---

## Planned items (6–13) — items 6, 7, 8, 10, 11, 12 done, 9 mostly shipped, 13 design-complete but not yet built

### Item 6 — Four-level contact verification · [CLAUDE.md#6](CLAUDE.md)
`VERY_LOW`/`LOW`/`HIGH`/`VERY_HIGH`, ordinal. Old `UNVERIFIED`/`VERIFIED` would map onto
`VERY_LOW`/`VERY_HIGH` conceptually, but **no on-device migration code was written or is needed** —
Deposplit is pre-launch; the relay DB has been purged and all emulators/simulators reset clean.
- [x] `phon` expand `VerificationLevel` value object 2→4, ordinal/comparable (`relay` untouched — never stores contacts)
- [x] `A` expand enum + contact record + add-contact level picker + guidance text
- [x] `I` same
- [x] `doc` rewrite "Contact Verification" section (+ "Ready/Not added" & "Contacts Management" refs) 2→4
- [x] `doc` identity-recovery approver weighting references 4 levels (rule itself still TBD — walk separately)

### Item 7 — Holder-decrypts-at-pickup crypto redesign · [CLAUDE.md#7](CLAUDE.md) · *done*
Client-only; `relay` + DB schema untouched (still opaque bytes).
- [x] `A` `I` pickup: decrypt with holder X25519 priv + sender X25519 pub → store **plaintext** share
- [x] `A` `I` retrieve: re-encrypt plaintext to *current* sender's X25519 pub; sender decrypts + `combine` (`reconstruct()` needed no code change — it already used the sender's own identity + the holder's current `xPublicKey`)
- [x] `A` `I` `HeldShare`: `ciphertext`→`plaintextShare`; `senderKey`→`contactId`; denormalized `senderPseudonym` snapshot
- [x] `A` `I` `ShareMetadata`: `recipientKey`→`contactId`
- [x] `A` `I` precondition: rotation/recovery updates existing contact **in place**, preserving `contactId` (trivially satisfied — items 8/9's rotation/recovery mechanisms aren't built yet, and `Contact.id` was already stable)
- [x] `phon` same field renames + pickup/retrieve crypto flow, for cross-platform *consistency* — not full parity (no denormalized pseudonym snapshot; not originally tagged for this item, added on request during the walk)

### Item 8 — Identity recovery (holder-driven metadata reconstitution) · [CLAUDE.md#8](CLAUDE.md) · *done*
Pure-social, `k`-of-`n` by construction; recovery returns **metadata only**, never shares.
- [x] `R` `A` `I` new metadata-only recovery message type (`{secretId, label, secretCreatedAt, holder-identity, k, n}` push) — self-approved at creation (no consent phase), no conflict check
- [x] `A` `I` `updateContact(contactId, newKeys?, newLevel?)` contact-update-in-place primitive + UI (preserve `contactId`; steer away from delete+add; key change **forces** re-choosing verification level — no silent inherit)
- [x] `R` `A` `I` add `k`/`n` to deposit payload + `PayloadCanonical` + `HeldShare`/`ShareMetadata`
- [x] `A` `I` re-key `retrieval` on `secretId` (not the transient deposit relay-row id) — also applied to `removal`'s holder-side matching for the same reason, though not originally called out
- [x] `A` `I` optional catalog export/import (non-secret catalog: contact pubkeys, pseudonyms, levels, `ShareMetadata`) — native file pickers on both platforms (`.fileExporter`/`ShareLink`/`.fileImporter` on iOS; SAF `CreateDocument`/`OpenDocument` on Android)
- [x] `phon` same field renames (k/n, RecoveryMetadata type), retrieve/delete re-key, `updateContact` (keys only, no level param — no picker UI per item 6's narrower phon scope), `pushRecoveryMetadata`/`processRecoveryMetadata`, and the `Catalog`/`CatalogManagement`/`CatalogService` primitive — for cross-platform *consistency*, not full parity (no relink/catalog-backup UI; not originally tagged for this item)

### Item 9 — Holder-key-change handling + redundancy monitoring · [CLAUDE.md#9](CLAUDE.md)
NB: the health-check is a **push** — reshaped by item 12, so the originally-sketched pull
health-check is deliberately **not** being built here; skip straight to item 12 for that piece.

> ⚠ **Scope split agreed 2026-08-18 (Paul + Claude), before iOS work started:** the rotation
> push's *receiving* side (auto-verify against the trusted old key, `min(level, LOW)` downgrade,
> `updateContact` in place) and a callable `pushRotation(to:)` primitive are in scope per-platform
> below. The *trigger* — a user deliberately regenerating their own keypair while still holding
> the device (distinct from item 8's device-*loss* recovery) — is **not**: it needs a new
> `IdentityStore` capability ("replace my keypair, keep contacts/shares/secrets") plus a Settings
> UI action, neither of which exists yet or is specified by any "What is next" item. Deferred as
> its own follow-up rather than improvised mid-item-9. See `CLAUDE.md` item 9's "Proactive
> rotation" bullet for the same note.

- [x] `R` `A` `I` `phon` signed `rotate(K_old→K_new)` push; auto-verify against trusted old key; update contact in place — **done, all four scopes** (receive-side + `pushRotation(to:)` primitive only — see scope-split note above)
- [ ] `A` `I` reconstruct-and-re-split repair flow (shared with item 11) — the underlying primitives (`reconstruct`, `deposit`, `discardSecret`) already compose into this on every platform, so no new hexagon code is needed; left unchecked because there's no dedicated one-tap "repair" UI action yet, and wiring one before item 12 gives Alice a reason to see it (a lost-holder alert) would be a button with nothing to trigger it
- [x] `R` `A` `I` `phon` "withdrawn by recipient" row state + tombstone-on-delete + `syncDistributed()` handling (row *absence* never a signal) — **done, all four scopes**

  **`R` implementation notes (2026-08-18):** Two independent pieces, both relay-only so far —
  client wiring (Android/iOS/phon) is still open.
  - **Withdrawn tombstone.** Added `Withdrawn` to `ShareRequestState` (now `Pending`/`Approved`/
    `Denied`/`Withdrawn`) and a new `ShareRequests.withdrawShareRequests(recipientKey, senderKey?,
    secretId?)` port method, `POST /share-requests/withdraw` — flips matching **`Approved`
    `Deposit`** rows to `Withdrawn` in place instead of hard-deleting them (Retrieval/Removal rows
    for the same grouping, and non-`Approved` Deposit rows, are left untouched — those are
    separate, already-resolving consent flows). Deliberately a **new** method rather than
    repurposing the existing bulk `deleteShareRequests`: that method's two actual call sites
    (Removal-approval cleanup, Deposit-delete cascade) both want a real hard delete, not a
    tombstone, so overloading its Deposit-row behavior would have silently changed their meaning.
    `hasActiveDeposit` now excludes `Withdrawn` alongside `Denied`, so a withdrawn holder can be
    re-deposited to later.
  - **`key_rotations` — a dedicated table, not a `ShareTransactionType` case.** Considered folding
    the rotation push into `share_requests` (reusing the existing poll endpoint) but rejected: a
    rotation notice has no `secretId` and no consent phase, so it would need a nullable
    `secret_id` plus a pile of forever-`NULL` share-specific columns — exactly the abstraction-fit
    problem the `ShareTransactionType` rename just cleaned up. Went with a small dedicated table
    instead: `key_rotations(id, old_ed25519_key, recipient_key, new_ed25519_key, new_x25519_key,
    signature, created_at)`, no `state` column (no consent, same fire-and-forget shape as
    `inventory` — the recipient polls, auto-verifies, and deletes once consumed). New value
    objects `KeyRotation` and `X25519Key` (a dedicated opaque type rather than reusing `PublicKey`,
    which is documented/used for Ed25519 verification specifically — keeps the two key algorithms
    from being silently interchangeable despite sharing a 32-byte length; the relay never performs
    key agreement with it, only routes it opaquely like ciphertext). New
    `PayloadCanonical.forRotation(recipientKey, newEd25519Key, newX25519Key)`, signed by the
    caller who becomes `oldEd25519Key` — proves continuity of key control, which is what lets the
    recipient auto-accept without a fresh human re-verification. New `KeyRotations` driving port +
    `KeyRotationsService`, `KeyRotationRepository` driven port + `AnormKeyRotationRepository`,
    `KeyRotationsController` at `POST /key-rotations` (push) / `GET /key-rotations` (list,
    recipient-scoped) / `DELETE /key-rotations/{id}` (recipient deletes once consumed — unlike
    `ShareRequest` rows there's no sender-side reason to read one back). Bound in `app/Module.scala`
    alongside the existing `ShareRequests`/`ShareRepository` bindings.
  - Cross-platform signature vector added to `PayloadCanonicalVectorTests.scala` for
    `forRotation`, same fixed-seed pattern as `forOpen` — recomputed via an actual BouncyCastle
    run: `Fs4kRoHL0q5uN2ECfmXdcJnYzSz_yvO-8VNz6AIJYAcMR3QJmPQlu8AyiJpYOfeUGXag0M4VdZtPQ3AoWjPBDQ`
    (fixed recipientKey/newEd25519Key/newX25519Key = 32 bytes of `0x03`/`0x04`/`0x05`). Android's
    and iOS's `PayloadCanonicalVectorTest`/`-Tests` must match this exact value once their turn
    comes.
  - `conf/evolutions/default/1.sql` edited in place (new `withdrawn` enum value, new
    `key_rotations` table + index, updated `hasActiveDeposit`/partial-index prose) — no new
    evolution file, per the no-migrations pre-launch policy.
  - `conf/openapi.yaml` fully updated: `ShareRequestState` enum + description, `POST
    /share-requests/withdraw`, new `KeyRotation`/`PushRotationBody`/`X25519PublicKey` schemas,
    `POST`/`GET /key-rotations` + `DELETE /key-rotations/{id}` paths, and the "Payload signatures"
    prose section gained `forRotation`'s canonical construction alongside `forOpen`/`forRespond`.
  - Tests: `SharesServiceTests` gained 5 withdraw tests (flips to Withdrawn not delete; sender/
    secretId filtering; Pending rows untouched; Retrieval/Removal rows for the same grouping
    untouched; a withdrawn deposit no longer blocks re-deposit) — also fixed the
    `InMemoryShareRepository` test double's `hasActiveDeposit`, which needed the same
    `Denied`-and-`Withdrawn` exclusion as the real Anorm repo. New `KeyRotationsServiceTests` (6
    tests: push/verify/list/delete + Forbidden/NotFound) with its own `InMemoryKeyRotationRepository`
    test double, reusing `SharesServiceTests`'s `Fixtures` object (same package) for keypairs. Root
    gained `KeyRotationsApiSpec` (9 integration tests) and 3 new tests in `ShareRequestsApiSpec`
    for the withdraw endpoint; `RequestSigner` gained `signRotation`. Relay hexagon: 69 → 83 tests;
    root: 39 → 51 tests (phon's 37 untouched, not yet wired to either piece — expected, matching
    item 8's precedent of `R` landing before client platforms).

  **`I` implementation notes (2026-08-18):** Scoped per the shared note above — receive-side +
  `pushRotation(to:)` primitive only, no "regenerate my own identity" trigger.
  - **`ShareRelay` gained the new methods directly, rather than a separate `KeyRotationRelay`
    port.** The relay backend keeps rotation pushes in a dedicated `KeyRotations`
    service/`key_rotations` table for domain-purity reasons (no `secretId`, no consent phase) —
    but those reasons are about *server-side schema shape*, not about this *client-side*
    HTTP-calling port, which is really just "the wire API surface for one relay instance." Since
    rotation pushes route through the exact same BYOR per-contact relay resolution as every other
    `ShareRelay` call, giving them a second port would only have meant a second
    resolver/cache for zero benefit. New value object `KeyRotation` (no dedicated `X25519Key`
    type on iOS — unlike the relay, the client never round-trips an X25519 key through a
    different verification path than an Ed25519 one, so the extra type would have bought nothing);
    new `PayloadCanonical.forRotation`, byte-identical construction to the relay's Scala version.
  - **Receiving side** — `ShareService.processRotations()` (private, called from `syncInbox()`
    alongside the existing `processRecoveryMetadata()`): fans out `listRotations()` across every
    relay referenced by the contact list (`allRelays()`, the same helper every other fan-out
    method already uses), matches `oldEd25519Key` against a known contact via
    `contactRepository.getByEdKey`, independently re-verifies `signature` against that same key
    (never trusts the relay's word for it — same defense-in-depth posture as `verifyOpen`), and on
    success calls `contactManagement.updateContact(...)` with `verificationLevel: min(contact
    .verificationLevel, .low)` — item 10's downgrade rule, applied here since it's inseparable
    from correct rotation-processing semantics (an unconditional auto-accept with no downgrade
    would just be a wrong implementation of this receive path, not a simpler one). Consumed
    notices are deleted from the relay (`deleteRotation`), same "consumed once processed" pattern
    as `inventory`.
  - **`ShareService` gained a new `contactManagement: any ContactManagement` constructor
    dependency** so `processRotations()` can call `updateContact` — a driving-port-to-driving-port
    collaboration within the same hexagon (both are use-case interfaces the hexagon itself
    implements), not a boundary violation; `ContactService.updateContact`'s own precondition ("a
    fresh level is required whenever a key changes") is trivially satisfied here since
    `processRotations()` always computes and supplies one. `DeposplitApp.swift` reordered so
    `ContactService` is constructed before `ShareService` and the same instance is passed to both.
  - **Sending side** — `ShareManagement.pushRotation(contactId:newEd25519Key:newX25519Key:)`:
    signs the new keys with the device's *current* identity (which becomes `oldEd25519Key` on the
    wire) and pushes to the resolved contact's relay. Exercised directly by tests; no UI action
    calls it yet (see the scope-split note above).
  - **Withdraw tombstone** — `deleteHeldShare`/`deleteAllHeldFromSender` now best-effort-notify
    the sender's relay (`withdrawShareRequests`) before deleting the local `HeldShare`(s): the
    single-share case scopes by `secretId` alone (already globally unique per deposit, so
    `senderKey` isn't needed to disambiguate); the delete-all-from-sender case scopes by
    `senderKey` instead of looping per secretId, matching the relay's own bulk-filter shape. Both
    call the relay with `try?` — local deletion always proceeds regardless of the network
    outcome, since the notify is a courtesy, not a precondition (mirrors the relay's own
    "fire-and-forget" framing of the tombstone). `syncDistributed()` gained a branch for
    `req.state == .withdrawn`: drops the local `ShareMetadata` pointer (so the health count — a
    pre-item-12 proxy over `ShareMetadata` rows, per item 11 — reflects the loss) and deletes the
    now-consumed relay row, mirroring the "delete once observed and processed" pattern used
    elsewhere; non-withdrawn rows are upserted exactly as before.
  - `ShareRequestState` gained `.withdrawn`; two app-layer exhaustive switches over it
    (`ShareDetailView.stateColor`/`.label`, `DistributedTab`'s private `ShareRequestState.label`
    extension) needed a case added — caught immediately by `xcodebuild build`, not by `swift
    test`, since the hexagon package alone doesn't exercise app-layer UI code. In practice a
    withdrawn row rarely reaches these views at all, since `syncDistributed()` removes the local
    pointer as soon as it observes one.
  - Tests: `ShareServiceTests` gained 11 tests (rotation push/verify/downgrade/reject-forged/
    reject-unknown-sender; withdraw scoping for both single-share and all-from-sender, including a
    "local delete still happens if the relay call fails" case; `syncDistributed`'s withdrawn vs.
    non-withdrawn branches). `FakeContactRepository` was upgraded from a set of no-ops to a
    genuinely mutable in-memory store so tests can observe `updateContact`'s effect; `FakeShareRelay`
    gained the four new methods with call-tracking. Hexagon: 41 → 52 tests, all passing (`swift
    test`). Full app-target verification also run and passing: `xcodebuild build` (BUILD SUCCEEDED)
    and `xcodebuild test` against an iPhone SE simulator (TEST SUCCEEDED).

  **`A` implementation notes (2026-08-18):** Same scope and shape as iOS — receive-side +
  `pushRotation(to:)` primitive, withdraw wired both ways. Mirrors iOS's design decisions
  one-for-one since both platforms hit the identical fork (extend `ShareRelay` directly vs. a
  separate port; add a `ContactManagement` dependency to `ShareService`).
  - `ShareRelay.kt` gained `withdrawShareRequests`/`pushRotation`/`listRotations`/`deleteRotation`
    directly, same reasoning as iOS (one physical relay, one BYOR routing scheme — a second
    port/resolver would have bought nothing). New `KeyRotation` data class in `value_objects/`.
    `ShareRequestState` gained `WITHDRAWN`. `PayloadCanonicalVectorTest.kt` gained the `forRotation`
    vector (same fixed seed/fixture bytes as the relay's and iOS's), actually run — confirms
    BouncyCastle-Kotlin agrees byte-for-byte with BouncyCastle-Scala and CryptoKit-Swift, not just
    asserted by analogy.
  - `ShareService` gained a `contactManagement: ContactManagement` constructor parameter; private
    `processRotations()` (called from `syncInbox()`) fans out `listRotations()` across
    `allRelays()`, matches `oldEd25519Key` via `contactRepository.getByEdKey`, independently
    re-verifies `signature` (never trusts the relay's word for it), and on success calls
    `contactManagement.updateContact(contact.id, notice.newEd25519Key, notice.newX25519Key,
    minOf(contact.verificationLevel, VerificationLevel.LOW))` — Kotlin enums are ordinal-`Comparable`
    for free, so `minOf` alone expresses item 10's downgrade rule, no custom comparison needed.
    Consumed notices are deleted via `deleteRotation`. `pushRotation(contactId:newEd25519Key:
    newX25519Key:)` signs with the device's current identity and pushes via `relayForContact`;
    exercised by tests only, no UI action yet (scope-split note above).
  - `deleteHeldShare`/`deleteAllHeldFromSender` best-effort-notify (`runCatching`) the sender's
    relay before deleting locally, same `secretId`-alone vs. `senderKey`-bulk scoping as iOS.
    `syncDistributed()` gained a `req.state == ShareRequestState.WITHDRAWN` branch that drops the
    local `ShareMetadata` row and deletes the relay row, `continue`-ing past the normal upsert path.
  - Two exhaustive `when` expressions over `ShareRequestState` needed a `WITHDRAWN` branch
    (`ShareDetailScreen.kt`'s state-label/color mapping, `HomeScreen.kt`'s holder-status badge) —
    caught immediately by `:app:compileDebugKotlin`, same category of gap as iOS's Swift `switch`
    exhaustiveness check. New string resources `share_request_state_withdrawn` added to both
    `values/strings.xml` ("Withdrawn") and `values-de/strings.xml` ("Zurückgezogen").
    `DeposplitApiAdapter.kt`'s `toDomain()` state-string `when` also gained a `"withdrawn"` branch
    (this one wasn't exhaustive-enforced by the compiler — a plain string match with an `error()`
    fallback — so it would only have surfaced as a runtime crash on the first real withdrawn row
    without this fix; worth flagging since Kotlin's compiler safety net doesn't cover this shape).
  - Tests: `ShareServiceTest.kt` gained 11 tests, same coverage shape as iOS (rotation push/
    verify/downgrade/reject-forged/reject-unknown-old-key; withdraw scoping for both
    single-share and all-from-sender, including a "local delete still happens if the relay call
    fails" case; `syncDistributed`'s withdrawn vs. non-withdrawn branches). `FakeContactRepository`
    upgraded from no-op writes to a genuinely mutable in-memory store (needed to observe
    `updateContact`'s effect); `FakeShareRelay` gained the four new methods with call-tracking;
    `newService()` widened from returning a `Triple` to a small `ShareServiceFixture` data class
    (Kotlin's `Triple` tops out at 3 elements, and this needed 5) — all five call sites updated.
    `:hexagon:test` (forced via `:hexagon:clean :hexagon:test`, JUnit XML read directly, matching
    the rename session's established Gradle-false-UP-TO-DATE workaround): 41 → 55 tests, 0
    failures (11 new `ShareServiceTest` cases + 3 new `PayloadCanonicalVectorTest` `forRotation`
    cases). `:app:compileDebugKotlin` BUILD SUCCESSFUL (same two pre-existing unrelated warnings
    as before, no new ones); `:app:test` still blocked by the pre-existing unrelated jlink
    toolchain issue, not investigated further.

  **`phon` implementation notes (2026-08-18):** Consistency, not parity — ported the same
  domain/hexagon-level shape as relay/Android/iOS, skipped everything UI (no withdraw button, no
  rotation-push trigger, no relink-style controller action — phon's minimal HTMX views have no
  plumbing for any of that already, matching every prior item's precedent).
  - `Share.scala` gained `ShareRequestState.Withdrawn`; new `value_objects/svo/KeyRotation.scala`
    (own copy — phon can't depend on relay), using raw `Array[Byte]` fields throughout rather than
    the relay's dedicated `PublicKey`/`X25519Key` opaque types — those exist for server-side
    verification-path safety that doesn't apply to this client-side mirror, and every other phon
    value object (`Contact`, `ShareRequest`) already uses bare byte arrays, so opaque types here
    would be parity with the relay, not consistency with phon's own conventions. New
    `PayloadCanonical.forRotation`, byte-for-byte identical to the other three platforms'.
  - `ShareRelay.scala` (phon's driven port) gained the same four methods as Android/iOS, same
    "grouped onto this trait, not a separate port" reasoning. `HttpClientShareRelay.scala`
    (`app/driven_adapters/phon/` — the live HTTP client phon uses to talk to the actual relay) is
    the load-bearing file again, same as the rename: implements all four against the real
    `/share-requests/withdraw` and `/key-rotations` endpoints, and its `parseShareRequest`'s
    `state` match gained a `"withdrawn"` case (previously `throw`s on anything unrecognized —
    would have been a hard runtime failure the first time a real withdrawn row came back, not
    just a stale-looking one, since phon actually round-trips through the live relay).
  - **`ContactManagement.updateContact` needed a real signature change, not just plumbing** — this
    is the one place phon's "no picker UI" simplification (item 6) collided with item 9's
    correctness requirement, not just its UI polish. phon's `updateContact` had no
    `verificationLevel` parameter at all; a key change unconditionally defaulted to `VeryHigh`
    (mirroring `addFromQr`'s in-person-flow default, per item 8's precedent). Item 10's downgrade
    rule (`min(old, Low)`) is a domain rule, not UI polish — auto-accepting a rotation at
    `VeryHigh` would be actively wrong (the same trust level an in-person QR re-scan earns),
    not merely less polished than Android/iOS. Added `verificationLevel: Option[VerificationLevel]
    = None` to `updateContact` on both the port and `ContactService`: an explicit level always
    wins; absent one, the existing key-change-defaults-to-`VeryHigh` behavior is unchanged
    (verified by a regression test). No existing call site outside tests supplied a level before
    this change (grepped first), so this was a safe, backward-compatible signature extension.
  - `ShareService` gained a `contactManagement: ContactManagement` constructor parameter (already
    bound in `PhonModule.scala` — Guice's `@Inject()` picks it up automatically, no module edit
    needed) so its new `processRotations()` (private, called from `syncInbox()`) can call
    `updateContact` with the explicit downgraded level after auto-verifying an incoming notice.
    `pushRotation`, the withdraw wiring in `deleteHeldShare`/`deleteAllHeldFromSender`, and
    `syncDistributed()`'s withdrawn branch all mirror Android/iOS exactly.
  - Tests: `FakeContactRepository` (in `ShareServiceSignatureTests.scala`, package-shared with
    `ShareRelayResolverFanOutTests.scala` and `ContactServiceTests.scala` via Scala's
    package-private top-level visibility) upgraded from no-op `save`/`delete` to a genuinely
    mutable store; `FakeShareRelay` gained the four new methods with call-tracking; `newService`
    widened from a 3-tuple to a 5-tuple (contactRepo + metaRepo added) — five call sites across
    two test files updated, plus `newServiceForRecoveryTest`'s single `ShareService(...)`
    construction. `ShareServiceSignatureTests` gained 11 tests, same coverage shape as Android/iOS
    (rotation push/verify/downgrade/reject-forged/reject-unknown-old-key; withdraw scoping for
    both single-share and all-from-sender, including a "local delete still happens if the relay
    call fails" case; `syncDistributed`'s withdrawn vs. non-withdrawn branches).
    `ContactServiceTests` gained 2 tests for the new `verificationLevel` parameter (explicit level
    honored; still defaults to `VeryHigh` with none given). `phon/test`: 37 → 50 tests, 0 failures.
    Full project `sbt test` (relay 83 + phon 50 + root 51 = 184) also run clean, confirming
    end-to-end wire-compatibility, not just independent compilation.

### Item 10 — Malicious key substitution + stolen-key revocation · [CLAUDE.md#10](CLAUDE.md) · *done*
- [x] `A` `I` `K_old`-signed rotation auto-accept with `min(level, LOW)` downgrade — **already shipped under item 9** (see item 9's note above); no new work needed here.
- [x] `A` `I` `phon` local "compromised/revoked" key flag → disables auto-accept of that key's rotations
- [x] `A` `I` conflict-resolution UI for two competing "current keys" (never auto-resolved) — `phon` gets the gating/data-model only, no HTMX UI, per its established "consistency not parity" scope
- [x] `A` `I` "requester's key changed N days ago" indicator + fresh-OOB nudge on approve-retrieve screen

  **No `R` work — 100% client-local.** Every piece (the revoked-key flag, the conflict record, the
  gating check, the retrieve-approval indicator) lives entirely in each client's local state; no
  new relay endpoint, no schema change.

  **`I` implementation notes (2026-08-18):** `hexagon/Sources/value_objects/Contact.swift` gained
  `revokedEdKeys: [Data]` (a growing historical set, not a single boolean, so a later legitimate
  relink to a genuinely new key is never blocked by an old flag) and `keyChangedAt: Date?`. New
  `KeyConflict.swift` value object + `driven_ports/KeyConflictRepository.swift` port. The core
  design decision (corrected mid-session after an initial "lean on the relay" proposal was
  rejected — the relay may lose its state at any time and must never be relied on to keep a
  security alert alive): `ShareService.processRotations()` now checks the incoming notice's
  `oldEd25519Key` against `contact.revokedEdKeys` **before** the existing downgrade/auto-accept
  branch — on a match it saves a `KeyConflict` to the new local `KeyConflictRepository`, deletes
  the relay notice (the durable local copy now exists, so nothing depends on relay retention
  either), and skips `updateContact` entirely; no match falls through to item 9's existing
  auto-accept path unchanged. `ContactManagement.markKeyCompromised(contactId:edPublicKey:)`
  (idempotent, defaults to the contact's current key) and `ShareManagement.listKeyConflicts`/
  `dismissKeyConflict` are new driving-port primitives. UI: Contacts screen gained a red
  warning-shield badge + "Mark Key Compromised" context-menu action + confirmation dialog;
  Requests screen gained a "Key Conflicts" section above pending requests (`KeyConflictCard` —
  "Possible impersonation attempt," **Dismiss only**, no "Accept" action — deliberately steers to
  the existing item-8 Relink flow as the sole path back, never auto-resolved) and an orange
  "key changed N days ago" label on retrieval requests specifically (gated to `.retrieval`, per the
  "key change → quick retrieval" attack signature), sourced from `RequestsViewModel
  .keyChangedDaysAgo(for:)`. Tests: `ShareServiceTests` gained 6 cases (revoked-key gating +
  captured conflict; non-revoked rotation still auto-accepts; list/dismiss round-trip;
  `markKeyCompromised` default-key + idempotent; `updateContact` stamps `keyChangedAt` only on an
  actual key change) — required widening `makeService()`'s fixture tuple from 5 to 6 across 19
  call sites (16 destructured + 3 direct `ShareService(...)` constructions). Hexagon: 52 → 58
  tests, `swift test` all passing. Full app-target verification also run clean: `xcodebuild build`
  (device SDK, BUILD SUCCEEDED) and `xcodebuild test` against an iPhone SE (3rd gen) simulator
  (`** TEST SUCCEEDED **`, iPhone 16 unavailable on this machine so a different simulator id was
  substituted).

  **`A` implementation notes (2026-08-18):** Mirrors iOS's design one-for-one. `Contact.kt` gained
  `revokedEdKeys: List<ByteArray> = emptyList()` and `keyChangedAt: Instant? = null`. New
  `KeyConflict.kt` data class + `driven_ports/KeyConflictRepository.kt`.
  `ContactManagement.markKeyCompromised` and `ShareManagement.listKeyConflicts`/
  `dismissKeyConflict` added to the respective port interfaces. `ShareService.processRotations()`
  gained the identical pre-downgrade revoked-key check (save `KeyConflict` + delete relay notice +
  skip `updateContact` on a match), needing a new `keyConflictRepository: KeyConflictRepository`
  constructor parameter. `ContactService.updateContact` now carries `revokedEdKeys` forward
  unconditionally and stamps `keyChangedAt` only when a key actually changes;
  `markKeyCompromised` is idempotent (no-op if the key is already flagged). App layer:
  `LocalContactRepository`'s `ContactWire` gained non-optional `revokedEdKeys`/`keyChangedAt`
  fields (no optional/fallback decode shim — pre-launch, local stores are wiped not migrated); new
  `LocalKeyConflictRepository` (JSON file, structurally identical to
  `LocalShareMetadataRepository`). UI: `ContactsScreen` gained a red warning badge + a destructive
  "Mark Key Compromised" `IconButton` + `AlertDialog` confirmation; `RecipientRequestsTab` gained a
  `KeyConflictItem` list section above pending requests (Dismiss-only, steers to the existing
  Relink flow) and an orange "key changed N days ago" `Label` on `RETRIEVAL` requests specifically,
  using a `<plurals>` string resource (`requests_key_changed_warning`) in both `values/strings.xml`
  and `values-de/strings.xml`. Tests: `ContactServiceTest` gained 4 cases (was already split from
  `ShareServiceTest` by file, unlike iOS's single-file layout — `markKeyCompromised`
  default/idempotent/explicit-key, `updateContact`'s `keyChangedAt` stamping);
  `ShareServiceTest` gained 3 cases (revoked-key gating + captured conflict, non-revoked rotation
  still auto-accepts, list/dismiss round-trip) via a `FakeKeyConflictRepository` test double and
  widening `ShareServiceFixture`'s tuple from 5 to 6 across 16 destructured call sites plus 4 direct
  `ShareService(...)` constructions. `:hexagon:test`: 55 → 62 tests, 0 failures.
  `:app:compileDebugKotlin` BUILD SUCCESSFUL (same two pre-existing unrelated warnings as before,
  no new ones); full `./gradlew test` (hexagon + app) green.

  **`phon` implementation notes (2026-08-18):** Consistency, not parity — data model + gating logic
  only, no HTMX UI (no compromise-flag button, no conflict-list view, no key-changed indicator —
  phon's minimal views have no plumbing for any of that already, matching every prior item's
  precedent). `Contact.scala` gained `revokedEdKeys: List[Array[Byte]] = Nil` and
  `keyChangedAt: Option[Instant] = None`. New `value_objects/svo/KeyConflict.scala` (own
  `Serializable` case class, since phon's `Contact` and friends are Java-serialized to disk via
  `.devDBs/*.ser` files rather than JSON) + `driven_ports/KeyConflictRepository.scala`. New
  `driven_adapters/phon/FileKeyConflictRepository.scala`, structurally identical to
  `FileShareMetadataRepository.scala` (same `ObjectInputStream`/`ObjectOutputStream` pattern,
  its own `.devDBs/keyconflicts{port}.ser` file), bound in `PhonModule.scala`.
  `ContactManagement.markKeyCompromised` and `ShareManagement.listKeyConflicts`/
  `dismissKeyConflict` added to the port traits. `ShareService.processRotations()` gained the same
  pre-downgrade revoked-key check as Android/iOS, needing a new
  `keyConflictRepository: KeyConflictRepository` constructor parameter (now the 8th constructor
  param, alongside `contactManagement` added under item 9). `ContactService.updateContact` carries
  `revokedEdKeys` forward and stamps `keyChangedAt` on any key change; `markKeyCompromised` mirrors
  Android/iOS's idempotent-default-to-current-key shape. Tests: `ContactServiceTests` gained 4
  cases (same coverage as Android's `ContactServiceTest`); `ShareServiceSignatureTests` gained 3
  cases (revoked-key gating + captured conflict, non-revoked rotation still auto-accepts,
  list/dismiss round-trip) via a `FakeKeyConflictRepository` test double — needed widening
  `newService`'s return tuple from 5 to 6 across 16 destructured call sites in
  `ShareServiceSignatureTests.scala` plus 2 direct `ShareService(...)` constructions in the
  separate `ShareRelayResolverFanOutTests.scala` file (same package, so `FakeKeyConflictRepository`
  is visible there via Scala's package-private top-level scoping — no duplicate fake needed).
  `phon/test`: 50 → 57 tests, 0 failures. Full project `sbt test` (relay 83 + phon 57 + root 51 =
  191) also run clean.

### Item 11 — Secret lifecycle · [CLAUDE.md#11](CLAUDE.md) · *done*
Bounds `2 ≤ k ≤ n ≤ 255` (hard, no UI ceiling) — already enforced by `split()`/`combine()`
on all three platforms before this item; no new code needed there. Hexagon only; `relay` untouched.
- [x] `A` `I` `Secret(secretId, label, k, n, secretCreatedAt, state)` aggregate; `ShareMetadata` normalized to reference it
- [x] `A` `I` `reconstruct(secretId)` enforces `approved.size >= k` — **remove hardcoded `check(approved.size >= 2)`**
- [x] `A` `I` `reconstruct()` becomes a **pure read** — **remove the auto-teardown** of local `ShareMetadata` + relay rows
- [x] `A` `I` `discardSecret(secretId)` fan-out consent-gated `delete` primitive + "discard secret" UI
- [x] `A` `I` two-state `ACTIVE`/`DISCARDING` + health-alarm suppression while `DISCARDING`; record removed on teardown/force-forget (`forceForgetSecret`)
- [x] `A` `I` split-time three-axis soft warnings (operational burden / confidentiality tail `k` low vs `n` / availability tail `n−k` small)
- [x] `A` `I` graduated `n_live` health alarm (`>=k+2` healthy · `==k+1` caution · `==k` critical · `<k` lost) — feeds item 9; `n_live` was a pre-item-12 proxy (locally-tracked holder count) at the time this item shipped, since refined into item 12's freshness-gated count
- [x] `A` `I` free-cap counts `ACTIVE` only; `discardSecret` frees the slot immediately (item 5 / C4) — no enforcement exists yet since item 5 itself isn't built, but the `Secret.state` data model is ready for it
- [x] `phon` `Secret` aggregate + `reconstruct`/`discardSecret`/`forceForgetSecret` primitives; simplified its existing manual delete-fan-out to call `discardSecret` directly. Skipped the health badge and split-time warning UI (consistency, not full parity — not originally tagged for this item)

### Item 12 — Polling, staleness & relay-TTL cadence (custodial-heartbeat) · [CLAUDE.md#12](CLAUDE.md) · *done*
Flips item 9's health-check pull→push. `relay` schema gained one new table (`custody_heartbeats`); the relay's role as a blind mailbox is otherwise unchanged.
- [x] `R` `A` `I` `phon` reshape item 9's health-check → holder-initiated **signed custodial-heartbeat push** (default-on, holder-disableable) + **signed opt-out notice** message type — same wire shape covers both (`optedOut: true`, `secretIds` typically empty)
- [x] `A` `I` per-holder freshness clock + three buckets (`Confirmed` / `Unmonitored-by-choice` / `Silent-overdue`) feeding `n_live`; refreshed by any signed proof-of-custody (heartbeat / pickup / retrieve approval)
- [x] `A` `I` "getting stale" early-nudge UI; long-silent holders drop out of `n_live` (reversible)
- [x] `A` `I` `phon` client-side "retain encrypted-to-holder blob until pickup confirmed (relay-observed *or* heartbeat-attested)" rule in the deposit flow
- [-] `R` two-class relay TTL (action-requests generous / pushes short-latest-wins) — operator config; **not enforced by the relay code itself** (no TTL/GC job was written — the item's own framing is "the relay may GC aggressively under quota pressure," an operational deployment concern, not application logic to build pre-launch)
- [x] nonce/auth window stays 5 min (no change)

  **`R` implementation notes (2026-08-18):** `hexagons/relay/src/main/scala/value_objects/CustodyHeartbeat.scala` (`id, holderKey, ownerKey, secretIds, optedOut, signature, createdAt`) + `PayloadCanonical.forHeartbeat(ownerKey, secretIds, optedOut)` (sorts `secretIds` before joining, so the signed bytes are independent of list-construction order) + `driving_ports/CustodyHeartbeats` (`pushHeartbeat`, `listHeartbeats` — no delete method) + `driven_ports/persistence/CustodyHeartbeatRepository` (`upsertHeartbeat`, `getHeartbeatsForOwner`) + `driving_adapters/CustodyHeartbeatsService`. `app/driven_adapters/persistence/AnormCustodyHeartbeatRepository` — the one real gotcha: H2's PostgreSQL-compatibility mode doesn't support `ON CONFLICT ... DO UPDATE`, so `upsertHeartbeat` does a portable select-existing-id-then-branch-to-INSERT-or-UPDATE instead, the same pattern already established for partial-index emulation elsewhere in this codebase. `app/controllers/api/CustodyHeartbeatsController` (`POST`/`GET /custody-heartbeats`), routes, `ApiSupport.custodyHeartbeatJson`, `Module` binding, `conf/evolutions/default/1.sql` (`custody_heartbeats` table, `UNIQUE (holder_key, owner_key)`), `conf/openapi.yaml`. Tests: `PayloadCanonicalVectorTests` gained 3 `forHeartbeat` vector cases (fixed 32-byte seed, deliberately unsorted `secretIds` fixture, expected signature computed from an actual BouncyCastle run); `CustodyHeartbeatsServiceTests` (6 new, `InMemoryCustodyHeartbeatRepository` double); `CustodyHeartbeatsApiSpec` (7 new integration tests). `relay/test`: 83 → 92 tests.

  **`I`/`A` implementation notes (2026-08-18):** Both platforms landed on the same design independently. New value objects: `CustodyHeartbeat` (addressed to this device), `CustodyHeartbeatTuning` (`emissionInterval = 3 days`, `lossThreshold = 3×`, `staleWarningThreshold = 2×` — UI tuning, not load-bearing spec per the item's own framing), `RetainedDepositBlob` + a new `RetainedDepositRepository` driven port. `Contact` gained `heartbeatOptedOutAt`/`lastHeartbeatSentAt`/`heartbeatEmissionOptedOut`; `ShareMetadata` gained `lastConfirmedAt`. `ShareRelay` gained `pushHeartbeat`/`listHeartbeats`; `ShareManagement` gained `setHeartbeatEmissionOptedOut`. `ShareService.deposit()` now saves a `RetainedDepositBlob` per holder (safe to retain — each blob is encrypted to the *holder's* X25519 key, so the sender can't decrypt it herself); `syncDistributed()` was rewritten to gate the freshness stamp on `isRetentionStillPending(id)` rather than the row's `Approved` state alone (a **one-time transition** — an unchanging Approved row on a later poll must not keep re-stamping freshness, or a long-dead holder would look perpetually confirmed), to poll approved retrievals purely for their freshness side effect, and to call the new `processHeartbeats()`; `syncInbox()` calls the new `emitHeartbeats()`. `emitHeartbeats()` (holder side) coalesces one signed heartbeat/opt-out notice per distinct sender once due, advancing `lastHeartbeatSentAt` **only on a successful push** (so a transient relay outage retries next poll rather than waiting a full interval). `processHeartbeats()` (owner side) auto-verifies each notice against the holder's trusted key, captures/clears `heartbeatOptedOutAt` the instant it's observed (durable and local — mirrors item 10's `KeyConflict` pattern, since the relay may lose its state at any time), and stamps `lastConfirmedAt` on matching `ShareMetadata`. Android/iOS UI: a `FreshnessBucket` enum feeding a freshness-gated `n_live` on `SecretGroup.health`, stale/unmonitored/silent-overdue labels on the Distributed tab, and a per-contact "Pause/Resume Heartbeats" Contacts-screen action. Tests: iOS `ShareServiceTests` gained 15 new cases (widened `makeService()`'s tuple 6→7 across 19 destructured call sites plus 3 direct constructions; `FakeShareMetadataRepository.save` had to be fixed from append-only to upsert — a pre-existing bug the new freshness tests exposed) plus 2 `PayloadCanonicalVectorTests` cases; `swift test` 73/73. Android `ShareServiceTest` gained 13 new cases (`FakeShareMetadataRepository.save` proactively fixed to upsert *before* running any tests, applying the iOS lesson — all 13 passed first try) plus 3 `PayloadCanonicalVectorTest` cases (byte-identical signature match confirmed against the relay's fixture); `:hexagon:test` 62 → 78. `:app:compileDebugKotlin` and full `./gradlew test` green.

  **`phon` implementation notes (2026-08-18):** Consistency, not parity — full data model + `ShareService` gating logic (deposit retention, freshness stamping, heartbeat emit/process, opt-out toggle), no HTMX UI (no toggle button, no freshness badge — phon's minimal views have no plumbing for either yet, matching every prior item's precedent). New `value_objects/svo/CustodyHeartbeat.scala`, `CustodyHeartbeatTuning.scala`, `RetainedDepositBlob.scala` + `driven_ports/RetainedDepositRepository.scala`. `Contact.scala`/`Share.scala` gained the same fields as Android/iOS; `PayloadCanonical.forHeartbeat` mirrors the relay's construction byte-for-byte; `ShareRelay.scala` gained `pushHeartbeat`/`listHeartbeats`; `ShareManagement.scala` gained `setHeartbeatEmissionOptedOut`. `ShareService.scala`'s `deposit`/`syncDistributed`/`syncInbox` were extended identically to Android's shape (Scala `Option`/`Try` in place of Kotlin nullable/`runCatching`), with the same first-observation retention-gating fix applied *before* running tests (the `FakeShareMetadataRepository.save` upsert bug had already been caught twice by this point — Android and iOS — so it was fixed proactively here too). Root-app wiring: `HttpClientShareRelay` gained `pushHeartbeat`/`listHeartbeats` HTTP calls against the real `POST`/`GET /custody-heartbeats` endpoints; new `driven_adapters/phon/FileRetainedDepositRepository.scala` (structurally identical to `FileShareRepository.scala`); bound in `PhonModule.scala`. Tests: `ShareServiceSignatureTests` gained 14 new cases via a `FakeRetainedDepositRepository` test double and extending `FakeShareRelay` with heartbeat push/list tracking — needed widening `newService`'s return tuple from 6 to 7 across 19 destructured call sites (`ShareServiceSignatureTests.scala`) plus 2 in the separate `ShareRelayResolverFanOutTests.scala` file (same package, so the new fake is visible there without duplication); `FakeShareMetadataRepository.save` fixed from append-only to upsert alongside the others. `phon/test`: 57 → 71 tests, 0 failures. Full project `sbt test` (relay 92 + phon 71 + root 58 = 221) green.

### Item 13 — Retrieve fan-out beyond `k` + reconstruction integrity · [CLAUDE.md#13](CLAUDE.md)
Client-only; `relay` untouched. Integrity via over-determination **only** (no stored commitment).
- [ ] `A` `I` `reconstruct(secretId)` fans out to item-12's `Confirmed` fresh set (widen if `< k`), collects until `k` **consistent** shares (first `k` win)
- [ ] `A` `I` cross-check any surplus to detect (`k+1`) / identify-and-exclude (Reed-Solomon `⌊m/2⌋`) a bad or malicious share
- [ ] `A` `I` "reconstructed without integrity margin" advisory when `n_live == k`

---

## Cross-cutting implementation chores (not tied to one item)

- [ ] `R` rename `SharesService.scala` → `ShareRequestsService.scala` (+ tests) to match the class name
- [~] `R` sync `conf/openapi.yaml` with the Play routes as items 8/9/12 add message types (recovery-metadata-return, rotation push, custodial-heartbeat, opt-out, "withdrawn" row state) — item 8's `inventory` type + `k`/`n` fields done; items 9/12's message types still pending
- [~] `R` sync `conf/evolutions/default/1.sql` for any new row states/types; keep the production-PostgreSQL partial-index note (one-pending-request-per-type enforced in `ShareRequestsService`) — item 8's `inventory` enum value + `k`/`n` columns done; items 9/12's row states still pending
- [ ] `doc` propagate items 6–13 into `Android/CLAUDE.md`, `iOS/CLAUDE.md`, and each repo's `README.md`/`CHANGELOG.md` as they land
- [ ] `doc` refresh `MEMORY.md` stale notes when touched (e.g. iOS package layout under `driving_adapters/`, `ShareRequestsService` name mismatch)
- [x] `R` `A` `I` `phon` **`ShareTransactionType` rename + wire-representation cleanup** (flagged Aug 2026, naming finalized in a follow-up design discussion, loosely tied to item 8) — **done, all four scopes**:
  1. **Rename `ShareRequestType` → `ShareTransactionType`.** Not every case is a consent-gated "request" — the fourth is a self-approving, holder-initiated push — so "Transaction" is the honest umbrella term. "Share," not "Secret": every row is scoped to one share held by one holder, never the secret as a whole. Not bare `TransactionType`: item 5's (parked) freemium IAP will eventually want its own purchase-transaction concept, and a bare name risks colliding with it later. **Scope is deliberately narrow** — only the type, its four cases, and the `requestType`→`transactionType` field/property rename. `ShareRequest` (the row/case class), the `share_requests` DB table, `ShareRequestsController`/`ShareRequestsService`, and the `Shares`/`ShareRequests` port are **unchanged**: a row is still meaningfully "a request" for 3 of the 4 transaction kinds.
  2. **Rename the four cases to transaction nouns, one consistent vault register**, replacing the earlier "verbs from Alice's POV" idea — that broke down because the *actor* genuinely alternates: Alice always opens `pick_up`/`retrieve`/`delete`, but the *holder* opens the fourth type (holder → owner), so no single named person's viewpoint stays accurate across all four. A neutral transaction noun sidesteps that, and a safe-*deposit*-box supplies the register:
     - `pick_up` → **`Deposit`** / `deposit`
     - `retrieve` → **`Retrieval`** / `retrieval`
     - `delete` → **`Removal`** / `removal` (not `Deletion` — stays in the vault register with the other three instead of switching to database vocabulary for just one case)
     - `recovery_metadata` → **`Inventory`** / `inventory` (a bank reporting "here's what's in your box" without handing over the contents — avoids implying the whole secret is "recovered," and avoids `Metadata` as a name that could mean too many other things elsewhere in a software app)
     All four wire strings end up as single lowercase nouns — no more snake_case compounds (`pick_up`, `recovery_metadata`) to keep in sync across DB enum labels, JSON, and `PayloadCanonical`'s signed bytes.
  3. **Wire-representation architecture is inconsistent across platforms**, independent of naming, and worth fixing in the same pass since the rename touches every call site anyway:
     - **Swift** bakes the wire string as the enum's own `rawValue` (`enum ShareRequestType: String { case pickUp = "pick_up", ... }`, `hexagon/Sources/value_objects/Share.swift`) and reuses it directly in both `PayloadCanonical.swift` (signing) and `Deposplit/api/DeposplitApiAdapter.swift` (JSON) — one source of truth, but it puts wire representation inside the hexagon's domain value object.
     - **Scala and Kotlin** keep the enum bare (no wire strings) but then **hand-duplicate** the case→string mapping independently in several places with no shared source: relay has it in `PayloadCanonical.scala`, `ApiSupport.scala`, `ShareRequestsController.scala` (×2), and `AnormShareRepository.scala` (×2); phon mirrors this in `PayloadCanonical.scala` + `HttpClientShareRelay.scala`; Android has it in `PayloadCanonical.kt` + `DeposplitApiAdapter.kt`. Every new case (most recently `recovery_metadata` for item 8) means updating all of these by hand — a real risk of missing one.
     - **Resolved for the relay, converging Scala toward Swift's model rather than the reverse.** `ShareTransactionType` (`hexagons/relay/src/main/scala/value_objects/ShareTransactionType.scala`) is now a parameterized enum carrying its own `wireValue: String` per case plus a companion `fromWire(s: String): Option[ShareTransactionType]` — the same "wire string lives on the enum itself" shape Swift already had, since `PayloadCanonical` (domain, on every platform) needs the wire string for signing regardless, so embedding it there isn't actually a hexagonal-purity violation, just consolidation. Every adapter (`ApiSupport`, `ShareRequestsController`, `AnormShareRepository`) now calls `.wireValue`/`.fromWire` instead of hand-rolling a `match`. **Android should follow the same shape** (a `wireValue` per enum constant + a `fromWire` companion) when its turn comes, rather than inventing a third approach; Swift's existing `rawValue` enum needs no architectural change, only the rename.
  4. **The cross-platform `PayloadCanonical` signature vector test will need recomputing**, same as item 8's `k`/`n` addition did — the signed byte sequence embeds the wire string, so its length changes; regenerate the fixture's expected signature from an actual BouncyCastle run rather than hand-deriving it. **Done for the relay**: recomputed to `49sMax0jpKfyXdIIiwi6xeKKyK5MZwGOur9I499SXiTneVBYc5Juv215DTDcHhpphU2YGZpqMYRZKNFVILw7AA` (Deposit/`deposit`, `k=2, n=3` fixture) — Android's and iOS's vector tests must be updated to match this exact value once their `PayloadCanonical`/`forOpen` calls use the new type/wire string, the same cross-platform-agreement check item 8 did.
  This is a wire-protocol rename (DB enum labels in `1.sql`, the JSON field name + its value strings, and the bytes `PayloadCanonical` signs) — breaking, but fine pre-launch per the no-migrations policy: edit `1.sql` in place, no evolution needed. Follow the established **`R` first (foundational — DB schema + wire protocol), then iOS → Android → phon** order, as item 8 used; port to phon for consistency, not full parity.

  **`R` implementation notes:** `ShareRequest.requestType` → `.transactionType`; the JSON field `requestType` → `transactionType` (`ApiSupport.shareRequestJson`, `ShareRequestsController`); the `?type=` query param on `GET /share-requests` keeps its short name (was never called `requestType`) but now accepts the four new wire values. DB: `request_type` column → `transaction_type`, `share_request_type` enum type → `share_transaction_type` (both renamed in `1.sql` in place, per no-migrations). `ShareRepository.hasActivePickUp` → `hasActiveDeposit` (port + Anorm impl) since it directly embedded the old case name and every call site was already being touched. `conf/openapi.yaml` fully updated (`ShareRequestType` schema → `ShareTransactionType`, all four enum values, `requestType` → `transactionType` on both `ShareRequest` and `OpenShareRequestBody`, path descriptions). Hit the H2 semicolon-in-`--`-comment gotcha again while rewriting `1.sql`'s prose (two new instances, self-inflicted) — fixed the same way as item 8 (semicolon → comma); worth double-checking for this on every future `1.sql` prose edit. 69 relay-hexagon + 39 root tests pass (108 total, unchanged from pre-rename — `phon`'s 37 untouched and now wire-incompatible with the relay until its turn, expected and matching item 8's precedent).

  **`I` implementation notes:** `ShareRequestType` (`String` rawValue enum, `hexagon/Sources/value_objects/Share.swift`) → `ShareTransactionType` with cases `deposit`/`retrieval`/`removal`/`inventory` — no architecture change needed (Swift's rawValue enum was already the single-source-of-truth shape the relay converged toward), just the rename. `ShareRequest.requestType` → `.transactionType`; `ShareRelay`/`ShareManagement` port params and `PayloadCanonical.forOpen`'s param renamed to match; `DeposplitApiAdapter`'s JSON wire structs (`OpenShareRequestJSON`/`ShareRequestJSON`) renamed `requestType` → `transactionType`. **Kept `ShareManagement.pushRecoveryMetadata`/`ShareService.processRecoveryMetadata` unchanged** — a deliberate distinction: these name the item-8 *feature* (identity recovery), not the wire transaction type, the same way a `refundPurchase()` method can be implemented via a generic `Transaction(type: .credit)` without needing its own name to track the generic type's. Applied the same distinction throughout `ShareServiceTests.swift`: renamed test names describing *mechanism* behavior (e.g. `...ADepositWith...`, `...ADepositWhose...`) but kept test/helper names describing the *feature* (`makeApprovedRecoveryMetadataRow`, `pushRecoveryMetadataOpens...`, `syncInboxProcessesAnApprovedRecoveryMetadataPush...`) — Android/phon should follow the same split when their turn comes. UI display strings updated too, since they were one line from the code being changed anyway: `RecipientRequestsTab`'s badge labels and `ShareDetailView`'s request-row labels now read "Deposit"/"Retrieval"/"Removal"/"Inventory". `HolderStatus.retrieveRequest` → `.retrievalRequest` (`HomeViewModel.swift`, referenced from `DistributedTab.swift`) since it directly embedded the old case name. Cross-platform signature vector recomputed to match the relay's exact value (`49sMax0jpKfyXdIIiwi6xeKKyK5MZwGOur9I499SXiTneVBYc5Juv215DTDcHhpphU2YGZpqMYRZKNFVILw7AA`) — confirmed byte-identical canonical construction across BouncyCastle and CryptoKit. `swift build`/`swift test` (41/41 pass) and a full `xcodebuild` of the `Deposplit` app target (BUILD SUCCEEDED) both verified.

  **`A` implementation notes:** `ShareRequestType` (`hexagon/.../value_objects/Share.kt`) → `ShareTransactionType(val wireValue: String)` with a `fromWire` companion, following the relay/iOS-converged single-source-of-truth shape — every adapter (`DeposplitApiAdapter`, `PayloadCanonical`) now calls `.wireValue`/`.fromWire` instead of the hand-rolled `toWire()` extension functions that existed in both `PayloadCanonical.kt` and `DeposplitApiAdapter.kt` (now deleted). `ShareRequest.requestType` → `.transactionType`; ports (`ShareRelay`, `ShareManagement`) and `ShareService` renamed to match. Kept `ShareManagement.pushRecoveryMetadata`/`ShareService.processRecoveryMetadata` unchanged, same feature-vs-mechanism reasoning as iOS. Mechanism-level UI/state renamed throughout since every call site was already being touched: `HolderStatus.retrieveRequest` → `.retrievalRequest` (`HomeViewModel.kt`, also referenced from `HomeScreen.kt` — caught by grep, not by the initial file survey, since that file only used the field, not the type); `ShareDetailViewModel.UiState`'s `retrieveRequest`/`deleteRequest`/`approvedRetrieveCount`/`isOpeningRetrieve`/`isOpeningDelete` → `retrievalRequest`/`removalRequest`/`approvedRetrievalCount`/`isOpeningRetrieval`/`isOpeningRemoval`, and `openRetrieveRequest()`/`openDeleteRequest()` → `openRetrievalRequest()`/`openRemovalRequest()`. String resources renamed to match (`share_request_pick_up`/`_retrieve`/`_delete`/`_recovery` → `_deposit`/`_retrieval`/`_removal`/`_inventory`; `share_detail_retrieve_button`/`_delete_button` → `_retrieval_button`/`_removal_button`, English text "Request Deletion" → "Request Removal") in both `values/strings.xml` and `values-de/strings.xml` — noted but did not backfill a pre-existing gap where German never had a `share_request_pick_up` translation to begin with. Test fixture helper `pickUpRow` → `depositRow` (mechanism-level, every call site already touched), test names split the same way as iOS (mechanism-level renamed, `pushRecoveryMetadata`/`recoveryMetadata`-named tests kept). Cross-platform signature vector recomputed to the same relay/iOS value. `:hexagon:test` 41/41 pass (unchanged count from pre-rename) and `:app:compileDebugKotlin` BUILD SUCCESSFUL (pre-existing unrelated warnings only) both verified; `:app:test` still blocked by the pre-existing unrelated jlink toolchain issue noted since item 8, not investigated further.

  **`phon` implementation notes:** Mirrored the relay's `ShareTransactionType(val wireValue: String)` + `fromWire` companion exactly (phon can't depend on `relay` — separate sbt subprojects — so it needed its own copy, same as it already had its own copy of `PayloadCanonical`). `HttpClientShareRelay.scala` (`app/driven_adapters/phon/` — the live HTTP client phon uses to talk to the actual deposplit.com relay) is the load-bearing file here: its hand-rolled `requestTypeStr` match was deleted in favor of `.wireValue`/`ShareTransactionType.fromWire`, and its JSON field `"requestType"` → `"transactionType"` — this is what makes phon wire-compatible with the relay again (it had been left broken on purpose since the `R` step, matching item 8's precedent). Also caught and fixed a Twirl view, `app/views/Phon/pendingRequests.scala.html`, which rendered `@shareRequest.requestType` directly — missed by the initial grep sweep (which was scoped to `.scala` files) and only found via a second, more targeted grep once the `find`-based sweep's results looked incomplete; a reminder to grep view templates explicitly, not just source files, when a field rename touches a type rendered in HTMX views. Kept `ShareManagement.pushRecoveryMetadata`/`ShareService.processRecoveryMetadata` and their test names (`approvedRecoveryMetadataRow`, `pushRecoveryMetadata opens...`) unchanged, same feature-vs-mechanism split as iOS/Android; renamed the mechanism-level test fixture `pickUpRow` → `depositRow` in both `ShareServiceSignatureTests.scala` and `ShareRelayResolverFanOutTests.scala` (the latter has its own private copy, not shared). Full `sbt test` — 69 relay + 37 phon + 39 root = **145 tests, all passing, exactly matching the pre-rename total** — confirms the entire backend (relay + phon) is wire-consistent end-to-end, not just independently compiling.

---

## Recently decided (spec walk, Aug 2026)

Items 4–13 and the C4/C5 sub-decisions were settled at the specification level this
month; see `CLAUDE.md` → "What is next" for the reasoning and the commit trail. Tier C is
cleared (item 12 resolved #5; #3 relay-kinds parked). Items 6, 7, 8, 10, 11, and 12 have since
shipped (see `CHANGELOG.md`); item 9's rotation push and withdraw tombstone have too, across
every platform, and its health-check piece shipped separately under item 12's reshape — its one
remaining piece, the "regenerate my own identity" trigger, was deliberately scoped out, not
merely pending. All other implementation above is pending.
