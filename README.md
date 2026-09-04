# Deposplit

**Maintaining a secret is a chicken-and-egg problem.** To protect the password manager's
master password, the recovery codes, the wallet seed phrase or the disk encryption key, you
need somewhere safe to put it — and that somewhere needs protecting too.

Deposplit breaks the loop with **Shamir's Secret Sharing**. Your secret is split into *n*
shares and given to *n* people you choose. Any *k* of them can put it back together. Fewer
than *k* learn **nothing** — not "almost nothing": below the threshold the shares carry no
information about the secret at all. And up to *n − k* of them can lose their share without
costing you anything.

You are not trusting any of those people with your secret. You are trusting that fewer than
*k* of them will conspire.

This repository is the project hub: the relay service, the landing page, and the
documentation covering all three repositories.

## Status

**Pre-launch.** The protocol and both apps are built and tested; nothing is published to an
app store, and no production relay is serving real users yet. There are consequently no
schema migrations — the evolutions script is edited in place, and test relays and devices
are reset to a clean slate.

## Documentation

Written for two audiences: developers working on Deposplit, and Claude Code working
alongside them. Non-developers should read the landing page instead.

| Document | What is in it |
|---|---|
| [docs/architecture.md](docs/architecture.md) | C4 views, Ports & Adapters, and the roads not taken |
| [docs/protocol.md](docs/protocol.md) | The relay wire protocol, signatures, and delivery model |
| [docs/security.md](docs/security.md) | Threat model, cryptographic constructions, crypto agility |
| [docs/trust-model.md](docs/trust-model.md) | Verification, rotation, revocation, recovery, custody monitoring |
| [docs/privacy.md](docs/privacy.md) | What the project itself may observe: logging, metrics, crash reports, libraries |
| [docs/testing.md](docs/testing.md) | Manual end-to-end flows across three devices |
| [TODO.md](TODO.md) | Open work |
| [SECURITY.md](SECURITY.md) | Reporting a vulnerability |

Start with `architecture.md`. It is the one that makes the others make sense.

## Repositories

Three independent repositories under the [Deposplit organisation](https://github.com/Deposplit),
cloned side by side into a `Deposplit/` workspace that is itself not a git repository.

| Folder | Repository | Contents |
|---|---|---|
| `deposplit.com/` | [deposplit.com](https://github.com/Deposplit/deposplit.com) | Relay, landing page, cross-project documentation |
| `Android/` | [Android](https://github.com/Deposplit/Android) | Kotlin SSS library, hexagon, and Android app |
| `iOS/` | [iOS](https://github.com/Deposplit/iOS) | Swift SSS library, hexagon, and iOS app |

## How it works, briefly

- **Your identity is a keypair, not an account.** The apps generate an Ed25519 pair for
  authentication and an X25519 pair for encryption at first launch. There is no
  registration, no e-mail, no password.
- **Contacts are exchanged out of band.** Ideally by scanning a QR code in person. The
  relay has no directory and no invitation flow, so it never learns who anybody is or who
  knows whom.
- **The relay is cryptographically blind.** It stores and forwards opaque bytes between
  public keys. A full breach of it yields nothing useful.
- **Consent is the real protection.** Retrieving a share needs its holder's approval, so
  even someone holding your keys must defeat *k* people who can pick up the phone and check.

## Tech stack

| Concern | Choice |
|---|---|
| Language and framework | Scala 3.3 + Play 3, built with sbt |
| Architecture | Ports & Adapters, enforced by sbt subprojects |
| Database | PostgreSQL in production, H2 in development and test |
| Database access | Anorm — SQL-first, minimal abstraction, sits cleanly in the adapter layer |
| Schema | Play Evolutions, `conf/evolutions/default/1.sql` |
| API specification | OpenAPI 3.0, `conf/openapi.yaml` |
| Landing page | Twirl + HTMX + Bootstrap, copy in `public/markdowns/` (English and German) |
| Cryptography | BouncyCastle, for Ed25519 signature verification only |

The relay never decrypts anything, so its only cryptographic dependency is signature
verification. See [docs/security.md](docs/security.md) for why there is no libsodium
anywhere, and [docs/architecture.md](docs/architecture.md) for why PostgreSQL rather than a
document store.

## Build and test

```bash
sbt run                                     # dev server, auto-reloads on change
sbt run -Dconfig.file=conf/localhost.conf   # dev server against the local H2 database
sbt test                                    # all tests (276)
sbt relay/test                              # the relay hexagon only (95)
sbt compile
sbt dist                                    # production distribution zip
```

Tests need no external services: `conf/test.conf` runs against in-memory H2.

For the apps, see each repository's own README. In short: `./gradlew test` in `Android/`
(115 in `:hexagon`, 20 in `:app`) and `swift test` in `iOS/hexagon/` (110).

## Continuous integration

Each repository has a `test.yml` workflow running its own suite on every push and on pull
requests targeting `main`, plus weekly Dependabot updates. Third-party actions are pinned
to commit SHAs. No job needs a device, emulator or simulator — the iOS workflow builds and
tests the `hexagon` package only, since the app target needs a simulator.

## Licence

MIT. Copyright © 2026 [Squeng AG](https://www.squeng.com).
