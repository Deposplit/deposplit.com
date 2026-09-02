# CLAUDE.md — deposplit.com

Guidance for Claude Code working in this repository. This file is **operating
instructions**, not design rationale — the rationale lives in `docs/`, and this file's job
is to tell you which of those to read before touching what.

Deposplit splits a secret into *n* shares and reconstructs it from any *k*. This repository
holds the relay service, the landing page, and the documentation for all three repositories
(`deposplit.com/`, `Android/`, `iOS/`).

## Read before you change

| If you are touching | Read first |
|---|---|
| Anything at all, first time in this repo | [docs/architecture.md](docs/architecture.md) |
| An endpoint, a table, a request type, `openapi.yaml` | [docs/protocol.md](docs/protocol.md) |
| Keys, signatures, encryption, cipher suites | [docs/security.md](docs/security.md) |
| Verification, rotation, revocation, recovery, heartbeats | [docs/trust-model.md](docs/trust-model.md) |
| Anything you want to verify by hand on real devices | [docs/testing.md](docs/testing.md) |

Prefer these docs over anything you remember. They are the source of truth for how
Deposplit works. If a memory from an earlier session contradicts them, the docs are right
and the memory is stale — verify it against the repo before acting on it, and prune it.

Do not add design rationale to this file. If a decision needs explaining, it belongs in the
relevant `docs/` page, in present tense, where the next reader will actually look for it.

## Rules that are easy to break by accident

**The hexagon boundary is real.** `hexagons/relay` and `hexagons/phon` must never import
Play or any infrastructure library. The root project depends on them; they never depend on
the root, nor on each other. If you find yourself wanting Play inside a hexagon, the logic
belongs in an adapter instead.

**Everything is synchronous.** No `Future`s, no reactive plumbing, in the hexagons or the
Play app. Stack traces stay readable and virtual threads make blocking cheap.

**`PayloadCanonical` is append-only.** New fields go at the **tail** of a signed byte
sequence, never inserted. Inserting one silently breaks interoperability with every already
released client and invalidates the cross-platform vector tests. Any change here must land
on **all four** implementations — relay, phon, Android, iOS — together, with the fixed-seed
vector tests updated in lockstep.

**Pre-launch means no migrations.** Edit `conf/evolutions/default/1.sql` **in place**; do
not add `2.sql`. Test relays and devices are reset to a clean slate instead.

**H2 runs the dev and test databases, so the evolutions script must stay portable:**
- `TIMESTAMP WITH TIME ZONE`, not `TIMESTAMPTZ`
- `DEFAULT <expr>` comes *before* `PRIMARY KEY` in a column definition
- semicolons inside `--` comments still split statements, so they are safe only
  *between* statements, never inside one
- **no partial indexes.** The one-pending-request-per-type constraint is therefore enforced
  in `ShareRequestsService`, not by the schema, and must be added manually to production
  PostgreSQL. There is a comment in `1.sql` saying so.

**The relay must stay blind.** It may verify signatures and route opaque bytes. It must
never decrypt, never perform key agreement, and never gain a table that maps keys to people.
A change that would let the relay answer "who is this?" is a change to the product, not an
implementation detail.

## Cross-platform parity

The relay, Android, iOS and phon share one design vocabulary: same port names, same service
names, same value objects. A change to shared concepts should land everywhere.

**phon is the exception, and is deliberately second-class.** It is a browser-based phone
emulator for teaching and manual testing — not a product surface. Changes are ported to it
for *consistency*, not full *parity*: it routinely skips UI affordances the mobile apps have
(no rename action, no health badges, no conflict list) because its minimal HTMX views have
no plumbing for them. Skipping UI in phon is normal and needs no justification; skipping
domain logic does.

## Build and test

```bash
sbt run                                     # dev server, auto-reloads
sbt run -Dconfig.file=conf/localhost.conf   # dev server against local H2 (needed for phon)
sbt test                                    # all tests (308: relay 104, phon 127, root 77)
sbt relay/test                              # relay hexagon only
sbt compile
sbt dist
sbt scalafmtAll scalafmtSbt                 # reformat; CI fails the build if anything is unformatted
```

Tests run against in-memory H2 via `conf/test.conf` — no external services needed.

The sibling repositories, for reference:

```bash
# from Android/
./gradlew test                    # JVM unit tests, no device needed (136 :hexagon, 20 :app)
./gradlew :hexagon:test           # hexagon only
./gradlew assembleDebug

# from iOS/hexagon/
swift test                        # 131 tests
swift build
```

> Swift on Windows writes to the Windows Console API, so its output is not captured by Git
> Bash. Run `swift test` from PowerShell, Windows Terminal or VS Code instead — and check
> whether a Swift toolchain is installed at all before assuming iOS changes can be verified
> here. When it is unavailable, iOS changes can still be written correctly by mirroring the
> verified Kotlin or Scala implementation, but must be **flagged as unverified** and handed
> off for a real `swift build` / `swift test` on a Mac.

## Layout

| Path | What |
|---|---|
| `hexagons/relay/` | The domain: value objects, driving ports, services, driven ports. No Play. |
| `hexagons/phon/` | The phone emulator's own hexagon, mirroring the mobile apps'. No Play. |
| `app/controllers/api/` | REST controllers and `AuthHelper` |
| `app/controllers/gui/` | Landing page and Markdown rendering |
| `app/driven_adapters/persistence/` | Anorm repositories — the only code that knows SQL |
| `app/views/` | Twirl templates |
| `conf/` | `routes` (production) and `dev.routes` (adds phon), `openapi.yaml`, `evolutions/`, `messages` + `messages.de` |
| `public/markdowns/` | Landing page copy, English and German |

Landing page copy is Markdown loaded over HTMX, not inline in the Twirl templates. Both
locales must be kept in sync.

## House style

- Match the surrounding code: this codebase uses explicit types at public boundaries and
  keeps value objects small and total.
- Scala formatting is `scalafmt` (`.scalafmt.conf`), enforced by CI ahead of the tests. Run
  `sbt scalafmtAll scalafmtSbt` before pushing rather than formatting by hand — and never mix
  a reformat into a behavioural commit, which buries the change.
- Line endings are CRLF on Windows; `core.autocrlf` handles the conversion, so do not
  hand-convert files.
- Do not reference work items by number in code comments or documentation. Say what the
  code does and why, so the comment survives the tracker.
