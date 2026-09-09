# Interaction design

Every screen in Deposplit exists three times over — in `Android/`, in `iOS/`, and in the phony
phone under `deposplit.com/app/views/Phon/`. Nothing in the code says what a screen is *for*, so
each reimplementation has to rediscover it, and the three drift apart one small reasonable
decision at a time.

This page is that missing half. It records what each screen is for and which of its behaviours are
**decisions** rather than accidents — the ones that must survive a redesign. It is not a style
guide and says nothing about pixels, spacing or colour, which are each platform's own business.

For the flows these screens serve, see [testing.md](testing.md); for why the domain behaves as it
does, [architecture.md](architecture.md) and [trust-model.md](trust-model.md).

## The rules that outrank any screen

Six rules decide most interaction questions before a screen is drawn. When a proposed design
collides with one of these, the rule wins.

**An affordance that cannot work is never shown.** Reconstruct stays hidden until *k* approved
retrievals exist, because a button that explains itself only after being pressed teaches the wrong
thing about what *k* means. The deposit form is replaced by a sentence when fewer than two contacts
exist, rather than being offered and refused on submit. A field behind Premium is a different case
and is shown as locked: that is a capability the user does not have *yet*, not one that cannot
exist.

**Consent is always a separate, explicit act.** A holder approves or denies; nothing is implied by
navigation, elapsed time, or the sender's wishes. On the owner's side the same rule appears as
separation: `reconstruct` is a pure read that tears nothing down, and discarding is its own
deliberate action. Wiring one into the other would make reading a secret mean disposing of it.

**Absence is never a signal.** A row vanishing from the relay means "collected, or never sent" —
never "done", never "lost". No screen may render a missing row as an outcome. This is why a holder
who stops holding a share leaves a *withdrawn* tombstone the owner can observe, rather than
disappearing quietly.

**A claim about a person is never made on their behalf.** The verification level is the user's own
assertion about how well they know who they are talking to, so the app asks rather than infers.
Manual key entry therefore stops one level short of `VeryHigh`: typing keys out of a chat window is
not being in the same room, and no interface should let it look like it was.

**Local first, relay second.** Every list renders from local storage before any network call, and a
relay that is down degrades to a soft banner over real content — never a spinner, never a blank
screen, never a blocking error. The Requests tab is the sole exception, and legitimately so: it has
nothing local to fall back on, because a pending inbound request exists only on the relay.

**The relay is never a character in the copy.** The user's mental model is people — who holds a
piece, who is waiting on whom. Nothing in the interface asks anyone to think about rows, requests
or endpoints. The empty state is *Nobody is waiting on you*, not *no pending requests*.

## The shell

| | Android | iOS | phon |
|---|---|---|---|
| First screen | `sign_in` | `SignInView` | `GET /` |
| Keys lost | `keys_lost` | `KeysLostView` | — |
| Tabs | Split & shared · Keeping safe · Requests | same | same |
| Always reachable | My QR code · New secret · Contacts · Settings | same | My QR code · Contacts · Settings |

Registration asks for one thing, a pseudonym, and never for an account. That is the first and
loudest claim the product makes about itself, so it stays a single field.

The **keys-lost** screen exists because a restored device can carry every record and none of the
keys. It says four things in that order: what happened, that nothing *else* was lost, what to do,
and that until it is done this person is unreachable. phon cannot reach the state — it keeps keys
and records in the same files, so the two cannot come apart — and so has no such screen.

## Split & shared

One card per secret, never per share. The card names the secret, when it was split, and its health;
expanding it lists the holders. A secret split among four people is one thing the user owns, not
four rows to reconcile.

**Health is a graduated alarm about redundancy**, computed from *n_live* — holders with recent
proof of custody — against the threshold *k*:

| Health | Condition | What the badge says |
|---|---|---|
| Healthy | `n_live > k + 1` | *(no badge; silence is the healthy state)* |
| Caution | `n_live == k + 1` | Margin of one — re-split soon |
| Critical | `n_live == k` | Reconstruct + re-split now |
| Lost | `n_live < k` | Unrecoverable |
| Discarding | the secret is being torn down | Discarding |

Each badge names the action, not just the condition. "Margin of one" is a fact; "re-split soon" is
what the user can do about it, and the second half is the reason for showing the first.

**Per-holder freshness is three buckets and one early nudge.** *Confirmed* means proof of custody
within the loss threshold. *Unmonitored by choice* means the holder sent a signed opt-out — a
standing advisory, never an alarm, because a holder declining to beacon is a decision rather than a
failure. *Silent — possible loss* means expected proof has not arrived; the holder drops out of
*n_live*, reversibly, the moment anything is heard again. Above those sits *Getting stale*, shown
for a holder still comfortably confirmed, so that the first the owner hears of a problem is not the
alarm itself.

The card's actions are **Request Retrieval** (one press, fanning out to every holder without a live
request), **Repair** (offered only at Caution or Critical), **Discard**, and **Force Forget** for a
discarding secret whose holders will never all answer. Discard states its own cost in the
confirmation: every holder must approve before the secret leaves the list.

### One holder: the share detail

Recipient, deposit date, then the two request kinds — Retrieval and Removal — each showing its
current state or *No request*, with the button to open one. Below them sits the reconstruct
section, which belongs to the whole secret rather than to this holder, and says how many approved
shares exist against how many are needed.

**Reconstruction is gated twice**: by the threshold, which hides it, and by biometrics, which guard
it. Where biometrics are unavailable the screen explains which of the three reasons applies — no
enrolment, no sensor, temporarily unavailable — rather than offering a button that cannot work.

The result carries an honest advisory about its own integrity: reconstructed from exactly *k*
shares with no cross-check possible; reconstructed with every collected share agreeing; or
reconstructed after excluding inconsistent shares, naming the contacts they came from. Only the
middle case may sound confident.

Reconstructed content forks three ways — text shown as text, an image shown as an image, anything
else shown as a size and an offer to export. Nothing is transcoded to make it displayable, because
a secret is bytes and has to come back as the bytes that went in.

**Reconstructing is reading, not finishing.** The collected shares stay where they are, and a
sender who has read the secret currently has no way to clear them — the open chore in
[TODO.md](../TODO.md).

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
  a button here.

## Contacts

Each contact shows its verification level and, when a nickname is set, the pseudonym beneath it —
but only then, so a second line always means "this is what they call themselves" rather than
repeating the first. Per contact: rename, relink, mark key compromised, pause or resume custody
heartbeats, delete.

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

At the free-tier limit the form says how many of how many slots are in use, and that discarding one
frees a slot immediately.

## Settings

Default relay (Premium, with the free per-contact override explained beside it), catalog backup, an
honest paragraph on what the platform's own device backup does and does not carry, and identity
regeneration with its consequences stated before the confirmation rather than after it.

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
