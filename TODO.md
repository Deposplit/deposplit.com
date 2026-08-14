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

> ⚠ **Items 7–13 are design-complete but not yet built** (item 6 shipped — see below). The
> current code still implements the *pre-7–13* model those items supersede. `No migrations`
> throughout — Deposplit is pre-launch; test relays and devices reset to a clean slate.

---

## Where to start / dependencies

- **Item 7 (holder-decrypts-at-pickup crypto redesign)** is the foundation: it reshapes
  `HeldShare` and `ShareMetadata`, and items **8, 9, 12, 13** all assume its model. Nothing
  precedes it, and much depends on it — a natural first big piece.
- **Item 6 (four-level verification) is done** — it was independent of the crypto redesign
  (enum + UI, no crypto dependency), so it shipped first as a low-risk warm-up. Item 10 will
  later lean on its levels.
- **Item 11 (secret lifecycle)** is partly independent: its `reconstruct()` bug-fixes
  (enforce real `k`, stop auto-teardown) can land early; its `Secret` aggregate feeds items
  9 and 13. Uses the `contactId` anchor introduced by item 7.
- Rough dependency order for what's left: **7 → 11 → {8, 9} → {10, 12} → 13**.

---

## Open items with remaining work (1–5)

### Item 1 — iOS biometric unlock · [CLAUDE.md#1](CLAUDE.md)
- [ ] `I` gate `reconstruct()` behind `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)` in `ShareDetailView` (Android already gates via `BiometricPrompt`)

### Item 2 — End-to-end interop testing · [CLAUDE.md#2](CLAUDE.md)
- [ ] Android deposits → iOS approves PickUp → later Retrieve → Android reconstructs, against a live `sbt run`
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

## Planned items (6–13) — item 6 done, 7–13 design-complete but not yet built

### Item 6 — Four-level contact verification · [CLAUDE.md#6](CLAUDE.md)
`VERY_LOW`/`LOW`/`HIGH`/`VERY_HIGH`, ordinal. Old `UNVERIFIED`/`VERIFIED` would map onto
`VERY_LOW`/`VERY_HIGH` conceptually, but **no on-device migration code was written or is needed** —
Deposplit is pre-launch; the relay DB has been purged and all emulators/simulators reset clean.
- [x] `phon` expand `VerificationLevel` value object 2→4, ordinal/comparable (`relay` untouched — never stores contacts)
- [x] `A` expand enum + contact record + add-contact level picker + guidance text
- [x] `I` same
- [x] `doc` rewrite "Contact Verification" section (+ "Ready/Not added" & "Contacts Management" refs) 2→4
- [x] `doc` identity-recovery approver weighting references 4 levels (rule itself still TBD — walk separately)

### Item 7 — Holder-decrypts-at-pickup crypto redesign · [CLAUDE.md#7](CLAUDE.md)
Client-only; `relay` + DB schema untouched (still opaque bytes).
- [ ] `A` `I` pickup: decrypt with holder X25519 priv + sender X25519 pub → store **plaintext** share
- [ ] `A` `I` retrieve: re-encrypt plaintext to *current* sender's X25519 pub; sender decrypts + `combine`
- [ ] `A` `I` `HeldShare`: `ciphertext`→`plaintextShare`; `senderKey`→`contactId`; optional denormalized `pseudonym` snapshot
- [ ] `A` `I` `ShareMetadata`: `recipientKey`→`contactId`
- [ ] `A` `I` precondition: rotation/recovery updates existing contact **in place**, preserving `contactId`

### Item 8 — Identity recovery (holder-driven metadata reconstitution) · [CLAUDE.md#8](CLAUDE.md)
Pure-social, `k`-of-`n` by construction; recovery returns **metadata only**, never shares.
- [ ] `R` `A` `I` new metadata-only recovery message type (`{secretId, label, secretCreatedAt, holder-identity, k, n}` push)
- [ ] `A` `I` `updateContact(contactId, newKeys?, newLevel?)` contact-update-in-place primitive + UI (preserve `contactId`; steer away from delete+add; key change **forces** re-choosing verification level — no silent inherit)
- [ ] `R` `A` `I` add `k`/`n` to pick_up payload + `PayloadCanonical` + `HeldShare`/`ShareMetadata`
- [ ] `A` `I` re-key `retrieve` on `secretId` (not the transient pickUp relay-row id)
- [ ] `A` `I` optional catalog export/import (non-secret catalog: contact pubkeys, pseudonyms, levels, `ShareMetadata`)

### Item 9 — Holder-key-change handling + redundancy monitoring · [CLAUDE.md#9](CLAUDE.md)
NB: the health-check is a **push** — reshaped by item 12 (see below).
- [ ] `R` `A` `I` signed `rotate(K_old→K_new)` push; auto-verify against trusted old key; update contact in place
- [ ] `A` `I` reconstruct-and-re-split repair flow (shared with item 11)
- [ ] `R` `A` `I` "withdrawn by recipient" row state + tombstone-on-delete + `syncDistributed()` handling (row *absence* never a signal)

### Item 10 — Malicious key substitution + stolen-key revocation · [CLAUDE.md#10](CLAUDE.md)
- [ ] `A` `I` `K_old`-signed rotation auto-accept with `min(level, LOW)` downgrade (ties to item 9)
- [ ] `A` `I` local "compromised/revoked" key flag → disables auto-accept of that key's rotations
- [ ] `A` `I` conflict-resolution UI for two competing "current keys" (never auto-resolved)
- [ ] `A` `I` "requester's key changed N days ago" indicator + fresh-OOB nudge on approve-retrieve screen

### Item 11 — Secret lifecycle · [CLAUDE.md#11](CLAUDE.md)
Bounds `2 ≤ k ≤ n ≤ 255` (hard, no UI ceiling). Hexagon only; `relay` untouched.
- [ ] `A` `I` `Secret(secretId, label, k, n, secretCreatedAt, state)` aggregate; `ShareMetadata` normalized to reference it
- [ ] `A` `I` `reconstruct(secretId)` enforces `approved.size >= k` — **remove hardcoded `check(approved.size >= 2)`**
- [ ] `A` `I` `reconstruct()` becomes a **pure read** — **remove the auto-teardown** of local `ShareMetadata` + relay rows
- [ ] `A` `I` `discardSecret(secretId)` fan-out consent-gated `delete` primitive + "discard secret" UI
- [ ] `A` `I` two-state `ACTIVE`/`DISCARDING` + health-alarm suppression while `DISCARDING`; record removed on teardown/force-forget
- [ ] `A` `I` split-time three-axis soft warnings (operational burden / confidentiality tail `k` low vs `n` / availability tail `n−k` small)
- [ ] `A` `I` graduated `n_live` health alarm (`>=k+2` healthy · `==k+1` caution · `==k` critical · `<k` lost) — feeds item 9
- [ ] `A` `I` free-cap counts `ACTIVE` only; `discardSecret` frees the slot immediately (item 5 / C4)

### Item 12 — Polling, staleness & relay-TTL cadence (custodial-heartbeat) · [CLAUDE.md#12](CLAUDE.md)
Flips item 9's health-check pull→push. `relay` untouched (blind mailbox).
- [ ] `R` `A` `I` reshape item 9's health-check → holder-initiated **signed custodial-heartbeat push** (default-on, holder-disableable) + **signed opt-out notice** message type
- [ ] `A` `I` per-holder freshness clock + three buckets (`Confirmed` / `Unmonitored-by-choice` / `Silent-overdue`) feeding `n_live`; refreshed by any signed proof-of-custody (heartbeat / pickup / retrieve approval)
- [ ] `A` `I` "getting stale" early-nudge UI; long-silent holders drop out of `n_live` (reversible)
- [ ] `A` `I` client-side "retain encrypted-to-holder blob until pickup confirmed (relay-observed *or* heartbeat-attested)" rule in the deposit flow
- [ ] `R` two-class relay TTL (action-requests generous / pushes short-latest-wins) — operator config
- [x] nonce/auth window stays 5 min (no change)

### Item 13 — Retrieve fan-out beyond `k` + reconstruction integrity · [CLAUDE.md#13](CLAUDE.md)
Client-only; `relay` untouched. Integrity via over-determination **only** (no stored commitment).
- [ ] `A` `I` `reconstruct(secretId)` fans out to item-12's `Confirmed` fresh set (widen if `< k`), collects until `k` **consistent** shares (first `k` win)
- [ ] `A` `I` cross-check any surplus to detect (`k+1`) / identify-and-exclude (Reed-Solomon `⌊m/2⌋`) a bad or malicious share
- [ ] `A` `I` "reconstructed without integrity margin" advisory when `n_live == k`

---

## Cross-cutting implementation chores (not tied to one item)

- [ ] `R` rename `SharesService.scala` → `ShareRequestsService.scala` (+ tests) to match the class name
- [ ] `R` sync `conf/openapi.yaml` with the Play routes as items 8/9/12 add message types (recovery-metadata-return, rotation push, custodial-heartbeat, opt-out, "withdrawn" row state)
- [ ] `R` sync `conf/evolutions/default/1.sql` for any new row states/types; keep the production-PostgreSQL partial-index note (one-pending-request-per-type enforced in `ShareRequestsService`)
- [ ] `doc` propagate items 6–13 into `Android/CLAUDE.md`, `iOS/CLAUDE.md`, and each repo's `README.md`/`CHANGELOG.md` as they land
- [ ] `doc` refresh `MEMORY.md` stale notes when touched (e.g. iOS package layout under `driving_adapters/`, `ShareRequestsService` name mismatch)

---

## Recently decided (spec walk, Aug 2026)

Items 4–13 and the C4/C5 sub-decisions were settled at the specification level this
month; see `CLAUDE.md` → "What is next" for the reasoning and the commit trail. Tier C is
cleared (item 12 resolved #5; #3 relay-kinds parked). Item 6 has since shipped (see
`CHANGELOG.md`); all other implementation above is pending.
