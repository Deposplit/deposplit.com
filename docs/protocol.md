# Protocol

How the apps talk to a relay. The relay stores and forwards opaque bytes between public
keys; it never decrypts anything and never learns who anybody is. The formal specification
is [`../conf/openapi.yaml`](../conf/openapi.yaml) — this document explains the shape and
the reasoning behind it.

## Addressing: keys, not accounts

There is no registration. A caller *is* their public verify key, and every row the relay
stores embeds both `sender_key` and `recipient_key`. Rows are therefore **self-describing**:
authorising a request never requires looking up another row, a session, or a user record.
The relay's entire authorisation rule is *"does the key that signed this request match a
key embedded in the row being touched?"*

A direct consequence, worth knowing before you change anything: **when a user rotates their
identity, any row still addressed to or from the old key becomes permanently unreachable.**
Not deleted — invisible. Rotation flows therefore drain outstanding relay state under the
old identity first, and a mid-flight crash is recovered by retrying, since the old identity
stays live until the new one is activated.

## Two independent layers of signature

These are frequently confused, so: they protect different things and neither replaces the
other.

**1. Transport authentication** — proves *this HTTP request* is fresh and from the holder
of a given key. Three headers carry it:

| Header | Contents |
|---|---|
| `X-Deposplit-Verify-Key` | The caller's public verify key, base64url |
| `X-Deposplit-Nonce` | `<unix-ms>.<random>`, unique per request |
| `X-Deposplit-Signature` | Digital signature over the canonical string, base64url |

The canonical string is:

```
nonce || "\n" || UPPERCASE(method) || "\n" || path_with_query || "\n" || hex(SHA-256(body))
```

with the empty string as `body` for requests that have none. The relay rejects a nonce
whose embedded timestamp is more than five minutes old *or* dated in the future — there is
no forward skew allowance.

The header is named *verify* key deliberately: it carries only the public key needed to
verify the accompanying signature, never a signing key.

**2. Payload signatures** — prove *the content of a row* was authored by the party it
claims, independently of whatever transport carried it. Every `share_requests` row carries
a `sender_signature` set when opened and a `recipient_signature` set when answered.
Recipients re-verify these against the counterparty's key from their own local contact
record before acting; the relay also verifies them server-side as defence in depth, even
though it cannot read what they protect.

This second layer is what makes a compromised or malicious relay unable to forge protocol
traffic. Transport auth alone would let whoever controls the relay rewrite rows freely.

### Canonical payload byte sequences

Each is UTF-8, fields joined by `\n`, absent optional fields contributing an empty string.
Defined in `PayloadCanonical` and reimplemented identically on every platform.

| Construction | Fields, in order |
|---|---|
| `forOpen` | `secretId`, `transactionType`, `recipientKey`, `sealedLabel` (standard base64), `secretCreatedAt` (epoch ms), `ciphertext` (standard base64), `k`, `n`, `mimeType` |
| `forRespond` | `requestId`, `"approved"` or `"denied"`, `ciphertext` |
| `forRotation` | `recipientKey`, `newVerifyKey`, `newEncKey`, `newCipherSuite` |
| `forHeartbeat` | `ownerKey`, `secretIds` **sorted** then comma-joined, `optedOut`, `emittedAt` (epoch ms) |

**A signed timestamp has to survive the wire.** The receiver never verifies against the bytes the
sender built — it rebuilds them from the JSON it parsed — so `secretCreatedAt` and a heartbeat's
`emittedAt` must be transmitted with their milliseconds intact: `2026-01-01T00:00:00.123Z`, never
`2026-01-01T00:00:00Z`. Java's `Instant.toString` keeps them; Foundation's default
`ISO8601DateFormatter` drops them, and a dropped millisecond makes every signature fail to verify.

Three rules govern changes here:

- **Append only.** New fields go at the tail. Inserting one invalidates every existing
  cross-platform test vector and silently breaks interoperability between app versions.
- **Sort anything set-like.** `forHeartbeat` sorts `secretIds` so the signed bytes do not
  depend on the order a client happened to build the list in.
- **Nothing is escaped.** Fields are joined with newlines and nothing more, so a newline
  inside a field would be indistinguishable from a separator. The relay rejects control
  characters in `mimeType` for that reason.

Cross-platform agreement is proven, not assumed: fixed-seed vector tests on all four
implementations — relay, phon, Android and iOS — assert the same canonical bytes and, where
the signing library is deterministic, the same signature. (Apple's CryptoKit hedges its
signatures, so on iOS the vector tests assert canonical-bytes equality and verification
against a fixed signature instead.)

## Transaction types

Five types share the `share_requests` table. Three are consent-gated — Alice asks
something of Bob, Bob approves or denies — and two are pushes. Where each one falls in the
life of a secret or a share is in [lifecycles.md](lifecycles.md).

| Type | Direction | Carries | Purpose |
|---|---|---|---|
| `deposit` | sender → holder | `secretId`, `sealedLabel`, `secretCreatedAt`, `k`, `n`, `mimeType`, `ciphertext` | Give a holder a share. Delivered once on approval, then cleared from the row. |
| `retrieval` | sender → holder → sender | references `secretId`, with its `sealedLabel` | Ask for a share back. The holder re-encrypts fresh to the requester's current key. |
| `removal` | sender → holder | references the deposit, with its `sealedLabel` | Ask a holder to destroy a share. |
| `inventory` | holder → owner | `secretId`, `sealedLabel`, `secretCreatedAt`, `k`, `n`, `mimeType` — **never ciphertext** | Tell an owner what you still hold for them, so they can rebuild lost records. |
| `withdrawal` | holder → owner | `secretId`, `sealedLabel`, `secretCreatedAt` — nothing else | Tell an owner you have stopped holding a share, unilaterally. |

`inventory` and `withdrawal` are the odd ones out: each is created already `Approved`, has no
pending phase and no conflict check, and the recipient deletes it once consumed. They
self-approve because there is nothing to consent to — a holder volunteering "here is what I
hold for you", or "I hold this no longer", needs no permission from the person being told. A
holder may push either repeatedly.

**A withdrawal is explicit, not inferred from heartbeats.** Heartbeats already carry the
implication, and the owner already uses it the right way: a secret a holder stops naming stops
refreshing, and the holder drops out of `n_live` at the loss threshold — reversibly. A
withdrawal drives the *irreversible* step, forgetting the holder's record for that secret, which
needs a deliberate, signed act. Omission cannot be that:

- **Often there is nothing to omit from.** A holder sends heartbeats only to owners it holds
  something for, so deleting the last share from an owner ends heartbeats to that owner
  altogether. Silence, not an empty list.
- **Opted-out holders name nothing** by design, so for them omission means nothing at all.
- **Omission is not an act.** A storage fault on the holder's phone looks identical, and a record
  dropped on the strength of it cannot be brought back by a later heartbeat.
- **Omission is replayable; a withdrawal is monotonic.** A relay can serve an older heartbeat
  that predates a deposit. A withdrawal of a `secretId` stays true for ever — a holder never
  holds the same `secretId` again, since repair mints a fresh one — so replaying it changes
  nothing.

And it is a signed row of its own, not a state the relay sets on another: the relay cannot sign
one, so it cannot make an owner forget a holder who still has the share — the same reason an
approved removal is verified before it is acted on. Addressed to the owner's current key and
signed with the holder's, it also survives either side having rotated since the deposit.

**Consent is asymmetric on purpose.** Retrieval requires the holder's approval so they can
verify out of band — a phone call — that Alice really asked, rather than someone holding
Alice's stolen phone. Sender-initiated removal likewise requires approval: a sender cannot
compel a holder to delete. Holder-initiated deletion is unilateral and needs nobody's
permission, because a custodian who no longer wishes to hold something must always be able
to stop.

Row state is `Pending`, `Approved`, or `Denied`.

## Tables

Three, none of them about people — and none of them standing. **Every row is a message**,
deleted by the party it is addressed to once that party has acted on it: an approved deposit by
its sender as soon as she has seen the pickup, an answered retrieval or removal by its sender, a
denied one when she asks again, every push by its recipient. Nothing on the relay records who
keeps what for whom once the message about it has been read.

**`share_requests`** — the five transaction types above. Columns: `id`, `secret_id`, `sealed_label`,
`sender_key`, `recipient_key`, `state`, `ciphertext`, `k`, `n`, `mime_type`,
`secret_created_at`, `requested_at`, `responded_at`, `sender_signature`,
`recipient_signature`. `k`, `n` and `mime_type` are required for `deposit` and `inventory`,
forbidden for the other three.

A `retrieval` or `removal` row carries no pointer to the `deposit` it follows. It needs none:
`(secret_id, recipient_key)` already identifies the share it is about — the pair the relay
enforces while a deposit is in flight — so both parties match on it. A row that named a specific
deposit would point at a row that is gone once its pickup has been seen, and would be worthless
to a holder whose sender has since recovered onto a fresh device.

`sealed_label` is the secret's label, sealed by the sender to the recipient's encryption key with the
same box as a share, under HPKE info of its own (see [security.md](security.md)). Every type
carries one, because the recipient shows it: a holder sees it on a retrieval or removal request,
and a recovering owner rebuilds her records from an inventory push. The relay checks only that it
is non-empty, and cannot read it. Only the recipient can open it.

`mime_type` is the sender's claim about what the secret is — `text/plain` for typed text,
`image/png` or `image/jpeg` for a picked image — carried so the recipient can decide how to
render it once reconstructed. Unlike the label it is plaintext, which the relay routes without
interpreting, and it is never checked against the payload, which the relay cannot read. It must
be non-blank: `forOpen` renders an absent field and an empty one identically, so a blank value
would be a claim the signature cannot tell apart from no claim at all.

The clients do not simply believe whatever handed them a file: a picked image's type is read
off its own leading bytes, so a claim a device makes about its *own* secret is true by
construction. That is a client-side property and not something the relay can rely on — a
different client, or a hostile one, may claim anything.

### Request size

Two separate bounds, with two different jobs.

The relay refuses a ciphertext over **512 KiB** with `413`, and its body parser refuses any
request over **1 MiB**, also with `413`. These are operator guards — a `deposit` row holds the
only copy of a share in transit, so the relay is storage, and this is what stops one caller
parking arbitrary bytes in it.

The clients impose a much smaller limit of their own on the *secret* — see
[security.md](security.md). The relay's bound is deliberately looser and must stay that way: it
cannot know what any client's limit is, and a client raising its own should not require a relay
change in lockstep.

The parser's in-memory threshold and its hard limit are deliberately equal, so a body is never
spooled to a temporary file on the way past. Those bytes are ciphertext, and the relay has no
business writing them to disk.

**`key_rotations`** — signed "I am now this key" notices. Columns: `id`, `old_verify_key`,
`recipient_key`, `new_verify_key`, `new_enc_key`, `new_cipher_suite`, `signature`,
`created_at`. There is no state column: the recipient polls, verifies the signature against
the old key it already trusts, and deletes the row once consumed.

She leaves alone a rotation from a key she knows no contact by, and one with a signature that
fails. A contact who rotated twice while she was offline has left two notices, and if K1→K2 is
read before K0→K1, its old key is not known yet; the next pass applies it.

`POST` only ever inserts. Two rotations from the same old key to the same recipient mean a
retry after a crash mid-rotation, or two claims to one identity, one of them made with a stolen
key — and replacing the first would erase the evidence of the second.

**Expiry.** A rotation is sent once and can never be sent again: the old signing key is gone
once the new identity is active. One nobody reads — addressed to a key its recipient no longer
lists under, or from a key she knows no contact by — waits 180 days and is then deleted by the
relay, keyed on `created_at`, its receipt time. The contact it was meant for is then relinked
in person, as after recovery, and the rotating device's *awaiting relink* nudge already lists
who that is. The period is generous for that reason: holders are the people least likely to
open the app, and a row costs the relay a few hundred bytes and an old→new link it already saw
when the notice was pushed.

**`custody_heartbeats`** — signed "still guarding these for you" reports. Columns: `id`,
`holder_key`, `owner_key`, `secret_ids`, `opted_out`, `emitted_at`, `signature`, `created_at`,
with `UNIQUE (holder_key, owner_key)`. The owner deletes a heartbeat once read: what it told her
is kept on her own device, and the holder re-emits every interval. Until then `POST` **upserts**
— a newer push replaces an unread one, under a fresh `id`, so an owner deleting the one she read
can never delete one she has not.

She deletes every heartbeat she lists, including one she cannot use — from a key she knows no
contact by, or with a signature that fails. That is where heartbeats part from rotations, which
she leaves alone in the same case: a rotation is sent once, while a heartbeat has a newer one
behind it. Left on the relay, a heartbeat from a contact she has deleted would be refreshed by
every push its holder still makes, and so never expire.

`emitted_at` is the holder's own clock, and is signed; it is the time custody is dated from.
`created_at` is the relay's receipt time of the latest push, unsigned, and used for expiry and
by operators, never by the owner. A relay that could set the time custody was last proven could
keep a lost holder looking fresh by re-serving an old heartbeat, so the owner never reads it.

**Expiry.** The relay deletes a heartbeat that has been neither read nor superseded for 28 days —
one addressed to an owner who has changed her key or lost her phone, or from a holder who has
stopped pushing. It keys on `created_at`, never on `emitted_at`, because a holder whose clock
runs behind would otherwise have its heartbeats expire early. The period must not fall below the
apps' loss threshold of 9 days: a heartbeat that old would mark its holder lost anyway, so
expiring it changes nothing the owner concludes, whereas expiring a younger one could make a live
holder look lost to an owner who was offline for a week.

Expiry cannot reach one case: an owner gone for good whose holder still pushes to her. Each push
refreshes the row, so it stays — one per pair, by the upsert — until the holder lets the share
go. Telling *gone* from *offline* would take a record of when owners poll, which
[privacy.md](privacy.md) rules out.

**Why delete on read, not append-only.** A simpler design would never update or delete a
heartbeat: holders append, the owner acts on the latest one per holder, and expiry is the only
deletion. It is rejected for four reasons:

- **It turns heartbeats back into standing state.** The relay would hold a few rows of history
  for every active pair at all times, secret ids included, where today it holds only the
  heartbeats nobody has read yet. A breach, a backup or a legal demand would then yield the whole
  custody graph at any moment.
- **It breaks the rule every other row follows,** and with it what the privacy policy promises:
  every entry is deleted by the app it is meant for once that app has acted on it.
- **It makes expiry load-bearing.** Expiry would be what bounds the table, which pushes its
  period down to the loss threshold, instead of leaving it a generous safety net for edge cases.
- **It lets a buggy holder grow the table,** and every owner's download, with each poll. The
  upsert absorbs the same bug.

Rotation and heartbeat deliberately are *not* `share_requests` rows. Neither carries a
`secretId` and neither has a consent phase, so folding them in would mean a nullable
`secret_id` plus a column-load of permanent `NULL`s — a table pretending to be an
abstraction it is not.

Schema lives in [`../conf/evolutions/default/1.sql`](../conf/evolutions/default/1.sql).
Pre-launch, that file is **edited in place** rather than accompanied by new evolutions.

## Endpoints

```
POST   /share-requests             open a request (any of the five types)
GET    /share-requests             list requests visible to the caller
GET    /share-requests/{id}        fetch one
PATCH  /share-requests/{id}        approve or deny (recipient only)
DELETE /share-requests/{id}        delete one

POST   /key-rotations              push a signed rotation notice
GET    /key-rotations              list notices addressed to the caller
DELETE /key-rotations/{id}         delete one once consumed

POST   /custody-heartbeats         push (upsert) a signed heartbeat
GET    /custody-heartbeats         list the unread heartbeat per holder
DELETE /custody-heartbeats/{id}    delete one once read (owner only)
```

Deleting a row deletes that row and nothing else. A sender deletes an approved `deposit` as soon
as she has seen the pickup, and a `retrieval` or `removal` she opened in the meantime — Destroy
tapped before the pickup was seen, say — must not go with it.

## Delivery: polling only

There is no WebSocket and no push channel. Clients poll on app open and periodically while
foregrounded. Background push via FCM or APNs is deliberately absent: it would introduce
Google and Apple as intermediaries and leak metadata to them, which is hard to square with
a design whose whole point is that no third party learns anything.

Custody heartbeats piggyback that same poll — when a holder's app polls and the emission
interval has elapsed for a given owner, it emits one heartbeat covering all of that owner's
secrets. Coalesced per owner, so the cost is proportional to the number of people you hold
for, not the number of shares. Foreground only: a custodian who has not opened the app in
weeks genuinely *is* a redundancy risk, and papering over that with a background job would
report health that nobody has verified.

## Absence is never a signal

The relay may garbage-collect any row at any time. This is the single invariant that keeps
that safe:

> A missing row means "collected, or never sent". It never means "done", and it never
> means "lost".

Everything is therefore either idempotently re-emitted on the next poll (heartbeats, inventory
pushes) or re-issuable by the user (action requests).
Clients follow one rule when reconciling: **upsert, never delete.** A row's disappearance
must never cause a client to forget something.

Two kinds of row are neither, and each costs something bounded when lost:

- **A `withdrawal`** — once the share is gone, nothing re-emits it. The owner misses the explicit
  answer, not the share's safety: a monitored holder who has stopped holding stops naming the
  secret, falls silent for it, and drops out of `n_live` at the loss threshold like any holder
  who stops reporting.
- **A rotation** — sent once, and the key that signed it is gone. The contact it was meant for
  keeps the old key and has to be relinked in person, as after recovery; the rotating device's
  *awaiting relink* nudge already lists them.

**The rule binds the relay too.** Approving a `removal` sweeps the rest of that
(`secret_id`, `sender_key`, `recipient_key`) triple — the `deposit` above all, which may
still hold ciphertext for a share that no longer exists — but keeps the answered `removal`
row, signed. It fits neither escape above: the holder cannot re-emit it, having deleted
everything it refers to, and the sender cannot re-issue a request for a share that is
already gone. Since the sweep takes every other trace of that holder's custody with it,
deleting the answer as well would leave the sender an absence to read, and an absence says
nothing. She deletes the row herself once she has acted on it.

With every row a message, retention has one question to answer — how long to wait for an
addressee who never comes — and it wants a generous answer, so an offline counterparty can still
act. The relay answers it itself, with a sweep that runs inside the app (`jobs.RetentionSweep`),
so a self-hosted relay expires rows the same way without its operator doing anything. The period
for each table is configuration, under `relay.retention` in `application.conf`.
`custody_heartbeats` and `key_rotations` have one, described above. `share_requests` has none
yet, so a row there stays until its addressee deletes it.

**One exception needs care.** A `deposit` row carries the only copy of an encrypted share
in transit, so collection before pickup would appear to lose it — and the polynomial is not
retained, so a single share cannot be cheaply re-minted. The sender therefore keeps each
encrypted blob until that holder's pickup is confirmed, then discards it. This is safe
precisely because the blob is sealed to the *holder's* key under an ephemeral key the sender
discarded at sealing: she cannot open what she is retaining, so holding all *n* is *n* opaque
forward-only blobs, not a reconstructable secret sitting on one device.

**The blob is the prerequisite, not yet the mechanism.** Nothing re-deposits from it today,
and that path lands with collection itself. Nothing costs anything meanwhile, because the
retention sweep does not collect `share_requests` rows in the first place — but extending it to
deposits *without* the client half is what would turn this into the very loss the retention
exists to prevent.

Pickup counts as confirmed through either channel — the relay showing the deposit accepted
(fast, but collectable before she ever polls), or the holder's signed heartbeat naming that
`secretId` (durable, immune to collection). A *later* loss is a different problem with a
different remedy; see [trust-model.md](trust-model.md).

Which makes a deposit approval an **acknowledgement, not the delivery**. The pending row
must already carry the ciphertext — the sender's signature covers it, so a holder cannot
verify the row without it — and the approval's job is to tell the sender the share arrived
and let the relay drop its copy. Holders therefore decrypt and store before approving, never
after; see "The holder decrypts at pickup" in [security.md](security.md).

## The two clocks never meet

The five-minute nonce window times a single request *in flight*. Row retention is storage
of an already-accepted row. A deposit may sit for weeks though the request that created it
authenticated within five minutes, and every re-emission is a fresh signed request with a
fresh nonce. Long retention never stresses the auth window.
