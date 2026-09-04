# Deposplit — open work

What is left, across all three repositories. One board rather than three, because the work
fans out across them and a solo developer plus an agent is better served by a single list.

Shipped work is not recorded here. What the system does and why is documented in
[`docs/`](docs/); how it got there is in the git history.

**Status:** `[ ]` not started · `[~]` in progress
**Scope:** `R` relay/backend · `phon` phone emulator · `A` Android · `I` iOS · `doc` documentation

---

## End-to-end interop testing

Nothing here is blocked; it needs devices and an hour. [docs/testing.md](docs/testing.md) is
the how — starting a relay, pointing three instances at it, and what each flow proves. This
is the checklist for that document: tick an item only after running it on real instances
against a live relay, never after reading the code.

Items marked **(not written up)** have no flow in `docs/testing.md` yet, so they carry enough
detail here to be run as they stand. Promote one into that document if it earns a permanent
place rather than growing this list into a second manual.

### The documented flows

- [ ] `A` `I` **Flow 1 — happy path.** 2-of-2 across two holders. Watch step 10: pickup is where the holder decrypts and where the relay stops holding anything.
- [ ] `A` `I` **Flow 2 — deny and re-request.**
- [ ] `A` `I` **Flow 3 — sender-initiated removal.** The deposit row cascades to its retrieval and removal rows.
- [ ] `A` `I` **Flow 4 — holder-initiated deletion.** Alice must learn of it, and never by a row simply going missing.
- [ ] `A` `I` **Flow 5 — offline and error states.** Distributed and Held render from local storage behind a soft banner; the Requests tab legitimately errors, because it has nothing local to fall back on.
- [ ] `A` `I` **Flow 6 — cross-platform.** The highest-value flow in the document: the only test that proves CryptoKit and BouncyCastle agree on a live wire rather than only on the vectors.
- [ ] `A` `I` **Flow 7 — BYOR.** Two relays, one contact overridden and one not; killing one degrades only that contact. Needs the Premium unlock first.
- [ ] `A` `I` **Flow 8 — locale.** German throughout, dates in `dd.MM.yyyy`. Worth re-running now that several strings take format arguments.

### The documented edge cases

- [ ] `A` `I` Fresh keypairs after reinstall — existing contacts can no longer decrypt shares sent to the old ones.
- [ ] `A` `I` Reconstruct stays hidden until *k* approved retrievals exist for the same `secretId`.
- [ ] `A` `I` 2-of-3 with only two approvals still reconstructs.
- [ ] `A` `I` Integrity margin with a surplus holder: the advisory reports the margin honestly rather than claiming more confidence than it has.
- [ ] `A` `I` Verification levels: manual entry never offers `VERY_HIGH`; a QR scan defaults to it.
- [ ] `A` Biometric on a device with no enrolment explains itself instead of offering a button that cannot work.
- [ ] `A` `I` The key-change indicator appears on retrieval requests, and only on those.

### Pictures (not written up)

- [ ] `A` `I` **A JPEG survives the round trip byte for byte.** Deposit a JPEG on one platform, reconstruct on the other, export it, and `cmp` against the source file. JPEG rather than PNG on purpose: export used to re-encode to PNG while naming the file from the declared type, so a re-encode would hand back PNG bytes under a `.jpg` name and look right in a viewer.
- [ ] `A` `I` **The size cap holds at both ends.** A secret of exactly 256 KiB deposits and reconstructs — that is ~262 KB of ciphertext and ~341 KiB of base64 body, so it also proves the relay's 512 KiB and 1 MiB bounds have real headroom. One byte over is refused at pick time, naming the file's actual size, without the file being read into memory first.
- [ ] `I` **A straight-from-camera photo is refused by type.** An unmodified iPhone photo is HEIC. It must be turned away as an unsupported type, never silently transcoded to JPEG behind the user's back — verbatim or refused is the whole rule.
- [ ] `A` `I` **A repair carries an image through untouched.** Reconstruct an image secret, re-split it through the repair flow, reconstruct again, and `cmp` against the original. This is the path where bytes used to round-trip through a `String` and come back corrupted.
- [ ] `A` `I` **EXIF rides along, including GPS.** Deposit a photo carrying location EXIF and confirm it is still there after reconstruction on the other platform. [docs/security.md](docs/security.md) says this is deliberate; confirm the document and the code still agree.

### Freemium (not written up)

- [ ] `A` `I` **The cap counts active secrets, not lifetime deposits.** Three active secrets; the fourth is refused. Discard one and the fourth succeeds immediately — before any holder has confirmed the removal, since a discarding secret gives its slot up at once.
- [ ] `A` `I` **A repair at the cap is not blocked.** With three active secrets, run a repair through to the re-deposit. It must go through: the replacement supersedes an active secret rather than adding a fourth.
- [ ] `I` **Buying unlocks both relay editors without a relaunch.** Buy in the Simulator against the scheme's StoreKit configuration, then check Settings and Add Contact — both fields become editable, because they observe the store. Delete the transaction under Debug → StoreKit → Manage Transactions and confirm both lock again.
- [ ] `A` `I` **A free device can still share with a self-hoster.** Scan a QR whose payload names a non-default relay, on a device without Premium. The contact must be added with the override intact and deposits must route to that relay. This is the free half of BYOR, and the one gate whose mis-scoping would break interop rather than merely annoy.

### Identity and trust (not written up)

- [ ] `A` `I` **A share sealed before a rotation still opens after it.** Alice deposits for Bob, then regenerates her identity before Bob picks the share up. Bob's pickup must succeed from the retained previous key, and his later retrieval must come back sealed to Alice's *new* key. Run it with Alice and Bob on different platforms.
- [ ] `A` `I` **A rotation from a key flagged compromised is refused auto-accept.** Mark a contact's key compromised on one platform, then push a rotation from that contact on the other. It must land as a `KeyConflict` for manual resolution, never as a silent relink.
- [ ] `A` `I` **Identity recovery rebuilds the sender's view.** Alice reinstalls with fresh keys, relinks with Bob, and Bob pushes recovery metadata. Alice's Distributed view must come back — metadata only, with no share bytes crossing the wire.
- [ ] `A` `I` **A holder who opts out of heartbeating stops counting as confirmed.** Opt out on the holder, then check the sender: the health signal degrades, and a recent timestamp alone must not make that holder count as confirmed.
- [ ] `A` `I` **A restored backup really does carry the corpus.** Back up a device holding shares for someone, restore onto a second one, and confirm three things: the held shares, contacts and secret metadata are all there; the keys-lost screen appears, because the private keys are not; and after relinking, that holder can answer a retrieval again. The decision recorded in [docs/security.md](docs/security.md) rests on this and nothing else can test it. Worth running the device-to-device transfer separately from the cloud restore — they are different transports, and on iOS whether files survive a direct migration is undocumented.

### The relay under load and restart (not written up)

- [ ] `R` `A` `I` **A restart mid-flight loses nothing.** `conf/localhost.conf` is file-backed H2, so a deposit made before a relay restart must still be collectable after it. Kill the relay between deposit and pickup and confirm the share arrives.
- [ ] `R` `A` `I` **Two senders, one holder.** Bob holds shares from both Alice and Carol. Deleting all of Alice's from Bob's Held view must leave Carol's untouched, and Carol's sender view unaffected.

## Chores

- [ ] `phon` **phon cannot upload an image.** Its hexagon already enforces the 256 KiB cap and recognises PNG/JPEG, but the deposit form is still text-only, so phon can demonstrate a `text/plain` deposit and nothing else. Adding a file input means switching `createMySecret` from a URL-encoded `Form` to a multipart body, and dropping the `.trim` that assumes the secret is text. Worth having for teaching: phon cannot reconstruct, so sending an image to a real device is the only way to show the flow without two phones.
- [ ] `doc` arrows overlap in the C4 system-context and container diagrams in [docs/architecture.md](docs/architecture.md). Cosmetic, deliberately deferred. Mermaid's C4 renderer offers little layout control — `UpdateLayoutConfig` with `$c4ShapeInRow`/`$c4BoundaryInRow` is the usual lever, and converting a diagram to a styled `flowchart` gives full control at the cost of the C4 shape vocabulary.

## Open questions

Not work yet — decisions that have to be made before any work is worth planning.

- [ ] `R` **Analytics, logging and tracing — what is left after the rules.** The ground rules are written down now, in [docs/privacy.md](docs/privacy.md), which was the first move this item asked for. They settle the client half outright — no analytics, no crash report leaving a device on its own, no library that phones home, no push notifications — along with what a log line or a metric may contain, what the relay may compute from rows it already holds, and how the landing page may be counted. What is left is narrower and server-side: whether the relay gets aggregate metrics **at all** before it has users, and through what; whether an operational error log exists above `WARN` in production, where it is written and who may read it; and the rate-limiting mechanism, within the constraint that its counters stay in memory, expire with their window and are never written down. None of the three is urgent while nothing is deployed. The consequences that are decided but unbuilt — per-path counters on the landing page, and the privacy policy behind `/legalese/pp`, which nothing serves yet — are listed in that document rather than repeated here.

## Parked

Not rejected — waiting on something.

- [ ] `A` `I` **An encrypted on-device backup, recovered through a split passphrase.** Signal's shape: opt out of the platform's backup, and ship your own passphrase-encrypted export instead — the two halves are one decision, because opting out without a replacement loses the shares this device holds for other people. The passphrase is then the thing to split among contacts, which is Deposplit pointed at itself: the file can sit anywhere, and recovery bootstraps from the *k* contacts you can still find in person, who hand back the passphrase through the ordinary consent-gated flow — after which the file returns the contacts you had forgotten. An attacker would need *k* holders **and** the file, rather than the file alone. Two constraints keep this honest. **It can never restore the identity**: the private keys do not export, by design, so a restored device still mints fresh keys and still relinks. And **it buys nothing while the platform backup is on**, because the platform already carries the same corpus — see *Data at rest, and what a backup carries* in [docs/security.md](docs/security.md). So it waits on that default being turned off, which is not planned. Splitting the corpus itself is the wrong shape and was rejected: it would hand other people's shares to holders those people never chose, and collapse *k*-per-secret into *k*-for-everything.
- [ ] `A` `I` **Scheduled background refresh, so custody does not depend on app launches.** Both apps poll only while open — Android's home view model syncs from `init { load() }`, and neither platform schedules anything: no `WorkManager` job, no `BGTaskScheduler` task. Every signal the trust model runs on therefore rides on somebody opening the app, which collides with the freshness tuning in [docs/trust-model.md](docs/trust-model.md): emission every 3 days, stale warning at 2×, loss threshold at 3×. A holder who simply does not launch Deposplit for nine days drops out of `n_live` for every secret they hold, and their owner watches redundancy erode for a reason that has nothing to do with custody. The same gap leaves a pending retrieval unseen, because nothing tells a holder that somebody needs their share. **This, not push, is the answer to that gap:** push is out because the relay would have to hold a device token beside a public verify key — the objection is in *What the rules already settle* in [docs/privacy.md](docs/privacy.md) — whereas background refresh needs no token and no third party. It is not free either, though: a device that beacons on a schedule while nobody is using it tells the relay when that key's phone is awake, night after night, which foreground-only polling never does. Parked rather than planned because whether app-launch cadence is actually too slow is an empirical question with no deployment to answer it, and because the tuning is UI tuning that could move instead.
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
