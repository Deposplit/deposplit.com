# Terms of Service

As of {TODO: date of publication}

These terms govern the use of Deposplit: the apps for Android and iOS, and the relay that Squeng AG
operates at api.deposplit.com. The provider is Squeng AG, at the address given in the
[imprint](imprint). How personal data is handled is set out in the [privacy policy](pp).

## What Deposplit does

Deposplit splits a secret on the phone into *n* shares and gives one each to *n* chosen contacts,
the holders. Any *k* of those shares together give the secret back, and fewer reveal nothing about
it. The shares travel through a relay, end-to-end encrypted, and the relay cannot read them.

## What Deposplit cannot do

**Squeng AG has no copy of any secret and cannot recover one.** A secret can be reconstructed only
from *k* shares. If fewer than *k* holders still have their share, for instance because phones were
lost, the app was deleted or holders declined to send their share back, the secret is lost. Nobody
can bring it back, Squeng AG included. The app warns when the margin gets thin, but acting on the
warning is up to the user. A secret whose loss would be serious deserves more holders than strictly
needed, and a second safeguard outside Deposplit.

**The relay is a mailbox, not a vault.** It keeps an entry only until it is delivered, and it may
delete any entry at any time. The apps are built for that, and nothing should rely on the relay
keeping anything.

**Holders are chosen by the user, and so is the risk.** Any *k* holders acting together can
reconstruct a secret without its owner. Who holds a share, and how many must agree, is the user's
decision and the user's responsibility.

## Free and Premium

Deposplit is free for up to three secrets shared at a time. Discarding one frees its slot straight
away, and holding shares for other people is not limited.

**Premium** is a one-time in-app purchase. It unlocks sharing more than three secrets, and naming a
relay of one's own as the phone's default. Reaching a contact who uses their own relay is free.

Premium is bought through Apple's App Store or Google Play, which handle payment and refunds under
their own terms. There is no Deposplit account: Premium belongs to the store account it was bought
with, and can be restored on another phone signed in to that account. Squeng AG has no record of
who bought it.

## Using the relay

The relay is for Deposplit traffic only. Storing data unrelated to Deposplit, overloading or
attacking the relay, circumventing its limits, or using it for unlawful purposes is not allowed.
Squeng AG may refuse requests that do any of these.

A secret's label is stored on the relay as written, without encryption. It should not reveal what
the secret is or whom it concerns.

**Self-hosted relays:** a contact can be reached through a relay that somebody else operates. For
that relay, its operator is responsible, not Squeng AG.

## Availability and changes

Squeng AG operates the relay with care, but without any guaranteed availability. Maintenance and
outages happen, and the apps are built to cope with them.

Squeng AG may change the apps and the relay. If it discontinues its relay, it announces this on
this website at least {TODO: notice period} in advance.

## Source code

The source code of the apps and the relay is published on GitHub under the MIT license. These terms
do not restrict any right under that license. They govern the service that Squeng AG operates and
the purchase of Premium. For apps installed from a store, that store's terms apply as well.

## Liability

Squeng AG is liable for damage it causes intentionally or through gross negligence. To the extent
the law permits, any further liability is excluded, in particular for the loss of a secret, a share
or access to them, and for indirect or consequential damage. Mandatory statutory liability, such as
for product defects, remains unaffected.

## Changes to these terms

Squeng AG may change these terms with effect for the future. The version in force is always the one
here, and every change can be traced in the public
[repository on GitHub](https://github.com/Deposplit/deposplit.com). A change does not take away
anything already bought.

## Law and jurisdiction

These terms are governed by Swiss law, excluding its conflict-of-law rules and the United Nations
Convention on Contracts for the International Sale of Goods. The place of jurisdiction is
{TODO: seat of Squeng AG}. Consumers keep the protection of the mandatory law of the country where
they live, and may also sue there.
