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

- [ ] `A` `I` **Flow 7 — BYOR.** Two relays, one contact overridden and one not; killing one degrades only that contact. Needs the Premium unlock first.

### The documented edge cases

- [ ] `A` `I` Fresh keypairs after reinstall — existing contacts can no longer decrypt shares sent to the old ones.
- [ ] `A` `I` Reconstruct stays disabled until *k* approved retrievals exist for the same `secretId`, and the line beneath says how far short it is. The button itself never leaves the screen.
- [ ] `A` `I` Request Retrieval stays enabled at *k*: with three holders and 2-of-3, two approvals must not silence the third ask. It goes quiet only once every holder has a pending or approved retrieval.
- [ ] `A` `I` 2-of-3 with only two approvals still reconstructs.
- [ ] `A` `I` Integrity margin with a surplus holder: the advisory reports the margin honestly rather than claiming more confidence than it has.
- [ ] `A` `I` Verification levels: manual entry never offers `VERY_HIGH`; a QR scan defaults to it.
- [ ] `A` `I` A phone with neither a biometric nor a passcode set up explains itself instead of offering a button that cannot work.
- [ ] `A` `I` The passcode fallback reaches the secret: turn the face off, or fail it three times, and reconstruct still completes.
- [ ] `A` `I` The key-change indicator appears on retrieval requests, and only on those.

### Background refresh

Written up in [docs/testing.md](docs/testing.md), which carries the `adb` and `lldb` incantations for firing a pass by hand. None of this is reachable by a unit test.

- [ ] `A` `I` A pass emits without a launch: force-stop the holder, fire a pass, and watch `lastConfirmedAt` move on the sender.
- [ ] `A` `I` Silence past the nine-day threshold no longer costs anything, with the holder's app never opened.
- [ ] `A` `I` A waiting retrieval notifies exactly once, names nobody, and is not truncated on the lock screen.
- [ ] `A` `I` Answering clears it — nothing is re-announced on the next pass.
- [ ] `A` `I` A removal is silent, and is still sitting in the Requests tab.
- [ ] `A` The permission is asked on the launch where the first share lands, never before, and never twice; denying it leaves a Settings line that says so.
- [ ] `I` The explanation comes first, and *Not now* costs nothing: Settings then says it is not turned on yet and offers the prompt again. Denying that turns the line and the button into the route to the system page, and turning it on there is reflected on return.
- [ ] `I` Both background keys reached the **built** app's `Info.plist`. Silent when wrong, and the only check that catches it.
- [ ] `I` A pass on a locked phone emits nothing and writes nothing. Needs a real device — the Simulator's Keychain does not behave like a locked phone's.

### Pictures (not written up)

- [ ] `A` `I` `phon` **A JPEG survives the round trip byte for byte.** Deposit a JPEG on one platform, reconstruct on the other, export it, and `cmp` against the source file. JPEG rather than PNG on purpose: export used to re-encode to PNG while naming the file from the declared type, so a re-encode would hand back PNG bytes under a `.jpg` name and look right in a viewer. phon can be the depositing end now that it uploads images, and it shows a reconstructed one on screen - but it has no export, so the `cmp` still needs a real device receiving.
- [ ] `A` `I` **The size cap holds at both ends.** A secret of exactly 256 KiB deposits and reconstructs — that is ~262 KB of ciphertext and ~341 KiB of base64 body, so it also proves the relay's 512 KiB and 1 MiB bounds have real headroom. One byte over is refused at pick time, naming the file's actual size, without the file being read into memory first.
- [ ] `I` **A straight-from-camera photo is refused by type.** An unmodified iPhone photo is HEIC. It must be turned away as an unsupported type, never silently transcoded to JPEG behind the user's back — verbatim or refused is the whole rule.
- [ ] `A` `I` `phon` **A repair carries an image through untouched.** Reconstruct an image secret, re-split it through the repair flow, reconstruct again, and `cmp` against the original. This is the path where bytes used to round-trip through a `String` and come back corrupted.
- [ ] `A` `I` **EXIF rides along, including GPS.** Deposit a photo carrying location EXIF and confirm it is still there after reconstruction on the other platform. [docs/security.md](docs/security.md) says this is deliberate; confirm the document and the code still agree.

### Freemium (not written up)

- [ ] `A` `I` **The cap counts active secrets, not lifetime deposits.** Three active secrets; the fourth is refused. Destroy one and the fourth succeeds immediately — before any holder has confirmed the removal, since a destroying secret gives its slot up at once.
- [ ] `A` `I` **A repair at the cap is not blocked.** With three active secrets, run a repair through to the re-deposit. It must go through: the replacement supersedes an active secret rather than adding a fourth.

### Identity and trust (not written up)

- [ ] `A` `I` **A share sealed before a rotation still opens after it.** Alice deposits for Bob, then regenerates her identity before Bob picks the share up. Bob's pickup must succeed from the retained previous key, and his later retrieval must come back sealed to Alice's *new* key. Run it with Alice and Bob on different platforms.
- [ ] `A` `I` **A rotation from a key flagged compromised is refused auto-accept.** Mark a contact's key compromised on one platform, then push a rotation from that contact on the other. It must land as a `KeyConflict` for manual resolution, never as a silent relink.
- [~] `A` **Identity recovery rebuilds the sender's view.** Alice reinstalls with fresh keys, relinks with Bob, and Bob pushes recovery metadata. Alice's Distributed view must come back — metadata only, with no share bytes crossing the wire. Worth running with a phony phone as Bob: its relink pushed nothing at all until the call was added, and only this check would have caught it.
- [ ] `A` `I` **A holder who opts out of heartbeating stops counting as confirmed.** Opt out on the holder, then check the sender: the health signal degrades, and a recent timestamp alone must not make that holder count as confirmed.
- [ ] `A` `I` **A restored backup really does carry the corpus.** Back up a device holding shares for someone, restore onto a second one, and confirm three things: the held shares, contacts and secret metadata are all there; the keys-lost screen appears, because the private keys are not; and after relinking, that holder can answer a retrieval again. The decision recorded in [docs/security.md](docs/security.md) rests on this and nothing else can test it. Worth running the device-to-device transfer separately from the cloud restore — they are different transports, and on iOS whether files survive a direct migration is undocumented.

## Chores

- [ ] `doc` arrows overlap in the C4 system-context and container diagrams in [docs/architecture.md](docs/architecture.md). Cosmetic, deliberately deferred. Mermaid's C4 renderer offers little layout control — `UpdateLayoutConfig` with `$c4ShapeInRow`/`$c4BoundaryInRow` is the usual lever, and converting a diagram to a styled `flowchart` gives full control at the cost of the C4 shape vocabulary.
- [ ] `A` `I` a notification tap opens the app rather than the **Requests** tab. Deliberately deferred, and deliberately deferred on *both* platforms at once: iOS needs a `UNUserNotificationCenterDelegate`, which means an `@UIApplicationDelegateAdaptor` and therefore the UIKit `iOS/CLAUDE.md` keeps out, so doing it on Android alone would split the two in the one place they should match. Requests is a top-level tab, so the cost is one tap.

## Open questions

Not work yet — decisions that have to be made before any work is worth planning.

- [ ] `R` **Analytics, logging and tracing — what is left after the rules.** The ground rules are written down now, in [docs/privacy.md](docs/privacy.md), which was the first move this item asked for. They settle the client half outright — no analytics, no crash report leaving a device on its own, no library that phones home, no push notifications — along with what a log line or a metric may contain, what the relay may compute from rows it already holds, and how the landing page may be counted. What is left is narrower and server-side: whether the relay gets aggregate metrics **at all** before it has users, and through what; whether an operational error log exists above `WARN` in production, where it is written and who may read it; and the rate-limiting mechanism, within the constraint that its counters stay in memory, expire with their window and are never written down. None of the three is urgent while nothing is deployed. The consequence that is decided but unbuilt — per-path counters on the landing page — is described in that document rather than repeated here.
- [ ] `phon` `A` `I` **A second rotation from an old key is never raised as a conflict.** A contact is known by one verify key at a time, so once a rotation from K_old has been applied, any further rotation from K_old comes from a key no contact is known by, and is left on the relay until it expires. Two things send one. **A crash in `regenerateIdentity`** between the rotation pushes and the activation, followed by a retry: a contact who read the orphaned first attempt before the retry's moves to a key whose private half was never kept, and the retry no longer matches. The comment there, claiming such a contact "auto-corrects on the next successful push", is only true when the retry is read first — though the rotating device does see that contact on its *awaiting relink* list, so it is not silent there. **A stolen K_old**: the thief's rotation and the owner's are two claims to one identity, which [docs/trust-model.md](docs/trust-model.md) says produce a `KeyConflict`. Today whichever is read first wins, silently. The likely fix is to remember a contact's previous verify keys and treat a rotation from one of them as a `KeyConflict` — a new `Contact` field on all four platforms, which means Android's hand-written `CatalogCodec` and every memberwise `Contact(...)` on iOS. Part of the question is whether the crash-retry case should raise one too. The contact is stuck on a key nobody holds either way, so a conflict that sends the user to relink in person may be the right answer for both.

## Parked

Not rejected — waiting on something.

- [ ] `A` `I` **An encrypted on-device backup, recovered through a split passphrase.** Signal's shape: opt out of the platform's backup, and ship your own passphrase-encrypted export instead — the two halves are one decision, because opting out without a replacement loses the shares this device holds for other people. The passphrase is then the thing to split among contacts, which is Deposplit pointed at itself: the file can sit anywhere, and recovery bootstraps from the *k* contacts you can still find in person, who hand back the passphrase through the ordinary consent-gated flow — after which the file returns the contacts you had forgotten. An attacker would need *k* holders **and** the file, rather than the file alone. Two constraints keep this honest. **It can never restore the identity**: the private keys do not export, by design, so a restored device still mints fresh keys and still relinks. And **it buys nothing while the platform backup is on**, because the platform already carries the same corpus — see *Data at rest, and what a backup carries* in [docs/security.md](docs/security.md). So it waits on that default being turned off, which is not planned. Splitting the corpus itself is the wrong shape and was rejected: it would hand other people's shares to holders those people never chose, and collapse *k*-per-secret into *k*-for-everything.
- [ ] `A` `I` **In-app camera capture as a secret input.** Today an image must already exist in the photo library or in Files, which means a photo of a seed phrase has been sitting in the camera roll — and syncing to iCloud or Google Photos — before it is ever split. A capture kept in memory and split immediately never touches either. Both platforms already declare a camera permission for QR scanning, and both already have a camera surface to copy (`DataScannerViewController`, CameraX).
- [ ] `A` `I` **Widen the accepted image formats past PNG and JPEG.** HEIC is the obvious first candidate: an unmodified iPhone photo is HEIC, so today it is refused on type as well as size. Each addition is more decoder surface reached by attacker-chosen bytes, so this is a deliberate trade rather than an oversight. SVG stays out — it is scriptable.
- [ ] `A` **Google Play Billing adapter.** The entitlement port, both gates and the paywall ship; on Android there is simply nothing behind the purchase button, and the screen says so. Blocked on Play infrastructure rather than on code: Play Billing talks to the Play Store app and has no offline mode, so one test purchase needs a Play Console entry for `com.deposplit`, the `com.deposplit.premium` product active, a build on at least the internal test track, and a registered license tester — and Google scopes testing to hardware devices, with the emulator's purchase flow unreliable even on a Play system image. When it lands it only has to write the preference `SharedPreferencesPurchaseRepository` already reads; nothing else moves. What it writes must reflect the Google account, though, not just a purchase made on this device. Like `StoreKitPurchaseStore` on iOS, it should re-read the account's purchases with `queryPurchasesAsync` on every launch and write the answer through. Then a new phone signed in to the same account gets Premium back, which the Terms of Service promise, and a refunded purchase loses it. iOS needed none of this setup, because a StoreKit configuration file buys the unlock in the Simulator with no App Store Connect record at all.
- [ ] `I` **Rehearse the iOS purchase against the App Store Sandbox.** StoreKit Testing in Xcode proves the code path but not the App Store Connect record: the product has to exist there with a real price, and be bought once by a sandbox Apple ID on a physical device before release. No code change expected — the local `Deposplit.storekit` becomes redundant for that run rather than wrong.
- [ ] `R` `A` `I` **Airtable / Google Sheets relay kinds.** Wanted, but gated on the default relay demonstrating real adoption. Each non-REST kind needs its own adapter and wire shape, plus a `relayKind` discriminator on `Contact`; that is speculative surface area until people are actually using Deposplit. No design work before then.
- [ ] `R` `phon` `A` `I` **Relay row TTL.** One retention class, now that every row is a message its addressee deletes once read: how long to wait for an addressee who never comes — generous, so an offline counterparty can still act. The mechanism exists: `jobs.RetentionSweep` runs inside the relay, so a self-hosted relay gets it too, with one period per table under `relay.retention` in `application.conf`. **`custody_heartbeats` and `key_rotations` are done** (28 and 180 days, see docs/protocol.md). **`share_requests` remains.** No correctness property depends on *having* a period — though one depends on how it is introduced, below. See "absence is never a signal" in [docs/protocol.md](docs/protocol.md). **Collection cannot land alone for `share_requests`.** A `deposit` row carries the only copy of a share in transit, and the sender's retained blob is the prerequisite for surviving its collection — but nothing re-deposits from that blob today, so extending the sweep to deposits without the client half would introduce exactly the loss the retention exists to prevent. The client half is bigger than it looks: a re-deposit mints a fresh request id relay-side, while the blob's `id` *is* the original request id — the key `ShareMetadata`, `syncDistributed` and `isRetentionStillPending` all join on — so both local records need re-keying. It need not detect collection, though. Attempt the re-deposit unconditionally and let `hasActiveDeposit` answer `Conflict` while the row is still there, which keeps "absence is never a signal" intact.
- [ ] `phon` `A` `I` **Reconsider *holder* for *keeper* in English.** Decided against for now, and parked rather than dropped because the reasoning is worth having to hand. The charge against *holder* is that the thing it holds is a **share**, so *share holder* can be read as shareholder — a live misreading in a product whose whole subject is splitting something up. Unlike the German this was weighed beside, there is no correctness argument to force it: *holder* implies no ownership in English, so this is clarity rather than a wrong word, while the price is the entire English surface — several dozen strings across the three platforms, plus `HolderStatus`, `holders`, `holderKey`, the `*_holder*` resource names, and every mention in [docs/interaction.md](docs/interaction.md). **Keeper** was the candidate, because the tab is already *Keeping safe* and *who keeps a piece* reads naturally. **Custodian** was the other: tied to the `custody` vocabulary already in the code — `CustodyHeartbeatTuning`, custody heartbeats, proof of custody — and the exact English counterpart of the German *Verwahrer*. Two things would reopen it: a real reader stumbling on the shareholder reading, or the English surface being rewritten for some other reason, at which point folding this in costs almost nothing. One consequence to know about in the meantime — German now names the role by keeping and English by holding, which is harmless but reads like an oversight to whoever meets it next.
