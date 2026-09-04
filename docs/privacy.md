# Privacy

The other documents here ask what an attacker can take. This one asks what **Deposplit** takes.

It covers everything that *observes* the running system — logs, metrics, traces, crash reports,
analytics, and the libraries that would carry any of them — and there the answer is meant to stay
"nothing". It is not a claim that nothing leaves a device: the relay receives public keys,
ciphertext, *k*, *n*, a declared type and a plaintext label, because routing needs them, and
[protocol.md](protocol.md) says exactly what. These rules are about what would be taken **in
addition**, for the project's benefit rather than the user's.

They are written down before any tool is chosen, because this is the one area where the sensible
defaults of ordinary, respectable software quietly undo the rest of the design.

## The rules

**1. No profiling.** Nothing may build a picture of a person. No per-install identifier minted
for observation, no in-app cohorting, no funnels, no session stitching, no "anonymous" id whose
job is joining one visit to the next. The relay's schema is deliberately about rows rather than
people; nothing standing beside it may become the table it refuses to be.

**2. No watching what a user does in the app.** No screen views, no feature-usage events, no
interaction timings, no tally of how many contacts somebody keeps or how often they reconstruct.
What happens on the device is the user's business, and the app's job is not to know. What the
relay may compute from rows it already holds is a different question, settled below.

**3. No phoning home without explicit, per-occurrence consent.** Each app talks to the relay its
user chose, and to nothing else. Anything that would open a second connection — a crash upload, a
diagnostic bundle, a version check, a remote configuration fetch — happens only because the user
asked for it that time, having seen what would be sent. A default-on setting is not consent, a
pre-ticked box is not consent, and silence is never consent.

**4. No library that does any of the above.** This binds the dependency, not its configuration.
A crash reporter that uploads unless told otherwise, an SDK that registers a background uploader
on initialisation, an analytics library that only reports in release builds — all out, including
one arriving transitively inside something else. A library that phones home when misconfigured is
a library that phones home.

Two clarifications, because rule 1 has edges a careful reader will find. First, the identity
keypair *is* a stable cross-visit identifier, and it is the product rather than a loophole: it is
how addressing works without accounts, it is minted on the device, and it names nobody. The rule
forbids minting a **second** one. Second, the rule bans the *mechanism*, not the number — install
cohorts, retention curves and install-to-uninstall funnels arrive from Play Console and App Store
Connect anyway, aggregated and with no SDK in the app. Building the same thing in-app would cost a
stable id, timestamped events leaving the device and a processor agreement, to learn what the
stores already report.

**The test to put to a change:** could the artefact it produces — a log line, a metric, a report,
a request to anybody — distinguish one user from another, or the same user from themselves last
week? If it could, it does not ship. An aggregate that survives that question is fine; one that
becomes identifying when joined to another is not, and joining is what observability stacks are
for.

Not collecting is also the cheapest position to be in. Data that was never collected needs no
lawful basis, no processor agreement, no retention schedule, and no procedure for answering a
deletion request that arrives four years from now.

## Observability is a second database

Every API request carries `X-Deposplit-Verify-Key`: the caller's public verify key, in a header,
on every call, by design — it is how the relay authenticates without accounts, and
[protocol.md](protocol.md) describes it. The relay's three tables hold rows and none of them is
about people.

An access log of the kind every reverse proxy writes by default records the client's IP address.
Putting that address on the same line as that header is precisely the linkage the schema is built
to avoid: it lets the operator answer *who is this?*, the one capability the relay is not allowed
to gain. No schema change is needed for that to happen. A configuration default is enough, in
software nobody thinks of as part of the product.

Retention makes it worse rather than better. The relay's rows are deliberately short-lived — a
deposit's ciphertext is cleared the moment its holder picks it up, a rotation is deleted once
consumed, and any row may be collected without consequence, because
[absence is never a signal](protocol.md). Logs are the opposite: appended, rotated, archived,
shipped elsewhere. An access log outlives the data it describes, inverting the property the
protocol works to hold.

So **the relay must stay blind** governs its logs, its metrics and its traces, not only its
tables.

## Where the project stands today

There is nothing to undo, which is the whole reason to write the rules now:

| | |
|---|---|
| Relay | No metrics, no tracing, no access log, no rate limiter. The only `logger.` calls in the repository belong to `phon`, a development tool that is not routed in production. The root logger is at `WARN`, with `play` at `INFO` and `application` at `DEBUG`, and there is no custom `ErrorHandler` — so Play's default one logs an unhandled server error together with the request method and URI. No headers and no body: quiet, but not silent. |
| Android | Three permissions — `INTERNET`, `CAMERA`, `USE_BIOMETRIC`. Dependencies are AndroidX, Compose, kotlinx-serialization, CameraX, ZXing and BouncyCastle. No Firebase, no Crashlytics, no Play Services analytics. |
| iOS | One package reference, the local `hexagon`. No remote packages at all. One usage-description string, for the camera. |
| Landing page | One cookie, `PLAY_LANG`, two letters, written only when a visitor picks a language. No third-party request. |

## What the rules already settle

- **Analytics in either app: never.** This is a category exclusion, not a tool comparison, and it
  does not need revisiting when a nicer SDK appears.
- **A crash report never leaves a device on its own.** The permitted shape is the one
  reconstruction already uses: the artefact is produced locally, shown to the user, and exported
  once, to a destination they pick, because they asked. No background upload, and no breadcrumb
  trail collected in advance against the possibility of a crash — collecting it is the surveillance,
  whether or not it is ever sent.
- **Diagnostics are local by the same rule.** A user who wants to help debug something exports
  what they can read first.
- **The relay may count what it holds, in snapshots, and keep only the aggregate.** Numbers about
  the machine are unarguable: requests per endpoint, error rates by code, latency histograms,
  connection-pool saturation. So is a point-in-time query over rows the relay already stores — the
  distribution of *k* and *n*, holders per owner, secrets per sender key, the mime mix,
  deposit-to-approval latency. Reading a table that already exists is not collection, and grouping
  by `sender_key` to emit a histogram profiles nobody, once the histogram is what is kept.

  The line is **snapshot versus time series.** Retaining per-key derived facts across time is out,
  even though each snapshot was allowed on its own: the rows are deliberately short-lived, so a
  history of them outlives what it describes and reconstructs a key's biography — the linkage
  table with extra steps.

  And nothing may be joined to a caller's address or to a live request's
  `X-Deposplit-Verify-Key`, **nor to any hash, truncation or other encoding of either**. A hash
  preserves equality, which is the whole of what a join needs and the whole of what this rule
  forbids: it defeats disclosure, not linkage. An IPv4 address hashes into a space small enough to
  tabulate exhaustively, and a public key needs no inverting at all, since everyone who matters —
  every contact, and the relay on every request — already holds the value to hash.
- **An error log may name the failure, never the parties or the payload.** Codes, types and shapes
  only — never a public key, never a `label`, never a request body. `label` in particular is
  user-authored text the relay stores in the clear and routes without interpreting; a log line
  that interpolates it turns a routed field into a retained one, in a file with a different
  lifetime and different backups. The endpoint is not the payload: Play's default handler already
  logs a failed request's method and URI, which names where the failure happened and, for a
  path-addressed row, which row. That is the ceiling, not a precedent to build on.
- **Push notifications are out, on a stronger objection than rule 4.** FCM and APNs deliver to a
  per-install token, so the relay would have to keep a durable mapping from that token to a public
  verify key — the very table it is not allowed to have, and in a purer form than any access log,
  since Google and Apple can resolve the token end of it to an account belonging to a named person.
  Every straightforward construction has that shape, including a content-free "something changed,
  go poll", and on iOS there is no alternative transport at all. Delivery is polling-only for the
  reasons [protocol.md](protocol.md) gives on its own terms; this is the further one.

  That is a test rather than an oath. Any future proposal has to answer *does the relay end up
  holding a token beside a key*, not *would push be convenient*. And it is worth being clear about
  what push would actually buy, which is **promptness**: both apps poll only while open, so a
  holder who does not launch the app does not learn that somebody needs their share. That gap is
  real and it is answerable without a third party, by scheduled background refresh. Until a
  construction turns up that the relay cannot join, the answer to it is polling more often.
- **Rate limiting is where this bends, and only so far.** [SECURITY.md](../SECURITY.md) names rate
  limiting as the answer to spam and resource exhaustion, and a rate limiter counts per caller by
  definition. Three properties keep it honest, and they are the ones doing the work: the counter
  lives **in memory**, **expires with its window**, and is **never written to disk, never logged,
  never joined to anything**. Hashing the address or key before using it as a bucket is worth
  doing — it keeps the raw value out of a heap dump or a debug endpoint — but that is hygiene, not
  anonymisation, and it must not be described as more: it is those three properties, never the
  hash, that let this bend at all. The one hashing worth more would be a **keyed** one whose key
  rotates with the window and is then destroyed, so buckets cannot be matched from one window to
  the next — which in a limiter whose counters already die with the window adds little. A rate
  limiter that persists its state is a visitor log with extra steps.
- **The store listings stay honest, which is not the same as blank.** Play's Data Safety form and
  Apple's privacy label ask about everything that leaves the device, not only about analytics —
  and public keys, ciphertext, *k*, *n*, `mime_type` and the plaintext `label` all reach the
  relay, over a connection with an address at the other end. The truthful declaration is therefore
  *collected, **not linked** to an identity, not shared, not used for tracking*, with nothing at
  all under analytics, diagnostics or advertising. "No data collected" would be a binding
  declaration that is false; the claim worth defending, and the one that actually distinguishes
  this product, is **no analytics, no diagnostics, no third-party sharing, no tracking**. Any
  change that would add a line to either form is a change to the product rather than an
  implementation detail, and the exact wording is a pre-launch task done with the forms open.

## The landing page is a different context

A visitor to the website is not a user. There is no keypair, no relay row and no secret, so the
harm the rules above exist to prevent — the operator answering *who is this?* about somebody whose
shares the system routes — does not arise there at all. Those rules govern the apps and the relay.

The site holds to the same standard regardless, for a positional rather than a moral reason: the
product's whole claim is that it cannot see anything, and an analytics tag on the marketing page
is a free gift to the first person who views source.

One structural fact makes a relaxed answer safe here. `ApiHostPathFilter` keeps the REST API on
`api.` and everything else on `www.`, so a caller's public verify key never reaches the host that
would do any counting. Site analytics cannot be joined to a Deposplit identity, because the
identity is not there to join to.

**Counting your own requests is counting, not tracking.** The web app may keep aggregate counters
— views per path, locale split, referrers by source, status codes — with no cookie, no script, no
identifier and no address retained. The HTMX design makes that unusually informative for free:
`/problem`, `/solution`, `/theory` and `/practice` are fetched as separate requests by the
carousel, and `/name`, `/origin` and `/prices` are pages in their own right, so a per-path counter
reports which sections were actually pulled rather than merely which page was opened.

**The honest limitation is bots**, and it cuts against the tidy answer. Raw hits at this stage are
mostly crawlers, so an unfiltered "interest" number is noise — and a script beacon is the better
instrument precisely because it needs a browser to execute it, which filters crawlers for free.
The more invasive mechanism produces the more accurate number, for reasons that have nothing to do
with tracking.

**If counters stop being enough, the escalation is self-hosted and cookieless** — Plausible CE,
Umami, GoatCounter — served from this domain, so the page still makes no third-party request. The
way such tools count a unique visitor without a cookie is a daily-rotating salted hash of address
and user agent whose salt is destroyed at midnight: the construction named above as the one
hashing worth more than hygiene, so reaching for it is consistent rather than special pleading.
Two things stay true and should be stated rather than inherited from the vendors' marketing — it
does process an address, however briefly, and "no consent banner required" is a widely relied-upon
position rather than a settled one.

**A third-party tag is out**, on the same category grounds as rule 4. So is any convenience that
makes a visitor's browser talk to somebody else, which is the current state of the Bootstrap Icons
stylesheet: the one outward request on the site, and the only party learning that anybody visited.
Closing it is worth more than any counter added afterwards.

**Whether a visitor became an installer** is answered store-side rather than on the site. UTM
parameters on the store links produce Play Console install-referrer and App Store campaign
reports, aggregated per campaign — the same category as the store aggregates under *Honest
limits*, and nothing is added to the page to get them.

Anything added here makes the privacy policy behind `/legalese/pp` non-optional.

## What is still open

- Whether the relay gets aggregate metrics at all, and through what. The rules permit them; the
  question is whether a pre-launch service with no users earns the dependency.
- Whether an operational error log exists above `WARN` in production, where it is written, and who
  can read it.
- The rate-limiting mechanism, within the constraint above.
- **The public privacy policy.** This document is the engineering rule and is written for
  developers. The site's navigation already links to `/legalese/pp`, and there is no Markdown
  behind it yet. That policy is a separate, legal artefact addressed to users — it should be
  derivable from this one and must claim nothing this one does not permit.

## Honest limits

- **These rules bind what Deposplit builds, not what it runs on.** Whatever terminates TLS sees
  addresses, and a hosting provider keeps its own logs on its own terms. Someone who cannot accept
  that runs their own relay, which the protocol supports per contact.
- **The claim is auditable, not provable.** All three repositories are public, so "no telemetry"
  is a statement anybody can check by reading the source. Nothing proves a published binary was
  built from that source; reproducible builds are not in scope.
- **The stores learn what stores learn, and hand some of it back.** Google and Apple know who
  downloaded the app and who paid for the unlock; Deposplit learns neither, the entitlement being
  a local preference and honour-system by design. But Play Console and App Store Connect report
  aggregates to the developer — installs and uninstalls, retention, country and OS mix, conversion
  and revenue — and Android Vitals reports crash and ANR clusters with stack traces, as does Apple
  for users who opted in at OS level. **This project will read those numbers.** They are the
  platform's collection rather than the app's, and nothing above pretends they do not exist.
- **Not measuring has a cost, and it is accepted.** The gap is precise rather than total, given
  the store aggregates above and the relay's own snapshots: what is genuinely lost is **in-app,
  step-level drop-off** — whether somebody abandoned at choosing *k* and *n* or at scanning a QR
  code. Nothing outside the app can report that, so it comes from watching five people use it
  instead, which is the older and better instrument anyway. A bare counter
  carrying no id and no session would be the permissible way to buy it back, and it is still not
  worth the connection, the declaration and the credibility while the user count is small enough
  that the numbers would mean nothing. The alternative is a system that learns things about its
  users in order to serve them better, which is the shape of the product Deposplit deliberately is
  not.
