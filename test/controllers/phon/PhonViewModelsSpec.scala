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
import value_objects.svo.HeldShare
import value_objects.svo.MimeType
import value_objects.svo.ShareRequest
import value_objects.svo.ShareRequestState
import value_objects.svo.ShareTransactionType

import java.time.Instant
import java.util.UUID

/** What the Requests screen may offer on each row.
  *
  * Approving a retrieval re-encrypts the share to the requester, so it cannot work once this device has deleted that
  * share — and the ask can still arrive, because the owner learns of the deletion only on their next poll. The row has
  * to say so instead of offering a button that silently does nothing.
  */
class PhonViewModelsSpec extends PlaySpec {

  private val now = Instant.parse("2026-09-23T12:00:00Z")
  private val keptSecret = UUID.randomUUID()
  private val deletedSecret = UUID.randomUUID()
  private val held = List(
    HeldShare(
      UUID.randomUUID(),
      keptSecret,
      "kept",
      UUID.randomUUID(),
      now,
      now,
      Array[Byte](1),
      2,
      3,
      MimeType.Default
    )
  )

  private def request(secretId: UUID, kind: ShareTransactionType) =
    ShareRequest(
      id = UUID.randomUUID(),
      secretId = secretId,
      senderKey = Array.fill(32)(7.toByte),
      recipientKey = Array.fill(32)(8.toByte),
      label = "label",
      secretCreatedAt = now,
      transactionType = kind,
      state = ShareRequestState.Pending,
      requestedAt = now,
      respondedAt = None,
      ciphertext = None,
      senderSignature = Array.emptyByteArray,
      recipientSignature = None
    )

  private def canApprove(secretId: UUID, kind: ShareTransactionType): Boolean =
    PhonViewModels.requestRows(List(request(secretId, kind)), Nil, held, now).head.canApprove

  "A pending request" should {

    "offer Approve on a retrieval for a share this device still holds" in {
      canApprove(keptSecret, ShareTransactionType.Retrieval) mustBe true
    }

    "withhold Approve on a retrieval for a share this device no longer holds" in {
      canApprove(deletedSecret, ShareTransactionType.Retrieval) mustBe false
    }

    // Approving a removal hands nothing over, so there is nothing it needs in hand.
    "offer Approve on a removal whether or not the share is still here" in {
      canApprove(deletedSecret, ShareTransactionType.Removal) mustBe true
    }
  }
}
