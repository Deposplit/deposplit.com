# Trust model

Cryptography decides what is *possible*. This document covers what Deposplit decides is
*acceptable*: how you come to believe a key belongs to a person, what happens when that key
changes, and how you find out that a share you distributed is quietly gone.

The primitives underneath are in [security.md](security.md).

## Verification levels

A contact carries one of four levels, ordered by how many independent assurances you had
when you accepted their key.

| Level | Assurances | Typical route |
|---|---|---|
| `VERY_LOW` | 0 | A key from e-mail, LinkedIn, a business card — no live check |
| `LOW` | 1 | *Either* a trusted channel *or* proof of life, not both |
| `HIGH` | 2 | Both — a Signal video call with a verified safety number, showing their QR |
| `VERY_HIGH` | in person | You were physically present and scanned their QR |

The two axes are **trusted channel** and **proof of life**. Their two middle combinations —
trusted channel without proof of life, proof of life over an untrusted channel — are
deliberately merged into one rung, because arguing about which is stronger produces no
useful answer. The user-facing rule is simply: *count your independent assurances.*

**Levels are user-asserted context labels, not cryptographic facts.** The app cannot tell an
e-mailed key from a Signal-relayed key from one shown on a video call — and even an
"in-person" scan cannot be proven, since a QR displayed on a screen scans identically. The
cryptographic fact is only *this key was pinned*; the level is honest metadata about how.
So the UI asks rather than infers.

Manual key entry offers only `VERY_LOW` through `HIGH`: physical co-presence cannot be
asserted by typing a key in by hand, so `VERY_HIGH` is reachable only through the in-person
scan flow, which defaults to it. The QR payload itself never carries a level — it is
assigned by the *receiving* device from the context in which it obtained the key, never
claimed by the sender.

## Key rotation

Someone who still holds their old keys and wants new ones pushes a signed
`rotate(K_old → K_new)` notice. The recipient verifies it against the old key they already
trust and updates the contact **in place, preserving `contactId`**, so held and distributed
shares stay linked.

Two rules govern acceptance:

- **Auto-accept only on a valid old-key signature.** An attacker without `K_old` cannot
  forge one. Any change *not* backed by `K_old` — that is, recovery after key loss —
  requires human re-verification and is surfaced as a high-stakes decision, never a silent
  accept.
- **Auto-accepting downgrades the level to `min(level, LOW)`.** A signed rotation proves
  continuity of key control; it is not a fresh check that you are talking to the right
  person. In the two-axis language above it is a trusted channel with no proof of life —
  one assurance, so `LOW`. A rotation from an already-`VERY_LOW` key stays `VERY_LOW`,
  because continuity from an unverified anchor adds nothing.

The unifying principle: **a contact's level always reflects the most recent *personhood*
assurance about its *current* key.** A cryptographic rotation is not one, so it can never
exceed `LOW`. Only fresh human verification — ideally an in-person re-scan — restores a
higher level. A cipher-suite change counts as a key change for this purpose even when the
key bytes are identical.

Contacts must always be **updated**, never deleted and re-added. Re-adding mints a fresh
`contactId` and orphans every share linked to the old one. The UI steers accordingly.

### Rotating while a share is still in flight

A share is encrypted to the holder's `encKey` as it stood at deposit time, so a holder who
rotates before collecting it would find the ciphertext addressed to a key they no longer
hold. Two things keep that from costing anything:

- **Pickup stores before it approves**, so a failed decrypt leaves the deposit pending with
  the relay's copy intact and the next poll retries. Approving is the destructive read.
- **One generation of `decKey` survives the rotation** (see
  [security.md](security.md)), so that retry succeeds rather than repeating forever.

Regenerating an identity still drains the inbox under the old keys first, because collecting
in advance is cheaper than relying on the fallback. That drain is best-effort — an
unreachable relay must never block someone rotating precisely because they believe the old
key is compromised — but a drain that could not reach every relay is now **reported** rather
than silently dropped, so the choice of what to do next stays with the person rotating.

## Revocation is social, not cryptographic

If an attacker holds your stolen key, you and the attacker are cryptographically
indistinguishable — you can both sign. A key therefore **cannot revoke itself**, and a
relay-side blocklist cannot work either: the relay is blind, and a block signed by the
stolen key is exactly as valid as one signed by you. An unforgeable revocation would need a
pre-provisioned recovery key, which is another secret you would have to keep safe — the
problem Deposplit exists to solve.

So revocation is anchored in people:

- A contact carries a growing set of **revoked keys** (`revokedVerifyKeys`), set out of band
  when someone tells you their key is compromised. It is a historical set rather than a
  single flag, so a later legitimate relink is never blocked by an old entry.
- A revoked key **cannot auto-accept a rotation**. An attacker's rotation push signed with
  the stolen key is ignored.
- Two conflicting claims to be the same identity produce a durable **key conflict** record,
  surfaced for manual resolution and **never auto-resolved**. The conflict is captured
  locally the moment it is detected, before the relay notice is deleted, so the alert never
  depends on relay retention.
- The conflict UI offers **Dismiss only** — deliberately no "Accept". The one legitimate way
  forward is a fresh human-verified relink through the normal flow, not a second, weaker
  acceptance path.

The tiebreaker is the layer underneath: *k*-of-*n* consent plus verification level. An
in-person-verified claim beats a remote attacker, and reconstruction still needs *k* humans.

### Retrieve-approval hardening

The attack signature is *key change, then a quick retrieval*. So the approval screen for a
retrieval — and only for a retrieval — shows **"this requester's key changed N days ago"**
and urges an out-of-band check. This composes with the downgrade rule: a recently rotated
key is by construction at most `LOW`, so it lands in the tightest scrutiny automatically.

## Recovery after losing everything

You lose your phone. Your keys are gone, and so is your entire local state — contacts,
pseudonyms, verification levels, and the record of which secrets you split and who holds
them. The relay is no help: its rows are addressed to a key you can no longer prove you
control.

Recovery is **social, `k`-of-`n` by construction, and returns metadata only.**

The `k`-of-`n` part is not a policy knob. Reconstruction needs *k* shares and one holder
supplies one share, so "a single trusted approver could suffice" is not an option here —
it is arithmetic. What a single approver *can* do is something lighter: propagate a key
change to a contact who holds no share.

The mechanism, per holder:

1. You reach a holder you remember, out of band — ideally in person — and re-exchange QR
   codes.
2. Their app **relinks** the re-presented identity to the existing contact ("this new key is
   my old contact Alice"), updating in place and preserving `contactId`, which re-associates
   the shares they hold.
3. Their app pushes an `inventory` notice per share held: `secretId`, `label`,
   `secretCreatedAt`, `k`, `n`, `mimeType` — **and no ciphertext**. That rebuilds your records.

**Why metadata only.** Returning the shares themselves would create a mass-reconstruction
moment: every secret you own decrypted onto one fresh device at once, a fat single-point
target. Metadata-only recovery restores the *ability* to reconstruct; each secret is then
assembled on demand, one at a time, through the normal consent-gated flow.

Two supporting details. *Who your holders are* is not derivable from the system — holders
do not know one another, which is deliberate and raises the collusion bar — so the source
of truth is your memory, optionally helped by a catalogue backup (an export of contacts,
levels and share metadata; no private keys, no shares, so it weakens nothing). And *`k` and
`n` travel in the deposit payload*, so relinking any one holder of a secret already tells
you the threshold and therefore how many more holders to find. Holders report `k` and `n`
but never learn about each other.

There is no recovery key, deliberately. If you could safely self-custody a recovery key you
could safely self-custody the original secret and would not need any of this.

## Custody monitoring

A holder losing their device is genuine redundancy loss, and their new device cannot tell
you — it has no record it ever held anything. Only you can notice.

Monitoring is a **holder-initiated push**, not an owner-initiated poll: *Bob's app tells
Alice "still guarding these for you"*. Mechanically the two are equivalent — both read
sustained silence as the loss signal — but the framing matters. A pull puts the custodian
in the position of an audited suspect whose app answers on his behalf; a push makes him an
active custodian reporting in, and makes opting out a natural act rather than an evasion.

- Heartbeats are **signed** over the reported `secretIds` and a timestamp, so a blind relay
  cannot forge "Bob is fine".
- They are **default-on and holder-disableable**, globally or per owner.
- **Opting out is itself a signed notice**, so the owner can distinguish *"chose privacy,
  status unknown by choice"* from *"went dark, possibly lost"*. An unmonitorable holder is a
  standing advisory, never a loss alarm.
- There is **no pull mechanism at all.** A "refresh now" button would resurrect the audit
  flavour, and the retrieval flow already *is* the on-demand check — a stronger one, since
  it asks for bytes rather than liveness. The moment you actually need a fresh reading is
  when you are about to reconstruct, and that is exactly when you open retrievals.

### Freshness

Your view of redundancy is only ever as fresh as the last heartbeat, so the UI must never
present a weeks-old "healthy" as live truth. Every holder falls into one bucket:

| Bucket | Condition | Counts toward `n_live`? |
|---|---|---|
| **Confirmed** | Proof of custody within the loss threshold | Yes — with a "getting stale" sub-flag as it nears the edge |
| **Unmonitored by choice** | Sent the signed opt-out | No — shown separately, no alarm |
| **Silent / overdue** | Heartbeats expected, none within the threshold | **No — drops out of `n_live`** |

*Any* signed proof of custody refreshes the clock — a heartbeat, a pickup approval, or a
retrieval approval — so a holder you recently retrieved from is fresh automatically.

Dropping out at the threshold, rather than merely being annotated, is the point: erosion has
to show up in the number you plan against, not in a footnote. It is also **reversible** — a
long-silent holder who reopens their app pops straight back to Confirmed and `n_live`
recovers. That makes erring toward alarm cheap: a false positive costs you a nudge to a
friend, not an irreversible re-split.

Current tuning: emission every **3 days**, stale warning at **2×** that, loss threshold at
**3×**. Those numbers are UI tuning, not architecture. What is load-bearing is that the
interval stays well inside the window in which two independent holder losses could occur,
and that a **single** missed beat is never read as loss.

## Secret health, and repair

A secret is `ACTIVE` or `DISCARDING`. The alarm ladder compares freshness-gated `n_live`
against `k`:

| Condition | Level | What to do |
|---|---|---|
| `n_live ≥ k + 2` | healthy | Nothing |
| `n_live == k + 1` | caution | Margin of one — re-split soon |
| `n_live == k` | **critical** | Reconstruct and re-split **now** — last recoverable moment |
| `n_live < k` | lost | Unrecoverable — change the underlying secret |
| *while `DISCARDING`* | suppressed | The decline is intentional |

The alarm fires at `n_live == k`, not below it. At exactly *k* you can still gather a
threshold, reconstruct, and re-split to restore margin. Below it there is nothing left to
do but change the secret itself.

**Repair requires reconstruction.** You cannot top up a lost holder: shares come from one
specific polynomial, and the polynomial is not retained — retaining it would defeat the
whole exercise. So restoring redundancy means *reconstruct, then re-split to a fresh holder
set*. That is precisely why catching loss while still comfortably above *k* matters.

There is no separate "rotate the value" flow, because none is needed. Replacing a secret is
`deposit(new value)` followed by `discardSecret(old)`; restoring redundancy on an unchanged
value is `reconstruct` first, then the same two steps. `discardSecret` fans out a `removal`
request to every holder — a *request*, not a command, so each holder still approves, and a
holder who never responds is escaped locally. Every deposit mints a fresh `secretId` and
thus a fresh polynomial, which matters: shares from two different polynomials for the same
value are not interchangeable.

Reconstruction itself is a pure read. It returns the secret and changes nothing —
`discardSecret` is the only teardown path.

**One honest caveat.** Re-splitting the *same* value restores availability, not
confidentiality: old holders' shares still reconstruct the still-live value until they
approve their removals. When the value itself changes, a lingering old share reconstructs
something already retired. For a value that cannot be changed — a seed phrase — best-effort
discard is the honest but imperfect mitigation.

## Choosing k and n

The bounds are `2 ≤ k ≤ n ≤ 255`, and there is deliberately **no UI ceiling on n** — a
board-of-directors secret legitimately wants a large *n* with a correspondingly large *k*.
Instead there are three non-blocking warnings at split time:

- **Operational burden** — a large *n* means exchanging keys with, approving pickups from,
  and monitoring *n* people. This is a warning about *work*, not danger, and the wording
  should say so.
- **Confidentiality tail** — *k* low relative to *n* means a small clique can reconstruct
  behind your back.
- **Availability tail** — *n* − *k* small means little redundancy. At `k == n`, a single
  lost holder destroys the secret permanently.

The two tails are symmetric: at fixed *n*, confidentiality rises with *k* and availability
rises with *n* − *k*. You cannot push both up without raising *n*. The warnings exist so
that nobody picks 10-of-10 in silence and discovers the consequence when a phone dies.
