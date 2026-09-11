# Interaction design

Every screen in Deposplit exists three times over — in `Android/`, in `iOS/`, and in the phony
phone under `deposplit.com/app/views/Phon/`. Nothing in the code says what a screen is *for*, so
each reimplementation has to rediscover it, and the three drift apart one small reasonable
decision at a time.

This page is that missing half. It records what each screen is for and which of its behaviours are
**decisions** rather than accidents — the ones that must survive a redesign. It is not a style
guide and says nothing about pixels, spacing or colour, which are each platform's own business.

For the flows these screens serve, see [testing.md](testing.md); for the states they are reading
from, [lifecycles.md](lifecycles.md); for why the domain behaves as it does,
[architecture.md](architecture.md) and [trust-model.md](trust-model.md).

## The rules that outrank any screen

Six rules decide most interaction questions before a screen is drawn. When a proposed design
collides with one of these, the rule wins.

**A control says why it cannot be used, rather than disappearing.** The screen keeps its shape as
state changes: a control that cannot work right now stays where it is, disabled, with the reason
next to it in words — *One more holder has to hand a piece back first*, *Every holder has been asked
already*. A control that simply vanishes leaves the reader hunting for something they remember
seeing and doubting their own memory, and it teaches nothing, where a disabled one that explains
itself teaches exactly what *k* means. Where a control genuinely has no place at all, something that
says why stands in for it: the deposit form on a phone with fewer than two contacts is replaced by a
sentence, rather than being offered and refused on submit. A field behind Premium is the same
principle once more — shown, locked, and labelled with what unlocks it. This is the control-level
half of *absence is never a signal*, three rules down.

**Consent is always a separate, explicit act.** A holder approves or denies; nothing is implied by
navigation, elapsed time, or the sender's wishes. On the owner's side the same rule appears as
separation: `reconstruct` is a pure read that tears nothing down, and destroying is its own
deliberate action. Wiring one into the other would make reading a secret mean disposing of it.

**Absence is never a signal.** A row vanishing from the relay means "collected, or never sent" —
never "done", never "lost". No screen may render a missing row as an outcome. This is why a holder
who stops holding a share leaves a *withdrawn* tombstone the owner can observe, rather than
disappearing quietly — and why the first rule above will not let a control go missing either.

**A claim about a person is never made on their behalf.** The verification level is the user's own
assertion about how well they know who they are talking to, so the app asks rather than infers.
Manual key entry therefore stops one level short of `VeryHigh`: typing keys out of a chat window is
not being in the same room, and no interface should let it look like it was.

**Local first, relay second.** Every list renders from local storage before any network call, and a
relay that is down degrades to a soft banner over real content — never a spinner, never a blank
screen, never a blocking error. The Requests tab is the sole exception, and legitimately so: it has
nothing local to fall back on, because a pending inbound request exists only on the relay.

**The relay is never a character in the copy.** The user's mental model is people and secrets — who holds a
piece, who is waiting on what. Nothing in the interface asks anyone to think about rows
or endpoints. The empty state is
- *No secrets split & shared yet*,
- *No shares to keep safe yet*,
- *No pending requests*, and
- *No contacts yet*.

## The shell

| | Android | iOS | phon |
|---|---|---|---|
| First screen | `sign_in` | `SignInView` | `GET /` |
| Keys lost | `keys_lost` | `KeysLostView` | — |
| Tabs | Split & shared · Keeping safe · Requests | same | same |
| One secret | `secret_detail/{secretId}` | `SecretDetailView` | `GET /secrets/:secretId` |
| One holder | `share_detail/{shareId}` | `ShareDetailView` | `GET /shares/:shareId` |
| Always reachable | My QR code · New secret · Contacts · Settings | same | My QR code · Contacts · Settings |

Registration asks for one thing, a pseudonym, and never for an account. That is the first and
loudest claim the product makes about itself, so it stays a single field.

The **keys-lost** screen exists because a restored device can carry every record and none of the
keys. It says four things in that order: what happened, that nothing *else* was lost, what to do,
and that until it is done this person is unreachable. phon cannot reach the state — it keeps keys
and records in the same files, so the two cannot come apart — and so has no such screen.

## Split & shared

One card per secret, never per share. The card names the secret, when it was split, how many people
hold a piece, and its health; tapping it opens that secret's own screen. A secret split among four
people is one thing the user owns, not four rows to reconcile.

**The card summarises and does nothing else.** Every action belongs to the screen behind it. A list
that unfolds actions in place turns ten secrets into ten places where buttons appear and disappear,
which is the first rule's failure case repeated once per row.

**Health is a graduated alarm about redundancy**, computed from *n_live* — holders with recent
proof of custody — against the threshold *k*:

| Health | Condition | What the badge says |
|---|---|---|
| Healthy | `n_live > k + 1` | *(no badge; silence is the healthy state)* |
| Caution | `n_live == k + 1` | Margin of one — re-split soon |
| Critical | `n_live == k` | Reconstruct + re-split now |
| Lost | `n_live < k` | Unrecoverable |
| Destroying | the secret is being torn down | Destroying |

Each badge names the action, not just the condition. "Margin of one" is a fact; "re-split soon" is
what the user can do about it, and the second half is the reason for showing the first.

**Per-holder freshness is three buckets and one early nudge.** *Confirmed* means proof of custody
within the loss threshold. *Unmonitored by choice* means the holder sent a signed opt-out — a
standing advisory, never an alarm, because a holder declining to beacon is a decision rather than a
failure. *Silent — possible loss* means expected proof has not arrived; the holder drops out of
*n_live*, reversibly, the moment anything is heard again. Above those sits *Getting stale*, shown
for a holder still comfortably confirmed, so that the first the owner hears of a problem is not the
alarm itself.

### One secret: the secret screen

The holders, each opening that holder's own screen; the three actions that operate on the whole
secret; the reconstructed content when there is some; then **Repair** (offered only at Caution or
Critical), **Destroy**, and **Force Forget** for a destroying secret whose holders will never all
answer. **Destroy** is the only action here that reaches other people's phones and the only one that
can never be undone, and its confirmation says both rather than softening either: every holder is
asked to destroy their piece, and once they all have, nothing can put this secret back together.
Until they have, it stays in the list as *Destroying*, which is what Force Forget is for.

**A state badge and a status line must not say the same thing.** The health badge already reads
*Destroying*, so the line beside Force Forget carries what the badge cannot: how many holders have
still to answer — *Waiting for 2 holders to destroy their piece*. That is precisely the judgement
Force Forget asks for, since waiting on one dark phone and waiting on everybody are different
situations, and the count is free: each holder's `ShareMetadata` row is dropped as their removal is
approved, so the number of holders left *is* the number outstanding.

**The word is deliberate, and *Delete* is deliberately not it.** Delete is what this product calls
its local, narrow, survivable actions — removing a contact, dropping a share this device holds for
someone else. Destroying a secret is the opposite on every axis, so it may not borrow the milder
word, and the mildness of **Force Forget** beside it is equally deliberate: that one is local-only
teardown, and the shares stay where they are.

**Three actions, always all three, never a different three.** They are not phases of one task —
retrieving and clearing are legitimately available at the same time — so they are three controls
whose enabled state changes, never one control whose label changes. A button whose position can be
learned is worth more than one whose meaning has to be re-read.

| | Enabled when | What the disabled state says |
|---|---|---|
| **Retrieve shares** | the secret is active, and some holder has no pending or approved retrieval | *Every holder has been asked already* · *This secret is being destroyed* |
| **Reconstruct** | approved retrievals ≥ *k* | *n more holders have to hand a piece back first* — or which of the three biometric reasons applies |
| **Clear collected copies** | at least one holder has handed a piece back | *Nothing has been handed back yet* |

**Retrieve shares stays enabled once *k* copies are in.** Stopping at the threshold would forfeit the
surplus, and the surplus is what the integrity cross-check is made of: asking the stragglers is how
*reconstructed without an integrity margin* becomes *confirmed by every collected share*. It goes
quiet only when nobody is left to ask, which is exactly what the service underneath does — it skips
a holder with a live request and asks everyone else.

**Reconstruction is gated twice**: by the threshold, and by biometrics. Where biometrics are
unavailable the screen names which of the three reasons applies — no enrolment, no sensor,
temporarily unavailable — in place of the button.

The result carries an honest advisory about its own integrity: reconstructed from exactly *k*
shares with no cross-check possible; reconstructed with every collected share agreeing; or
reconstructed after excluding inconsistent shares, naming the contacts they came from. Only the
middle case may sound confident.

Reconstructed content forks three ways — text shown as text, an image shown as an image, anything
else shown as a size and an offer to export. Nothing is transcoded to make it displayable, because
a secret is bytes and has to come back as the bytes that went in.

**Reconstructing is reading, not finishing — and clearing is how it finishes.** `reconstruct` is a
pure read: the copies collected from holders outlive it, each still carrying its ciphertext on the
relay. **Clear collected copies** hands them back, along with any ask still waiting for an answer,
so that afterwards there is no retrieval flow for the secret at all. It is not teardown: the holders
keep their pieces, the secret stays split among the same people, and asking again is a button press.
Clearing is also what makes a second round possible, since an approved request counts as live and
would otherwise silence every future ask.

**The confirmation states a consequence, never an accusation.** Nothing records that a secret has
been read — reconstructing leaves no trace by design — so all the screen knows is whether the secret
is in front of the reader right now. With it on screen, the confirmation says the holders keep their
pieces and copies can be asked for again; without it, that the secret has not been shown here and
clearing means asking the holders again first. Neither claims anything about what the reader
remembers.

The reminder to clear up afterwards is a standing line beside the button, not a dialog. The moment
the secret is finally on screen is the worst possible moment to cover it with something that asks
for nothing.

### One holder: the share detail

Recipient, deposit date, then the two request kinds — Retrieval and Removal — each showing its
current state or *No request*, with the button to open one. Nothing else: reconstructing belongs to
the secret as a whole, so it lives on the secret's screen, which is also the screen this one was
reached from.

## Keeping safe

The shares this device holds for other people, sortable by date, label or sender. A holder may
delete a share unilaterally, with no request and no approval, because the whole product rests on
custody being voluntary. When several shares come from one sender, the confirmation also offers to
delete all of them at once.

What is stored here is the **plaintext** share, decrypted at pickup — see
[security.md](security.md) for why that is what lets recovery survive the owner losing their keys.

## Requests

Only inbound, and only pending: the things other people are waiting on this user to answer. Each
carries the sender, the kind, and Approve and Deny given equal visual weight, because a denial is a
legitimate answer rather than a failure path.

Two things sit above the list, because they are about identity rather than about a request:

- **Key changed *n* days ago — verify fresh before approving**, on retrieval requests only. A
  retrieval hands over a share sealed to the requester's current key, so a recent key change is
  exactly when to look twice. Deposits and removals carry no such warning, because neither releases
  anything.
- **Possible impersonation attempt**, when a contact's key has changed and their previous key is
  flagged compromised. It is never auto-resolved and offers only *Dismiss*: reconnecting means
  verifying the person fresh and relinking from Contacts, which is a deliberate act elsewhere, not
  a button here. **Dismissing is permanent, and the card says so.** The relay's copy of the notice
  was deleted when the conflict was captured, so what the card holds is the only record there is,
  and dismissing deletes it. What is lost is the history rather than the protection — the contact
  still cannot be relinked without verifying them again, and a second attempt raises a fresh
  conflict — but a one-way door may not be offered as though it were a closing X. The German is
  *Verwerfen*, which is the only one of the three words the platforms had reached for that means
  throwing something away rather than hiding or ignoring it.

## Contacts

Each contact shows its verification level and, when a nickname is set, the pseudonym beneath it —
but only then, so a second line always means "this is what they call themselves" rather than
repeating the first. Per contact: rename, relink, mark key compromised, pause or resume custody
heartbeats, delete.

**Those actions are on the row, never behind a long-press.** An action reachable only by a gesture
the screen does not advertise is, to the person looking at it, an action that does not exist — and
a contact that cannot visibly be edited reads as one that cannot be edited at all. Deleting is
confirmed on all three platforms, because a contact is never re-added: adding the same person again
mints a fresh `contactId` and orphans every share anchored to the old one.

**Adding a contact has two ways in, and one of them is weaker on purpose.** Scanning a QR code in
person — or, on phon, pasting the payload that stands in for a scan — earns `VeryHigh`. Typing the
pseudonym and the two keys by hand offers Very Low, Low and High and never Very High. All three
platforms enforce this identically and show the same guidance for what each level asserts.

**A relay override on a contact is free; this device's own default relay is Premium.** Naming
someone else's relay says where *their* mailbox is and makes the person entering it reachable
nowhere new, so gating it would protect nothing. Naming your own is the self-hosting feature.

**Relinking always re-chooses the verification level.** It can never inherit the old one, because
the old one was a claim about a key that no longer exists.

The **awaiting relink** nudge on the home screen counts contacts still holding this device's old
key and says what to do: meet them and let them re-scan.

## New secret

Label, then the secret, then who holds it, then how many are needed.

The secret is either text or a picture, and the input says so: a text field labelled *Enter secret
text or …*, then a label — *… choose secret photo* — followed by two links naming the two sources,
the photo library and the files a gallery cannot see. The label is not clickable; what is clickable
is the source. The three lines read as one sentence.

A picked image is split exactly as it stands. It is never re-encoded to make it easier to handle,
which is why an unsupported type is refused by name instead of converted, and why an oversized one
is refused with its real size before it is read into memory.

**Before splitting, the form argues with the user when it should.** Three families of warning, each
stating a consequence rather than a rule: more holders than are comfortable to manage; a threshold
low enough that a minority could reconstruct behind the owner's back; and a threshold high enough
that losing one holder loses the secret. Each is a confirmation, never a refusal — *Deposit Anyway*
is always there.

At the free-tier limit the form says how many of how many slots are in use, and that destroying one
frees a slot immediately.

## Settings

Default relay (Premium, with the free per-contact override explained beside it), catalog backup, an
honest paragraph on what the platform's own device backup does and does not carry, notifications,
and identity regeneration with its consequences stated before the confirmation rather than after it.

**Notifications get a paragraph, not a switch** — the same shape as the backup entry above, and for
the same reason: the platform owns the switch. What the screen owes is what the notice is for, that
it names nobody, whether it is currently on, and a way back to the system page for somebody who
dismissed the prompt and has no other route to it. A control that cannot work says so; it does not
disappear, and it does not duplicate a switch that lives elsewhere.

## What a notification may say

One interruption exists: a contact is waiting on a share this phone keeps, and cannot go further
until it is answered. The notice says exactly that, in one sentence, and **names nobody, names no
secret, and does not count them** — a locked screen must give away nothing beyond the app being
installed, which the launcher already did. It is produced on the device from rows it already
fetched; [privacy.md](privacy.md) says why that is not push and why push is out.

**A pending removal says nothing at all.** The sender flipped her secret to `DESTROYING` the moment
she asked and is not waiting on the answer to carry on, so learning of it at the next launch costs
nobody anything — and a lock screen that speaks twice as often for one real interruption is worse
at the job. This is *absence is never a signal* meeting its own limit: silence here is a choice
about attention, not about state, and the removal request is still sitting in the Requests tab
saying so.

The permission is asked the first time the device is actually keeping something for somebody.
Before that, there is nothing a notice could ever say.

phon adds a **Danger zone** that resets a phone to a clean slate. It has no mobile counterpart and
needs none: resetting a test phone is the entire point of having one.

## Premium

One purchase, once. Two benefits — as many secrets as you like, and pointing this device at a relay
you run yourself — and one line that earns its place on the screen: whether Premium is unlocked is
decided on this device, and the relay never learns anything about it.

## What phon leaves out

phon is a teaching and manual-testing surface, not a product surface, so it drops UI freely and
domain logic never. What it skips, it skips for a reason it can state:

| Missing | Because |
|---|---|
| Camera | A browser has none; pasting the payload stands in for a scan, and manual key entry is the other way in |
| Export | A reconstructed secret is shown on screen and cannot be saved to a file |
| Paywall, free-tier cap, Premium gates | No purchases port at all, so the relay fields are simply editable |
| Keys-lost screen | `IdentityIntegrity` cannot reach that state here |
| *(added, not missing)* Danger zone | Resetting a test phone to a clean slate is the point of having one |

## Where the display model lives

`FreshnessBucket` and `SecretHealth` are **UI-layer types on each platform**, not hexagon value
objects, and that is deliberate: they serve display. `ShareService` recomputes its own freshness
check for retrieval targeting rather than sharing them — a small duplication kept on purpose, so
that tuning what the user *sees* never silently changes what the app *does*.

The consequence for this document: the health and freshness rules above are implemented three times
over and are exactly the sort of thing that drifts. Changing one means changing all three.

## Copy

German addresses the reader formally, with *Sie*, everywhere it addresses them at all — and wording
that avoids addressing the reader is better still. `CLAUDE.md` carries the rule and the trap that
comes with it. German dates are `dd.MM.yyyy`.

Empty states say what has not happened yet, in the user's terms — *You have not split anything
yet*, *Nobody is waiting on you* — never *no rows*.
