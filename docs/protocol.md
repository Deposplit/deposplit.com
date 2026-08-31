# Protocol

How the apps talk to a relay. The relay stores and forwards opaque bytes between public
keys; it never decrypts anything and never learns who anybody is. The formal specification
is [`../conf/openapi.yaml`](../conf/openapi.yaml) — this document explains the shape and
the reasoning behind it.

## Addressing: keys, not accounts

There is no registration. A caller *is* their Ed25519 public key, and every row the relay
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
| `X-Deposplit-Verify-Key` | The caller's Ed25519 public verify key, base64url |
| `X-Deposplit-Nonce` | `<unix-ms>.<random>`, unique per request |
| `X-Deposplit-Signature` | Ed25519 signature over the canonical string, base64url |

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
| `forOpen` | `secretId`, `transactionType`, `recipientKey`, `label`, `secretCreatedAt` (epoch ms), `shareId`, `ciphertext` (standard base64), `k`, `n` |
| `forRespond` | `requestId`, `"approved"` or `"denied"`, `ciphertext` |
| `forRotation` | `recipientKey`, `newVerifyKey`, `newEncKey`, `newCipherSuite` |
| `forHeartbeat` | `ownerKey`, `secretIds` **sorted** then comma-joined, `optedOut` |

Two rules govern changes here:

- **Append only.** New fields go at the tail. Inserting one invalidates every existing
  cross-platform test vector and silently breaks interoperability between app versions.
- **Sort anything set-like.** `forHeartbeat` sorts `secretIds` so the signed bytes do not
  depend on the order a client happened to build the list in.

Cross-platform agreement is proven, not assumed: fixed-seed vector tests on all platforms
assert the same canonical bytes and, where the signing library is deterministic, the same
signature. (Apple's CryptoKit hedges its signatures, so on iOS the vector tests assert
canonical-bytes equality and verification against a fixed signature instead.)

## Transaction types

Four types share the `share_requests` table. Three are consent-gated — Alice asks
something of Bob, Bob approves or denies — and one is a push.

| Type | Direction | Carries | Purpose |
|---|---|---|---|
| `deposit` | sender → holder | `secretId`, `label`, `secretCreatedAt`, `k`, `n`, ciphertext | Give a holder a share. Delivered once on approval, then cleared from the row. |
| `retrieval` | sender → holder → sender | references `secretId` | Ask for a share back. The holder re-encrypts fresh to the requester's current key. |
| `removal` | sender → holder | references the deposit | Ask a holder to discard a share. |
| `inventory` | holder → owner | `secretId`, `label`, `secretCreatedAt`, `k`, `n` — **never ciphertext** | Tell an owner what you still hold for them, so they can rebuild lost records. |

`inventory` is the odd one out: it is created already `Approved`, has no pending phase and
no conflict check, and the recipient deletes it once consumed. It self-approves because
there is nothing to consent to — a holder volunteering "here is what I hold for you" needs
no permission from the person being told. A holder may push it repeatedly.

**Consent is asymmetric on purpose.** Retrieval requires the holder's approval so they can
verify out of band — a phone call — that Alice really asked, rather than someone holding
Alice's stolen phone. Sender-initiated removal likewise requires approval: a sender cannot
compel a holder to delete. Holder-initiated deletion is unilateral and needs nobody's
permission, because a custodian who no longer wishes to hold something must always be able
to stop.

Row state is `Pending`, `Approved`, `Denied`, or `Withdrawn`. `Withdrawn` is the tombstone
a holder leaves behind on unilateral deletion — a courtesy so the sender is not blindsided
by silently eroding redundancy, never an authoritative signal.

## Tables

Three, none of them about people.

**`share_requests`** — the four transaction types above. Columns: `id`, `secret_id`,
`label`, `sender_key`, `recipient_key`, state, `share_id`, `ciphertext`, `k`, `n`,
`secret_created_at`, `requested_at`, `responded_at`, `sender_signature`,
`recipient_signature`. `k` and `n` are required for `deposit` and `inventory`, forbidden
for `retrieval` and `removal`.

**`key_rotations`** — signed "I am now this key" notices. Columns: `id`, `old_verify_key`,
`recipient_key`, `new_verify_key`, `new_enc_key`, `new_cipher_suite`, `signature`,
`created_at`. There is no state column: the recipient polls, verifies the signature against
the old key it already trusts, and deletes the row once consumed.

**`custody_heartbeats`** — signed "still guarding these for you" reports. Columns: `id`,
`holder_key`, `owner_key`, `secret_ids`, `opted_out`, `signature`, `created_at`, with
`UNIQUE (holder_key, owner_key)`. `POST` **upserts** and `GET` does **not** delete, because
a heartbeat is a standing status a holder re-emits rather than a one-shot delivery.

Rotation and heartbeat deliberately are *not* `share_requests` rows. Neither carries a
`secretId` and neither has a consent phase, so folding them in would mean a nullable
`secret_id` plus a column-load of permanent `NULL`s — a table pretending to be an
abstraction it is not.

Schema lives in [`../conf/evolutions/default/1.sql`](../conf/evolutions/default/1.sql).
Pre-launch, that file is **edited in place** rather than accompanied by new evolutions.

## Endpoints

```
POST   /share-requests             open a request (any of the four types)
GET    /share-requests             list requests visible to the caller
GET    /share-requests/{id}        fetch one
PATCH  /share-requests/{id}        approve or deny (recipient only)
DELETE /share-requests/{id}        delete one
DELETE /share-requests             recipient-initiated bulk delete
POST   /share-requests/withdraw    recipient-initiated unilateral withdrawal

POST   /key-rotations              push a signed rotation notice
GET    /key-rotations              list notices addressed to the caller
DELETE /key-rotations/{id}         delete one once consumed

POST   /custody-heartbeats         push (upsert) a signed heartbeat
GET    /custody-heartbeats         list the latest heartbeat per holder
```

Deleting a `deposit` row cascades to the `retrieval` and `removal` rows referencing it.

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

Everything is therefore either idempotently re-emitted on the next poll (heartbeats,
rotations, inventory pushes, tombstones) or re-issuable by the user (action requests).
Clients follow one rule when reconciling: **upsert, never delete.** A row's disappearance
must never cause a client to forget something.

Retention classes are an operator concern, not application logic: consent-gated action
requests want generous retention so an offline counterparty can still act, while
fire-and-forget pushes can be short-lived and latest-wins. Neither is enforced by the relay
code today.

**One exception needs care.** A `deposit` row carries the only copy of an encrypted share
in transit, so collection before pickup would appear to lose it — and the polynomial is not
retained, so a single share cannot be cheaply re-minted. The sender therefore keeps each
encrypted blob until that holder's pickup is confirmed, then discards it. This is safe
precisely because the blob is encrypted to the *holder's* key: the sender cannot decrypt
what she is retaining, so holding all *n* is *n* opaque forward-only blobs, not a
reconstructable secret sitting on one device.

**The blob is the prerequisite, not yet the mechanism.** Nothing re-deposits from it today,
and that path lands with collection itself. Nothing costs anything meanwhile, because no
collection job exists to GC a deposit row in the first place — but adding one *without* the
client half is what would turn this into the very loss the retention exists to prevent.

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
