# Deposplit — open work

What is left, across all three repositories. One board rather than three, because the work
fans out across them and a solo developer plus an agent is better served by a single list.

Shipped work is not recorded here. What the system does and why is documented in
[`docs/`](docs/); how it got there is in the git history.

**Status:** `[ ]` not started · `[~]` in progress
**Scope:** `R` relay/backend · `phon` phone emulator · `A` Android · `I` iOS · `doc` documentation

---

## End-to-end interop testing

Nothing here is blocked; it needs devices and an hour. Flows are written up in
[docs/testing.md](docs/testing.md).

- [ ] `A` `I` Android deposits → iOS approves → retrieval → Android reconstructs, against a live `sbt run`
- [ ] `A` `I` BYOR variant: two relays on different ports, one contact with a `relayBaseUrl` override, one without — verify routing and independent soft-failure
- [ ] `A` `I` reconstruction integrity with a surplus holder: confirm the advisory reports the margin honestly

## Chores

- [ ] `phon` **phon cannot upload an image.** Its hexagon already enforces the 256 KiB cap and recognises PNG/JPEG, but the deposit form is still text-only, so phon can demonstrate a `text/plain` deposit and nothing else. Adding a file input means switching `createMySecret` from a URL-encoded `Form` to a multipart body, and dropping the `.trim` that assumes the secret is text. Worth having for teaching: phon cannot reconstruct, so sending an image to a real device is the only way to show the flow without two phones.
- [ ] `doc` arrows overlap in the C4 system-context and container diagrams in [docs/architecture.md](docs/architecture.md). Cosmetic, deliberately deferred. Mermaid's C4 renderer offers little layout control — `UpdateLayoutConfig` with `$c4ShapeInRow`/`$c4BoundaryInRow` is the usual lever, and converting a diagram to a styled `flowchart` gives full control at the cost of the C4 shape vocabulary.

## Parked

Not rejected — waiting on something.

- [ ] `A` `I` **In-app camera capture as a secret input.** Today an image must already exist in the photo library or in Files, which means a photo of a seed phrase has been sitting in the camera roll — and syncing to iCloud or Google Photos — before it is ever split. A capture kept in memory and split immediately never touches either. Both platforms already declare a camera permission for QR scanning, and both already have a camera surface to copy (`DataScannerViewController`, CameraX).
- [ ] `A` `I` **Widen the accepted image formats past PNG and JPEG.** HEIC is the obvious first candidate: an unmodified iPhone photo is HEIC, so today it is refused on type as well as size. Each addition is more decoder surface reached by attacker-chosen bytes, so this is a deliberate trade rather than an oversight. SVG stays out — it is scriptable.
- [ ] `A` **Google Play Billing adapter.** The entitlement port, both gates and the paywall ship; on Android there is simply nothing behind the purchase button, and the screen says so. Blocked on Play infrastructure rather than on code: Play Billing talks to the Play Store app and has no offline mode, so one test purchase needs a Play Console entry for `com.deposplit`, the `com.deposplit.premium` product active, a build on at least the internal test track, and a registered license tester — and Google scopes testing to hardware devices, with the emulator's purchase flow unreliable even on a Play system image. When it lands it only has to write the preference `SharedPreferencesPurchaseRepository` already reads; nothing else moves. iOS needed none of this, because a StoreKit configuration file buys the unlock in the Simulator with no App Store Connect record at all.
- [ ] `I` **Rehearse the iOS purchase against the App Store Sandbox.** StoreKit Testing in Xcode proves the code path but not the App Store Connect record: the product has to exist there with a real price, and be bought once by a sandbox Apple ID on a physical device before release. No code change expected — the local `Deposplit.storekit` becomes redundant for that run rather than wrong.
- [ ] `R` `A` `I` **Airtable / Google Sheets relay kinds.** Wanted, but gated on the default relay demonstrating real adoption. Each non-REST kind needs its own adapter and wire shape, plus a `relayKind` discriminator on `Contact`; that is speculative surface area until people are actually using Deposplit. No design work before then.
- [ ] `R` `phon` `A` `I` **Relay row TTL.** Two retention classes — generous for consent-gated action requests, short and latest-wins for fire-and-forget pushes. No TTL or collection job exists; this is deployment configuration, not application logic, and no correctness property depends on *having* one — though one depends on how it is introduced, below. See "absence is never a signal" in [docs/protocol.md](docs/protocol.md). **Collection cannot land alone.** A `deposit` row carries the only copy of a share in transit, and the sender's retained blob is the prerequisite for surviving its collection — but nothing re-deposits from that blob today, so shipping a collection job without the client half would introduce exactly the loss the retention exists to prevent. The client half is bigger than it looks: a re-deposit mints a fresh request id relay-side, while the blob's `id` *is* the original request id — the key `ShareMetadata`, `syncDistributed` and `isRetentionStillPending` all join on — so both local records need re-keying. It need not detect collection, though. Attempt the re-deposit unconditionally and let `hasActiveDeposit` answer `Conflict` while the row is still there, which keeps "absence is never a signal" intact.

## Reminder to Paul wrt **Relay row TTL.**

Here's the whole picture, re-derived from the code as it stands after today (August 3rd, 2026).

  What the item actually asks for

  The relay has no TTL and no collection job at all today. Rows accumulate forever. The parked item is about adding
  retention — two classes, because the rows do two different jobs:

  - Consent-gated action requests (deposit, retrieval, removal) want generous retention, so an offline counterparty can
    still act on one.
  - Fire-and-forget pushes (rotations, inventory, heartbeats) want short, latest-wins retention — a heartbeat superseded
    by a newer one is dead weight.

  The entry stresses this is deployment configuration, not application logic. Nothing in the relay code needs to know a
  TTL exists.

  Why collecting rows is safe in general

  One invariant carries it, from protocol.md:

  ▎ A missing row means "collected, or never sent". It never means "done", and it never means "lost".

  Everything is either idempotently re-emitted on the next poll (heartbeats, rotations, inventory pushes, tombstones) or
  re-issuable by the user (action requests). Clients reconcile by upsert, never delete — a row's disappearance must
  never make a client forget something.

  The one row where that breaks

  A deposit row carries the only copy of an encrypted share in transit. The relay is genuinely storage there, not a
  mailbox. Collect it before Bob picks up and the share is gone — and it can't be cheaply re-minted, because the
  polynomial isn't retained. Alice would have to re-split the whole secret and re-deposit to everyone.

  What already exists to cover that

  RetainedDepositBlob. At deposit() (ShareService.kt:119-128) Alice saves each per-holder ciphertext locally, right
  beside the ShareMetadata. It's safe to keep because the blob is encrypted to Bob's X25519 key — Alice cannot decrypt
  it. Holding all n is n opaque forward-only blobs, not a reconstructable secret sitting on one device.

  She discards each blob once that holder's pickup is confirmed, through either channel: the relay showing the deposit
  approved (syncDistributed), or Bob's signed heartbeat naming that secretId (processHeartbeats).

  What's missing

  Nothing ever reads ciphertext back. There is no re-deposit. The blob's only live function today is as a marker —
  isRetentionStillPending(id) means "this holder's pickup is not yet confirmed by any channel", which is what gates the
  one-time freshness stamp.

  Hence the entry's warning: collection cannot land alone. Shipping a relay-side GC without the client half would
  introduce precisely the loss the retention exists to prevent. The blob is the prerequisite, not the mechanism.
  Meanwhile the gap costs nothing, because no collection job exists to trigger it.

  Why the client half is bigger than it looks — the coupling

  One UUID does triple duty. The relay mints it (ShareRequestsService.scala:116, id = UUID.randomUUID()), and Alice
  adopts it as the primary key of two local records:

  shareMetadataRepository.save(ShareMetadata(req.id, secretId, contact.id))
  retainedDepositRepository.save(RetainedDepositBlob(req.id, secretId, contact.id, …))

  So ShareRequest.id == ShareMetadata.id == RetainedDepositBlob.id, and three things join on it: syncDistributed's
  existingMetadata.find { it.id == req.id }, isRetentionStillPending(req.id), and reconcileDiscarding's
  relay.deleteShareRequest(meta.id).

  A re-deposit is a fresh POST /share-requests, so the relay mints a new id and the old one names nothing. Both local
  records must be re-keyed to it, atomically. Miss it and the failure is silent, not loud: the metadata is orphaned from
  its relay row, the blob is never discarded, lastConfirmedAt is never stamped again, and the health model shows a
  permanently unconfirmed holder — for a share that actually arrived.

  There's already a visible seam here. processHeartbeats finds its metadata by (secretId, contactId) and uses meta.id
  only as a key into the blob store. syncDistributed joins on the relay id directly. Two channels, two conventions — and
  processRecoveryMetadata mints ShareMetadata(UUID.randomUUID(), …), metadata the id-based join structurally cannot
  match. That doesn't bite today only because a recovered Alice's old rows are addressed to her old key and therefore
  invisible to her. The id-join is correct by virtue of a separate invariant; the pair-join is correct unconditionally.

  Why removing shareId was the precondition

  Until this morning that same relay-assigned id also travelled on the wire, signed into forOpen, on every retrieval and
  removal. Re-keying would have meant it was baked into signatures on rows already sitting on the relay — you'd have
  live, valid, signed rows pointing at an id that no longer named anything on either side, and no way to fix them
  without invalidating the signature.

  Now that id appears in exactly three local joins on one device. Decoupling became a change you can make in one file
  per platform instead of a protocol change.

  What decoupling would look like

  Give ShareMetadata its own locally-generated id — which the recovery path already does — and join to relay rows on
  (secretId, contactId) / (secretId, recipientKey), exactly as processHeartbeats, requestRetrieval and everything from
  today's commits already do. Then a re-deposit changes nothing locally: the relay row's id becomes the relay's business
  alone.

  That's still not the TTL. It's the piece that makes the TTL's client half tractable.

● User answered Claude's questions:
  ⎿  · Do you want any of this turned into a plan now? → Nothing for now (Recommended)

● Agreed — that's the right call. It's parked because nothing is deployed and no collection job exists, so the gap is
  inert. What today's work changed is the cost of unparking it: the id that would need re-keying no longer travels on
  the wire or inside a signature, so when a TTL does become real, the client half is three local joins on one device
  rather than a protocol change.

  Worth leaving as-is in TODO.md too. The entry's reasoning still holds verbatim — RetainedDepositBlob.id is still the
  deposit request id, and ShareMetadata, syncDistributed and isRetentionStillPending still join on it.
