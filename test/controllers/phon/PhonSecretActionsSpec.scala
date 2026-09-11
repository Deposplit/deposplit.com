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

import org.scalatestplus.play.*
import value_objects.svo.MimeType
import value_objects.svo.Secret
import value_objects.svo.SecretState
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType

import java.time.Instant
import java.util.UUID

/** The three things that can be done to a whole secret — ask the holders for copies, put it back together, let the
  * copies go again — and, for each, exactly when it can be pressed.
  *
  * All three stay on screen in every state, so these rules decide only what is *enabled*, and every disabled state owes
  * the reader a reason. Pinning them here because they are implemented three times over, once per platform, and are
  * precisely the sort of thing that drifts: this is where phon's copy of them is held to what `requestAll` and
  * `reconstruct` actually do.
  */
class PhonSecretActionsSpec extends PlaySpec {

  private val secretCreatedAt = Instant.now()

  private def secret(k: Int, n: Int, state: SecretState = SecretState.Active): Secret =
    Secret(UUID.randomUUID(), "a secret", MimeType.Default, k, n, secretCreatedAt, state)

  private def retrieval(state: ShareRequestState): ShareRequest =
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = UUID.randomUUID(),
      senderKey = Array.emptyByteArray,
      recipientKey = Array.emptyByteArray,
      label = "a secret",
      secretCreatedAt = secretCreatedAt,
      transactionType = ShareTransactionType.Retrieval,
      state = state,
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = None,
      k = None,
      n = None,
      mimeType = None,
      senderSignature = Array.emptyByteArray,
      recipientSignature = None
    )

  private def holder(retrievalState: Option[ShareRequestState]): HolderStatus =
    HolderStatus(
      shareId = UUID.randomUUID(),
      contactId = UUID.randomUUID(),
      recipientName = "a holder",
      recipientSubtitle = None,
      retrievalRequest = retrievalState.map(retrieval),
      removalRequest = None,
      lastConfirmedAt = None,
      heartbeatOptedOutAt = None
    )

  private def group(k: Int, states: Option[ShareRequestState]*): SecretGroup =
    SecretGroup(secret(k, states.size), states.toList.map(holder))

  "Asking the holders for copies" should {

    "be offered while any holder still lacks a live request" in {
      group(2, None, None).canRequestRetrieval mustBe true
      group(2, Some(ShareRequestState.Pending), None).canRequestRetrieval mustBe true
      group(2, Some(ShareRequestState.Approved), None).canRequestRetrieval mustBe true
      group(2, Some(ShareRequestState.Denied), None).canRequestRetrieval mustBe true
    }

    // A denied holder is askable again; that is the whole point of a denial being a legitimate
    // answer rather than a dead end.
    "be offered to a holder who said no" in {
      group(2, Some(ShareRequestState.Denied), Some(ShareRequestState.Approved)).canRequestRetrieval mustBe true
    }

    "go quiet only once every holder has been asked" in {
      val everyoneAsked = group(2, Some(ShareRequestState.Pending), Some(ShareRequestState.Approved))
      everyoneAsked.canRequestRetrieval mustBe false
      everyoneAsked.retrievalUnavailableReason mustBe Some("phon.secretDetail.retrieveDisabled.allAsked")
    }

    // The surplus is the point: reconstruct can only cross-check the shares it has against each
    // other, so a holder who has not answered yet is worth asking even after k already have.
    "stay offered once k copies are already in, so a surplus can still be collected" in {
      val enoughButNotEveryone = group(2, Some(ShareRequestState.Approved), Some(ShareRequestState.Approved), None)
      enoughButNotEveryone.canReconstruct mustBe true
      enoughButNotEveryone.canRequestRetrieval mustBe true
      enoughButNotEveryone.retrievalUnavailableReason mustBe None
    }

    "say so when the secret is on its way out instead" in {
      val discarding = SecretGroup(secret(2, 2, SecretState.Discarding), List(holder(None), holder(None)))
      discarding.canRequestRetrieval mustBe false
      discarding.retrievalUnavailableReason mustBe Some("phon.secretDetail.retrieveDisabled.discarding")
    }
  }

  "Putting the secret back together" should {

    "wait for k approvals, and say how many are still missing" in {
      group(2, None, None).canReconstruct mustBe false
      group(2, None, None).reconstructShortfall mustBe 2
      group(2, Some(ShareRequestState.Approved), None).reconstructShortfall mustBe 1
      group(2, Some(ShareRequestState.Approved), Some(ShareRequestState.Approved)).reconstructShortfall mustBe 0
    }

    "count only approvals, never a pending ask or a denial" in {
      group(2, Some(ShareRequestState.Pending), Some(ShareRequestState.Denied)).approvedRetrievals mustBe 0
    }

    "report no shortfall once past the threshold" in {
      val surplus =
        group(2, Some(ShareRequestState.Approved), Some(ShareRequestState.Approved), Some(ShareRequestState.Approved))
      surplus.reconstructShortfall mustBe 0
      surplus.canReconstruct mustBe true
    }
  }

  "Clearing the collected copies" should {

    // An ask still waiting is cleared along with the copies, but on its own there is nothing
    // collected to hand back yet.
    "wait until at least one holder has actually handed a piece back" in {
      group(2, None, None).canClearCollected mustBe false
      group(2, Some(ShareRequestState.Pending), None).canClearCollected mustBe false
      group(2, Some(ShareRequestState.Approved), None).canClearCollected mustBe true
    }

    "be offered well before the secret could be put back together" in {
      val halfway = group(3, Some(ShareRequestState.Approved), None, None)
      halfway.canReconstruct mustBe false
      halfway.canClearCollected mustBe true
    }
  }
}
