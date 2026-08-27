# Security policy

Deposplit splits secrets among people you choose, so a vulnerability here can cost somebody
their password manager, their wallet, or their disk. Reports are welcome and taken
seriously.

## Reporting a vulnerability

Please report privately, through **GitHub's private vulnerability reporting** on the
affected repository — the *Security* tab, then *Report a vulnerability*:

- [deposplit.com](https://github.com/Deposplit/deposplit.com/security/advisories/new)
- [Android](https://github.com/Deposplit/Android/security/advisories/new)
- [iOS](https://github.com/Deposplit/iOS/security/advisories/new)

Please do not open a public issue for a security problem, and please give us a reasonable
chance to fix it before disclosing publicly.

Useful things to include: which repository and commit, what an attacker gains, and the
smallest reproduction you have. A proof of concept is welcome but not required — a clear
description of the flaw is worth more than a working exploit.

This is a small project run by [Squeng AG](https://www.squeng.com). Expect an acknowledgement
within a few days rather than within hours.

## Status

**Pre-launch.** Nothing is published to an app store and no production relay serves real
users. Reports against `main` in any of the three repositories are in scope; there are no
released versions to designate as supported.

## Scope

Deposplit's security model is documented in [docs/security.md](docs/security.md) and
[docs/trust-model.md](docs/trust-model.md). The design assumes the relay is hostile, so the
findings that matter most are ones that break that assumption.

**Especially interesting:**

- Anything that lets the relay learn plaintext, participate in key agreement, or link keys
  to people.
- Forging or replaying a transport signature, or a payload signature on a share request,
  rotation notice or custody heartbeat.
- Reconstructing a secret from fewer than *k* shares, or bypassing holder consent.
- Accepting a key change without a valid signature from the previous key, or otherwise
  defeating the rotation and revocation rules.
- Extracting private key material from the Android Keystore or the iOS Keychain, or
  bypassing the biometric gate on reconstruction.
- Cross-platform divergence in the canonical byte constructions, which could let one client
  be tricked into signing something another interprets differently.

**Known and accepted, so not findings:**

- *k* colluding holders can reconstruct a secret. That is what *k* means.
- The relay learns that some key deposited something for some other key at some time.
  Metadata at that granularity is unavoidable for a store-and-forward mailbox.
- Client exclusivity is not enforced and cannot be. Hardcoded secrets are extractable and
  attestation is bypassable; the security model does not depend on which software connects,
  because a caller can only ever act within the bounds of the keypair it controls.
- Verification levels are user-asserted claims, not cryptographic proofs.
- Holders store their share as plaintext locally. A single share below the threshold carries
  no information about the secret.
- The freemium entitlement is client-side and honour-system by design.
- `phon`, the phone emulator, is a development tool. Its known exposure gap is tracked in
  [TODO.md](TODO.md); reports about it are welcome but low priority.

## Cryptography

No custom primitives. Ed25519, X25519, HKDF-SHA-256 and ChaCha20-Poly1305 via BouncyCastle
and Swift Crypto. The one hand-written algorithm is Shamir's Secret Sharing over GF(2⁸),
ported from a published reference and covered by cross-platform test vectors — scrutiny
there is particularly welcome.
