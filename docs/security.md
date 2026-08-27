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
| Stolen device, unlocked, keys extracted | Serious — but the attacker still has to defeat *k* holders' out-of-band consent. The keypair is not the last line of defence; the humans are. |
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
2. **Pickup** — on approval the holder decrypts immediately and stores the **plaintext**
   share locally. The ciphertext is discarded and the relay row cleared.
3. **Retrieval** — the holder re-encrypts that plaintext to the requester's *current*
   public key, looked up live.

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
