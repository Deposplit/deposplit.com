# Privacy Policy

As of November 1, 2026

This policy covers the website deposplit.com and the Deposplit apps for Android and iOS. It says
what personal data Squeng AG processes, why, and for how long. It follows the Swiss Federal Act on
Data Protection (FADP) and, for people in the EU and the EEA, the General Data Protection Regulation
(GDPR).

## In short

- No analytics, no tracking, no advertising, and no third-party services, on the website or in the
  apps.
- Secrets are split and encrypted on the phone. The relay passes on encrypted shares that it cannot
  read.
- There is no account: no name, no email address, no phone number. The relay does not know who is
  behind a key.

## Controller

Squeng AG, at the address given in the [imprint](imprint).
Email: deposplit@squeng.com

## The website

### Visiting the site

When a page is opened, the browser sends its IP address and the usual request headers, such as the
browser type and the preferred language, to the server. They are needed to deliver the page and are
used for nothing else. Deposplit does not write them down: there is no access log, no analytics and
no tracking pixel. If a request fails with a server error, the error log records which address on
the site was requested, but not who requested it.

The servers are operated by Clever Cloud SAS, France. The provider keeps access logs of its own,
which record for each request, among other things, the IP address, the time and the address
requested. Squeng AG can see them for seven days, and the provider keeps them for up to a year to
meet its legal obligations in France.

Legal basis under the GDPR: Art. 6(1)(f), the legitimate interest in delivering the website and
keeping it secure.

### Cookies and local storage

The site sets no tracking cookies. It stores two things in the browser, each only after being asked
to:

- **`PLAY_LANG`**, a cookie holding the language picked from the language menu (`en` or `de`). It is
  deleted when the browser is closed.
- **`bsTheme`**, an entry in the browser's local storage holding the theme picked with the theme
  switch (`light` or `dark`). It stays until it is deleted.

Without the language menu or the theme switch, nothing is stored at all. Both can be deleted at any
time in the browser's settings, after which the site follows the browser's language and colour
preferences again. Storing a setting that the visitor asked for needs no consent, so there is no
cookie banner.

Fonts, icons and scripts all come from deposplit.com itself, so the browser contacts no other party.
The links to GitHub and to squeng.com lead to other websites, whose own privacy policies apply.

## The apps

### On the phone

The shares held for others, the contacts and the names given to them, and the labels of one's own
secrets are stored in the app's private storage on the phone. The secret itself is not kept once it
has been split. Squeng AG has no access to any of it. The pseudonym on a contact card goes directly
from phone to phone, by QR code or typed by hand, and never reaches the relay.

Like other app data, all of this is included in the phone's backup and in a transfer to a new
phone, if they are turned on, so that a phone switch loses nothing. Android encrypts its backup
with the phone's screen lock. Apple encrypts iCloud Backup with keys that Apple holds, unless
Advanced Data Protection is turned on. The private keys never leave the phone, not even in a
backup.

- **Camera:** used only to scan a contact's QR code. The image is read on the device and is neither
  stored nor sent.
- **Face ID and fingerprint:** checked by the operating system. The app learns only whether the
  check succeeded.
- **Notifications:** created by the app on the device itself. No push service is used, so Apple and
  Google receive no notification token from Deposplit.

The apps contain no analytics, crash-reporting or advertising libraries, and they send nothing to
Squeng AG beyond what the relay needs, as described below.

### What the relay processes

The apps pass shares between phones through a relay, by default the one Squeng AG operates at
api.deposplit.com. With each request the relay receives:

- the IP address of the phone, which is needed to answer and which Deposplit does not log (see
  *Visiting the site* above for the hosting provider)
- the phone's public key and a signature, with which the relay checks that a request comes from the
  holder of the matching private key, without an account
- for a share: the public keys of sender and recipient, an identifier of the secret, its label
  encrypted for the recipient, its type (such as text or image), how many shares exist and how
  many are needed to reconstruct it, timestamps, signatures, and for deposits and returns the
  encrypted share itself
- signed notices that the apps exchange to keep each other up to date, such as that a key has been
  replaced by a new one, or that a holder still keeps a share

The share and its label are end-to-end encrypted for the recipient's key, and the relay can read
neither. What it can read is the type of the secret and how many shares there are.

A public key is a random number generated on the phone. It contains no name, and Squeng AG keeps no
record of which person a key belongs to. The contacts with whom keys were exchanged do of course
know them.

**How long:** entries on the relay exist only to be delivered. An encrypted share is deleted from
the relay as soon as its holder has picked it up, and a share returned to its owner as soon as the
owner clears the collected copies in the app. Every other entry is deleted by the app it is meant
for once that app has acted on it. Whatever is still there after 28 days without a change, for
example because a phone was lost or a contact was deleted, is deleted automatically. A notice that
a key has been replaced waits 180 days instead, because it cannot be sent a second time.

Legal basis under the GDPR: Art. 6(1)(b), because the processing is necessary to provide the service.

**Self-hosted relays:** a contact can be reached through a relay other than Squeng AG's. Data for
that contact then goes to that relay's operator, and that operator's privacy policy applies.

### App stores and purchases

The apps are distributed through Apple's App Store and Google Play, and in-app purchases are
processed by Apple and Google under their own privacy policies. Squeng AG does not learn who bought
what: the unlock is recorded on the device. Apple and Google provide Squeng AG with aggregate
statistics, such as downloads, countries, and crash reports from people who allowed them in their
phone's settings. These do not identify anybody.

## Disclosure and transfers abroad

Personal data is neither sold nor shared for advertising. For the website, the apps and the relay,
the only party processing it on behalf of Squeng AG is the hosting provider Clever Cloud SAS, with
data stored in France.

Nobody is profiled, and no decision is made automatically about anybody.

## Rights

The FADP and the GDPR grant a right of access to one's personal data, and the right to have it
corrected or deleted. The GDPR adds the rights to restrict processing, to object to it and to data
portability. Requests go to deposplit@squeng.com.

Because the relay does not know who is behind a key, a request about entries on the relay has to
name the *verification key* concerned, one of the two keys the app shows for sharing a contact.

Complaints can be lodged with the Swiss Federal Data Protection and Information Commissioner (FDPIC,
[edoeb.admin.ch](https://www.edoeb.admin.ch)) or, in the EU and the EEA, with the supervisory
authority of one's country.

## Changes

This policy changes when what is processed changes, and the current version is always the one here.
Every change can be traced in the public
[repository on GitHub](https://github.com/Deposplit/deposplit.com).
