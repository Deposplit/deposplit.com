# Security

What Deposplit protects, how, and — just as important — what it does not protect. The
human-facing rules that sit on top of these primitives are in
[trust-model.md](trust-model.md).

## Threat model

Deposplit assumes the relay is hostile. Not "might be breached" — hostile. Everything below
is designed so that an attacker who owns the server outright, reads the whole database and
watches all traffic still learns nothing worth having.

| Adversary | Outcome |
|---|---|
| Full relay breach: database, disk, traffic | Opaque ciphertext addressed between public keys. No plaintext, no identities, no social graph. |
| A single malicious or compromised holder | Holds one share. Fewer than *k* shares are information-theoretically independent of the secret — not merely hard to invert, but carrying no information about it. |
| A malicious holder who lies at reconstruction | Detected, and with enough margin identified and excluded. See *Reconstruction integrity*. |
| Stolen device, locked | Private keys sit in Keystore/Secure Enclave; reconstruction is gated behind biometrics. |
| Stolen device, unlocked, keys extracted | Serious — but the attacker still has to defeat *k* holders' out-of-band consent. The keypair is not the last line of defence; the humans are. Yields the current keys plus the one retained previous `decKey` (see *Key custody*). |
| A stolen device backup or transfer image | One share per secret held, plus the contact graph and the secret labels. Below threshold on its own; the attacker's next step is *k* more backups. See *Data at rest, and what a backup carries*. |
| Fewer than *k* holders colluding | Nothing. This is the guarantee Shamir actually provides. |
| *k* holders colluding | Full reconstruction. This is by design and cannot be defended against — choosing *k* is choosing who you trust collectively. |

Explicitly **out** of scope: a compromised client binary, a malicious OS, and traffic
analysis against a global passive adversary. The relay also cannot be prevented from
learning that *some* key deposited *something* for *some other* key at a given time.

## Identity: two keypairs, no account

At first launch a device generates two keypairs and picks a display name. That is the whole
onboarding: no server call, no e-mail, no password. The keypair *is* the identity.

| Name | Algorithm | Role |
|---|---|---|
| `verifyKey` / `signKey` | Ed25519 | Public / private halves used to verify and produce signatures |
| `encKey` / `decKey` | X25519 | Public / private halves used to encrypt to a recipient and decrypt as one |

The names describe the *role*, not the algorithm, so a future algorithm change never leaves
a field lying about what it holds. The same four names are used whether the keys are yours
or a contact's.

The pseudonym is stored locally and never sent to the relay. Contacts may additionally
carry a purely local nickname, so two people who both call themselves "Paul" can be told
apart; nicknames never leave the device.

### Key custody

**Android** — both private keys live in the Android Keystore, wrapped with AES-256-GCM
under the `deposplit_master` alias, and never leave the device as raw key material.

**iOS** — raw 32-byte key material in the Keychain (`kSecClassGenericPassword`, service
`com.deposplit.Deposplit`, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`). The Secure
Enclave is deliberately **not** used: it only handles P256, and Deposplit needs Curve25519.

**One generation of `decKey` is retained across a rotation**, under the same protection as
the current one, and is tried only after the current key fails to open a box. A share is
sealed to whichever `encKey` the holder advertised at deposit time, so without it a holder
who rotates between a deposit and their pickup could never collect that share: the row stays
pending and every later poll fails identically. The displaced `signKey` is **not** retained —
that would let an extracted device sign a rotation notice as the previous identity, which
every contact auto-accepts as proof of key continuity.

The cost is stated plainly in the table above: an attacker who extracts keys from an unlocked
device gets two generations of key-agreement key rather than one. It buys them one more
share, and a single share is still information-theoretically independent of the secret.

## Shamir's Secret Sharing

Split *n* ways, reconstruct from any *k*. Implemented over **GF(2⁸)**, byte by byte, with
the AES irreducible polynomial x⁸ + x⁴ + x³ + x + 1 and generator `0xe5` — the same
lookup tables HashiCorp Vault uses. A share is `[n bytes of y-values] || [1 byte
x-coordinate]`; reconstruction is Lagrange interpolation at x = 0.

The Kotlin, Swift and Scala implementations are hand-ports of
[privy-io/shamir-secret-sharing](https://github.com/privy-io/shamir-secret-sharing) (MIT,
TypeScript), not third-party dependencies. The whole algorithm is five functions and two
tables; taking a heavyweight dependency for that, or trusting a single-contributor port of
a security primitive, both looked worse than porting ~250 well-understood lines and testing
them against shared vectors.

Bounds are `2 ≤ k ≤ n ≤ 255`, enforced in the domain. `k ≥ 2` is a hard floor: `k = 1` is
not secret *sharing*, since any single holder could reconstruct alone. `n ≤ 255` is imposed
by the field, x = 0 being reserved for the secret itself.

### Reconstruction integrity

Classic Shamir has **no** integrity. Any *k* shares define *a* polynomial and yield *a*
secret — a single corrupt or dishonest share silently produces the wrong answer with no
error. The payload signature on a returned share proves it is *authentic* (it really came
from that holder); it says nothing about whether it is *correct*, because a malicious
holder signs bad bytes perfectly well.

The fix is over-determination. Reconstruction fans out beyond *k* to the holders most
recently confirmed alive, and `combineWithIntegrity` does bounded-exhaustive
maximum-agreement decoding: every size-*k* subset is tried as a hypothesis, the one with
the largest byte-for-byte agreeing set wins, and it is accepted only if it clears the
Reed–Solomon unique-decoding bound. That bound is a mathematical guarantee, not a
heuristic — two distinct polynomials of degree < *k* can agree on at most *k* − 1 points.

With margin *m* = responses − *k*: *m* = 1 **detects** a bad share, and *m* ≥ 2 identifies
and **excludes** the liar while still reconstructing correctly. The search is capped at
5,000 hypotheses as a safety valve against pathological fan-outs.

An honest consequence: integrity degrades exactly when redundancy is already thin. At
`n_live == k` a secret still reconstructs, but with no margin to cross-check — and the app
says so rather than implying a confidence it does not have.

A per-share commitment (a stored `H(share)`) was considered as an alternative and rejected.
A hash of a low-entropy secret's tiny share is brute-forceable, so an attacker who
exfiltrated the commitments — a stolen backup, a cloud sync, file-reading malware, no live
device needed — could recover all *n* shares and reconstruct, bypassing the holder-consent
layer entirely. Keeping the owner's device holding **nothing that pins her shares** is both
simpler and more faithful to the premise.

## Transport encryption

Every leg uses the same static-static Diffie-Hellman box:

1. **Agreement** — X25519(my private key, their public key) → 32-byte shared secret
2. **Derivation** — HKDF-SHA-256(ikm = shared secret, salt = nonce, info = `"deposplit-share"`) → 32-byte key
3. **Encryption** — ChaCha20-Poly1305(key, nonce, plaintext) → ciphertext + 16-byte tag

Wire format:

```
suiteTag(1 byte) || nonce(12 bytes) || ciphertext+tag
```

No native crypto libraries anywhere. BouncyCastle (`X25519Agreement`, `HKDFBytesGenerator`,
`ChaCha20Poly1305`) on Android and the relay; Swift Crypto (`Curve25519.KeyAgreement`,
`HKDF`, `ChaChaPoly`) on iOS. libsodium was the original choice and was dropped: it needs a
native `.so` plus JNA on Android and hand-porting on iOS, for primitives both platforms
already ship. ChaCha20-Poly1305 was chosen over XSalsa20-Poly1305 specifically because it
exists in all three stacks.

### The holder decrypts at pickup

This is the load-bearing decision, and it is not obvious.

1. **Deposit** — the sender encrypts each share to the holder's *current* public key.
2. **Pickup** — the holder decrypts the ciphertext their pending deposit row already carries
   and stores the **plaintext** share locally, and only *then* approves. The ciphertext is
   discarded and the relay row cleared.
3. **Retrieval** — the holder re-encrypts that plaintext to the requester's *current*
   public key, looked up live.

**That order is the guarantee, not an implementation detail.** Approving is what clears the
relay's only copy, so it has to be the last step of pickup: anything that fails before it —
a share sealed to a key the holder has since rotated away from, a device that dies
mid-sync — leaves the deposit pending with the relay's copy intact, and the next poll simply
retries. Approving first would consume the share and lose it with no error anyone could see.

Plaintext at rest sounds alarming and is not: one share below the threshold carries no
information about the secret, and it still sits in app-private storage under the OS's
file-based encryption. What this buys is that **no participant's ability to decrypt is ever
bound to a key from the past.**

That is what makes social recovery possible at all. Under the earlier design — where
ciphertext stayed encrypted to the sender's key until reconstruction — a sender who lost
her device could never decrypt her own shares again, no matter how many holders cooperated.
The secret was unrecoverable precisely in the scenario the product exists for. Now the
holder re-encrypts to whoever the sender *is now*, so both holder key rotation and total
sender key loss are survivable.

The relay is blind at every phase regardless: ciphertext at deposit, ciphertext at
retrieval.

## Data at rest, and what a backup carries

Seven JSON files, unwrapped, in app-private storage — `filesDir` on Android, the Documents
directory on iOS, which is not exposed to the Files app:

| File | Holds |
|---|---|
| `shares.json` | the plaintext shares held for other people, one per secret |
| `contacts.json` | pseudonyms, public keys, verification levels, relay overrides |
| `distributed_shares.json` | which contact holds which secret's share |
| `secrets.json` | your own secrets' labels, `k`, `n`, `mimeType` |
| `retained_deposits.json` | ciphertext sealed to holders' keys, which even the sender cannot open |
| `key_conflicts.json`, `contact_relinks.json` | unresolved key changes, and who has re-verified this device |

Plus a small amount of preference state: pseudonym, entitlement, default relay, and on Android
the two public keys.

**The private keys are not among them, on either platform.** The Keystore does not export key
material, and every iOS Keychain item is `ThisDeviceOnly` — which there covers the *public*
halves too, so a restored iPhone comes back with no identity at all, while a restored Android
still holds public keys and would otherwise show a QR code for an identity nobody can use. Both
cases are detected at launch; see *After a phone switch* in [trust-model.md](trust-model.md).

**All of it rides the platform's backup and its device-to-device transfer, deliberately.** Two
reasons, and the second is the one that decides it.

A backup is one more copy of one device, so it inherits the property the whole design already
rests on: no single device holds enough. A holder's share is information-theoretically
independent of its secret, exactly as it is on the device it was copied from.

And excluding the held shares would turn a routine phone switch into the loss that is hardest to
notice. A holder's restored phone would have no record it ever held anything, so it could not
report the gap, and the owner would see only their redundancy quietly degrading — for every
secret that holder guards, at once. The cost of switching backup off is not paid by the person
who switches it off.

What an attacker gains from a stolen backup is therefore bounded, but not nothing, and the
revealing part is not the shares. An owner's copy names all *n* of their holders and which
secret each one guards. Reach *k* of those holders' backups and the secret reconstructs with no
consent and no live device — the same shape as the argument that rejected per-share commitments
above, with the difference that commitments would have collapsed the attack to a *single*
backup, while this still costs *k*, spent against people who do not know one another. The labels
in `secrets.json` are worth more to a targeted attacker than any share in the file.

The platforms protect the copy unequally, and only one of them can be improved by the user.
Android encrypts its backup with the device's screen lock. iCloud Backup is encrypted to Apple's
keys unless **Advanced Data Protection** is switched on, which is the single most useful thing
an iPhone user can do for the data described here.

Both platforms now let the user exclude an individual app — iOS under iCloud Backup, Android
since version 16 under Google Backup — and on Android doing so also deletes what has already
been backed up. So this is a decision the app states rather than one it makes, and both apps
state it in Settings, consequence included.

## Rendering a reconstructed secret

Every secret carries a sender-declared `mimeType` — `text/plain` for typed text, `image/png` or
`image/jpeg` for a picked image — which travels with the deposit payload and the `inventory`
recovery push. It is a **claim**, at the same trust level as `label`: nothing on a receiving
device checks it against the bytes, and the relay could not check it if it wanted to, seeing
only ciphertext.

A device does check its *own* secrets. A picked image's type is read off its leading bytes
rather than taken from the picker, the file name, or the system's guess, so a claim a device
makes about something it split itself cannot disagree with what it split. That is worth having,
but it is not a property anyone else can rely on: a different client may claim whatever it likes.

Reconstruction therefore forks on the declared type, and the fork is built to fail safe. Text
renders as text only if the bytes really are valid UTF-8; an image goes through the platform's
own sandboxed decoder; anything else, and any decode that fails, falls through to a generic
binary view that shows the size and the declared type and offers an export. Nothing is decoded
twice and nothing is decoded lossily, so the original bytes survive whichever branch runs.

**A wrong or hostile `mimeType` is a rendering risk, never a confidentiality one.** It cannot
reveal a secret to anyone who could not already reconstruct it: by the time the type is read,
*k* holders have already consented and the plaintext is already on the device. The realistic
harm is feeding attacker-chosen bytes to an image decoder, which is why decoding uses the
platform's sandboxed decoder rather than a bundled library, and why every failure lands on the
binary view instead of an error path.

The export writes back exactly the bytes that were split, never a re-encode of them. That
matters for an image: handing back a re-compressed copy under the original type's name would
quietly make the export something other than the secret.

The export is the one genuinely new surface here: it writes reconstructed **plaintext** out of
the app, to a location the user picks. The catalogue backup deliberately never does that — it
carries contacts, levels and share metadata, no shares and no keys — so the two are not
comparable, and the export is offered per reconstruction, at the user's request, rather than
being anything the app does on its own.

## How large a secret may be

A secret may be at most **256 KiB**, typed text and picked images alike. The limit lives in the
domain rather than in an input form, so no entry point can slip past it — a re-split during a
repair least of all.

It is this modest because Shamir shares are byte-wise. An *S*-byte secret becomes *n* shares of
*S* bytes each; every one is sealed, base64-encoded into its own request, and held by the relay
until its holder picks it up, while the sender retains a copy of all *n* until every pickup is
confirmed. A secret therefore costs several times its own size, several times over. Splitting
and reconstruction are linear in *S* too, and `combineWithIntegrity` is linear in *S* for every
hypothesis it tries, so payload size buys latency at reconstruction as well as bytes at rest.

**An image is split verbatim or refused — never shrunk to fit.** Re-encoding to make something
fit would mean the secret is not the file the user chose, and a secret nobody can predict the
bytes of is a poor secret to hand back later. The cost is that most camera photos are several
megabytes and will be refused; what fits is a screenshot, a cropped photo of a paper backup, a
saved QR image. The refusal says so, with the actual size.

The accepted formats are **PNG and JPEG**, and nothing else. Each additional format is more
decoder surface reached by attacker-chosen bytes for no use case anyone has asked for; SVG is
scriptable and will not be added.

Splitting an image verbatim also means **its metadata rides along** — EXIF, and with it any GPS
coordinates the camera recorded. Deposplit does not strip it, because stripping it would be a
re-encode. Nobody gains from this who could not already reconstruct the secret: holders see only
shares, and the relay only ciphertext. It matters at the *export*, where the file leaves the app
carrying whatever it arrived with.

## Crypto agility

Ed25519 and X25519 are not post-quantum safe, and a system holding long-lived secrets is
exactly the shape exposed to harvest-now-decrypt-later. The goal is not a pluggable-cipher
marketplace — nobody wants to choose algorithms per contact — but making a future
**fleet-wide** algorithm change additive rather than a flag day.

**`CipherSuite`** is carried per contact and names the matched pair of signing and agreement
algorithms currently in force for them. One value exists today,
`"ed25519+x25519-v1"`. It is a wire-tagged string rather than an enum ordinal, because three
independently hand-ported enums cannot be trusted to agree on ordinals. Signing and
agreement are bundled rather than tagged separately: both keypairs are generated together
and rotate together, so splitting them would only create invalid combinations to reject.

**`TransportSuite`** is a separate, lighter, per-message tag — one byte, `1` for
X25519+HKDF-SHA-256+ChaCha20-Poly1305 — prefixed to every ciphertext. Only the KDF and AEAD
need an in-band tag: the agreement algorithm is already known from the recipient's
`CipherSuite` before encryption can even begin. Because the ciphertext is already covered by
the payload signature, the tag rides inside that signature for free — no extra column, no
extra signed field. A device that reads a tag it does not recognise fails with a clear typed
error rather than misparsing.

**Key lengths are not fixed.** A future signing algorithm or KEM will not share Ed25519's
sizes. The relay, which sees bare key bytes with no suite context at most call sites,
sanity-checks a generous 1–128 byte range; clients, which always have the resolved
`CipherSuite` in hand, validate against its declared lengths. Rotation notices are the one
place the relay does have a suite tag alongside a key, and there it validates properly.

`PublicKey.verify()` dispatches on algorithm — today a single branch, deliberately shaped as
an extension point rather than a speculative registry.

Rotating a contact's cipher suite counts as a key change even when the key bytes are
unchanged: it triggers the same verification-level downgrade described in
[trust-model.md](trust-model.md), because an algorithm change is still continuity of key
control rather than a fresh check that you are talking to the right person.

## Honest limits

- **A stolen key is not a stolen secret.** An attacker with both private keys must still
  get *k* holders to approve a retrieval, and the consent model asks each of them to verify
  out of band. The attacker's job is to defeat *k* humans, not one keypair.
- **Revocation limits future damage only.** If an attacker already defeated *k* holders and
  reconstructed during the exposure window, that secret is **burned**. Deposplit cannot
  un-leak it. The remaining remedy is to change the underlying secret itself — rotate the
  recovery key, move the funds — and Deposplit says so plainly rather than implying it can
  undo the disclosure.
- **Client exclusivity is impossible, and not relied upon.** Hardcoded secrets are
  extractable, certificate pinning proves the connection is unintercepted rather than which
  software runs, and attestation is bypassable on rooted devices while making Google and
  Apple gatekeepers. Since the server is blind, a rogue client can only act within the
  bounds of the keypair it controls — it cannot read others' shares or impersonate anyone.
  The realistic abuse is spam and resource exhaustion, addressed by rate limiting like any
  public API.
- **Verification levels are claims, not proofs.** See [trust-model.md](trust-model.md).
