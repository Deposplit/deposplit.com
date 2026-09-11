/*
 * The MIT License
 *
 * Copyright (c) 2026 Squeng AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package controllers.phon

import value_objects.svo.Contact
import value_objects.svo.CustodyHeartbeatTuning
import value_objects.svo.HeldShare
import value_objects.svo.KeyConflict
import value_objects.svo.Secret
import value_objects.svo.SecretState
import value_objects.svo.ShareMetadata
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType
import value_objects.svo.VerificationLevel

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the templates are handed instead of the driving ports themselves.
  *
  * The mobile apps compute all of this in a view model — `HomeViewModel` on both platforms — and the derivations are
  * subtle enough (n_live is freshness-gated, not a row count) that a Twirl template calling the ports inline could not
  * express them. Same names as the mobile view models on purpose: `SecretGroup`, `HolderStatus`, `SecretHealth`,
  * `FreshnessBucket`. Presentation only, so it belongs here in the adapter and never in the hexagon.
  */

/** The three-bucket custody-freshness model — see `CustodyHeartbeatTuning` for the windows and why they are what they
  * are.
  */
enum FreshnessBucket:
  /** Proof of custody — a heartbeat, a pickup, or a retrieval approval — observed within `lossThreshold`. Counts toward
    * n_live.
    */
  case Confirmed

  /** The holder sent a signed opt-out notice, so silence from them is not evidence of anything. Never an alarm; does
    * not count toward n_live either.
    */
  case Unmonitored

  /** Expected proof of custody has not arrived within `lossThreshold`, or never has. Drops out of n_live, reversibly:
    * the next heartbeat or approval puts it back.
    */
  case SilentOverdue

/** Graduated n_live health alarm. */
enum SecretHealth:
  case Healthy, Caution, Critical, Lost, Destroying

final case class HolderStatus(
    shareId: UUID,
    contactId: UUID,
    recipientName: String,
    /** Shown as a secondary line only when `recipientName` is a nickname, i.e. when there is something to disambiguate.
      */
    recipientSubtitle: Option[String],
    retrievalRequest: Option[ShareRequest],
    removalRequest: Option[ShareRequest],
    lastConfirmedAt: Option[Instant],
    heartbeatOptedOutAt: Option[Instant]
):
  def freshnessBucket(now: Instant): FreshnessBucket =
    if heartbeatOptedOutAt.isDefined then FreshnessBucket.Unmonitored
    else if lastConfirmedAt.exists(at => Duration.between(at, now).compareTo(CustodyHeartbeatTuning.lossThreshold) <= 0)
    then FreshnessBucket.Confirmed
    else FreshnessBucket.SilentOverdue

  /** The early nudge, surfaced while the holder is still comfortably counted, so the owner can act before they drop out
    * of n_live rather than after.
    */
  def isGettingStale(now: Instant): Boolean =
    freshnessBucket(now) == FreshnessBucket.Confirmed &&
      lastConfirmedAt.exists(at =>
        Duration.between(at, now).compareTo(CustodyHeartbeatTuning.staleWarningThreshold) > 0
      )

final case class SecretGroup(secret: Secret, holders: List[HolderStatus]):
  /** n_live is the freshness-gated count, never the number of `ShareMetadata` rows: an `Unmonitored` holder never
    * alarms, and a `SilentOverdue` one drops out reversibly instead of being counted as still live.
    */
  def nLive(now: Instant): Int = holders.count(_.freshnessBucket(now) == FreshnessBucket.Confirmed)

  def health(now: Instant): SecretHealth =
    if secret.state == SecretState.Destroying then SecretHealth.Destroying
    else
      val live = nLive(now)
      if live < secret.k then SecretHealth.Lost
      else if live == secret.k then SecretHealth.Critical
      else if live == secret.k + 1 then SecretHealth.Caution
      else SecretHealth.Healthy

  /** Mirrors what `requestAll` actually does — it skips a holder whose retrieval row is Pending or Approved — so the
    * press is worth offering while any holder still lacks one, and is a no-op only once nobody is left to ask.
    *
    * Deliberately still enabled once k copies are in: a surplus beyond the threshold is what lets reconstruct
    * cross-check the shares it has, so asking the stragglers is how a "no integrity margin" outcome becomes a confirmed
    * one. Same rule on both mobile Distributed tabs.
    */
  def canRequestRetrieval: Boolean =
    secret.state == SecretState.Active && holders.exists(
      _.retrievalRequest.forall(r => r.state != ShareRequestState.Pending && r.state != ShareRequestState.Approved)
    )

  /** Why **Retrieve shares** cannot be pressed, as a message key, or None when it can be. A control that cannot work
    * says so in words rather than disappearing.
    */
  def retrievalUnavailableReason: Option[String] =
    if secret.state != SecretState.Active then Some("phon.secretDetail.retrieveDisabled.destroying")
    else if !canRequestRetrieval then Some("phon.secretDetail.retrieveDisabled.allAsked")
    else None

  def approvedRetrievals: Int =
    holders.count(_.retrievalRequest.exists(_.state == ShareRequestState.Approved))

  def canReconstruct: Boolean = approvedRetrievals >= secret.k

  /** How many more holders have to approve before the secret can be put back together. Zero once it can. */
  def reconstructShortfall: Int = math.max(0, secret.k - approvedRetrievals)

  /** Collected copies are what there is to clear. An ask still waiting for an answer is cleared along with them, but on
    * its own means nothing has been collected yet.
    */
  def canClearCollected: Boolean = approvedRetrievals > 0

final case class HeldShareRow(
    share: HeldShare,
    senderName: String,
    senderSubtitle: Option[String],
    /** How many shares this device holds from the same sender — what decides whether the delete confirmation also
      * offers to drop all of them, as on Android.
      */
    siblingsFromSender: Int
)

enum HeldSortOrder(val wireValue: String):
  case Date extends HeldSortOrder("date")
  case Label extends HeldSortOrder("label")
  case Sender extends HeldSortOrder("sender")

object HeldSortOrder:
  def fromWire(s: String): HeldSortOrder = values.find(_.wireValue == s).getOrElse(Date)

final case class RequestRow(
    request: ShareRequest,
    senderName: String,
    senderSubtitle: Option[String],
    /** Days since the requester's key last changed, and only on a retrieval — the attack signature this hardens against
      * is a key change followed quickly by a retrieval request.
      */
    keyChangedDaysAgo: Option[Long]
)

final case class KeyConflictRow(conflict: KeyConflict, contactName: String)

final case class ContactRow(
    contact: Contact,
    awaitingRelink: Boolean,
    /** Shares this device holds from them — the "delete all from" affordance needs to know. */
    heldFromThem: Int
):
  def displayName: String = contact.nickname.getOrElse(contact.pseudonym)
  def subtitle: Option[String] = contact.nickname.map(_ => contact.pseudonym)
  def keyFlaggedCompromised: Boolean = contact.revokedVerifyKeys.nonEmpty
  def verificationLevel: VerificationLevel = contact.verificationLevel

object PhonViewModels:

  private val Unknown = "?"

  private def displayName(contact: Option[Contact]): String =
    contact.map(c => c.nickname.getOrElse(c.pseudonym)).getOrElse(Unknown)

  private def subtitle(contact: Option[Contact]): Option[String] =
    contact.flatMap(c => c.nickname.map(_ => c.pseudonym))

  /** Groups `ShareMetadata` rows under the `Secret` they belong to and attaches each holder's latest request of each
    * kind. Newest secret first, as on both mobile Distributed tabs.
    */
  def secretGroups(
      secrets: List[Secret],
      distributed: List[ShareMetadata],
      sentRequests: List[ShareRequest],
      contacts: List[Contact]
  ): List[SecretGroup] =
    val sharesBySecret = distributed.groupBy(_.secretId)
    val contactsById = contacts.map(c => c.id -> c).toMap
    secrets
      .map { secret =>
        val holders = sharesBySecret.getOrElse(secret.id, Nil).map { share =>
          val contact = contactsById.get(share.contactId)
          def latest(kind: ShareTransactionType): Option[ShareRequest] =
            contact.flatMap { holder =>
              sentRequests
                .filter(r =>
                  r.secretId == share.secretId &&
                    r.transactionType == kind &&
                    r.recipientKey.sameElements(holder.verifyKey)
                )
                .maxByOption(_.requestedAt)
            }
          HolderStatus(
            shareId = share.id,
            contactId = share.contactId,
            recipientName = displayName(contact),
            recipientSubtitle = subtitle(contact),
            retrievalRequest = latest(ShareTransactionType.Retrieval),
            removalRequest = latest(ShareTransactionType.Removal),
            lastConfirmedAt = share.lastConfirmedAt,
            heartbeatOptedOutAt = contact.flatMap(_.heartbeatOptedOutAt)
          )
        }
        SecretGroup(secret, holders)
      }
      .sortBy(_.secret.secretCreatedAt)
      .reverse

  def heldRows(held: List[HeldShare], contacts: List[Contact], order: HeldSortOrder): List[HeldShareRow] =
    val contactsById = contacts.map(c => c.id -> c).toMap
    val perSender = held.groupBy(_.contactId).view.mapValues(_.size).toMap
    val rows = held.map { share =>
      val contact = contactsById.get(share.contactId)
      HeldShareRow(
        share = share,
        senderName = displayName(contact),
        senderSubtitle = subtitle(contact),
        siblingsFromSender = perSender.getOrElse(share.contactId, 1)
      )
    }
    order match
      case HeldSortOrder.Date   => rows.sortBy(_.share.createdAt).reverse
      case HeldSortOrder.Label  => rows.sortBy(_.share.label.toLowerCase)
      case HeldSortOrder.Sender => rows.sortBy(r => (r.senderName.toLowerCase, r.share.label.toLowerCase))

  /** Inbound requests, newest first. The sender is matched by verify key rather than by contact id, because a request
    * arrives from a key: a sender this device does not know yet has no contact row to look up.
    */
  def requestRows(requests: List[ShareRequest], contacts: List[Contact], now: Instant): List[RequestRow] =
    requests
      .map { request =>
        val contact = contacts.find(_.verifyKey.sameElements(request.senderKey))
        RequestRow(
          request = request,
          senderName = displayName(contact),
          senderSubtitle = subtitle(contact),
          keyChangedDaysAgo = Option
            .when(request.transactionType == ShareTransactionType.Retrieval)(contact.flatMap(_.keyChangedAt))
            .flatten
            .map(at => Duration.between(at, now).toDays)
        )
      }
      .sortBy(_.request.requestedAt)
      .reverse

  def keyConflictRows(conflicts: List[KeyConflict], contacts: List[Contact]): List[KeyConflictRow] =
    val contactsById = contacts.map(c => c.id -> c).toMap
    conflicts
      .map(conflict => KeyConflictRow(conflict, displayName(contactsById.get(conflict.contactId))))
      .sortBy(_.conflict.detectedAt)
      .reverse

  def contactRows(contacts: List[Contact], awaitingRelink: List[Contact], held: List[HeldShare]): List[ContactRow] =
    val awaitingIds = awaitingRelink.map(_.id).toSet
    val heldPerSender = held.groupBy(_.contactId).view.mapValues(_.size).toMap
    contacts
      .map(contact =>
        ContactRow(
          contact = contact,
          awaitingRelink = awaitingIds.contains(contact.id),
          heldFromThem = heldPerSender.getOrElse(contact.id, 0)
        )
      )
      .sortBy(row => (row.displayName.toLowerCase, row.contact.addedAt))
