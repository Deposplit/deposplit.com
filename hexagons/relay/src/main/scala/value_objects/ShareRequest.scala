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

package value_objects

import java.time.Instant
import java.util.UUID

/** A share request row — self-describing with embedded routing metadata.
  *
  * `shareId` is None for Deposit and Inventory rows (both are roots — the former the share record itself, the latter a
  * report about one). For Retrieval and Removal rows it holds the id of the originating Deposit request, supplied by
  * the client and stored opaquely by the relay.
  *
  * `ciphertext` semantics differ by type:
  *   - Deposit: provided by Alice at creation; delivered to Bob on approval and cleared.
  *   - Retrieval: provided by Bob on approval; stored until Alice collects it.
  *   - Removal: always None.
  *   - Inventory: always None — this type never carries share bytes, only the metadata needed to rebuild a
  *     `ShareMetadata` row (see "state" below).
  *
  * `k`/`n` are populated for Deposit and Inventory only (None for Retrieval/Removal). Signed as part of
  * `senderSignature` so a holder can't misreport them without invalidating the row.
  *
  * `state` is `Pending` at creation for Deposit/Retrieval/Removal, awaiting the recipient's approve/deny. Inventory is
  * different: it's a holder-initiated *push*, not a consent-gated request — nothing for the recipient to approve — so
  * it's created directly in `Approved` state (`respondedAt` set immediately, `recipientSignature` left None). The
  * recipient simply polls for it and deletes the row once consumed, via the same `deleteShareRequestById` any party may
  * already call.
  *
  * `senderSignature` and `recipientSignature` are Ed25519 signatures over `PayloadCanonical`'s byte constructions —
  * independent of the transport-auth signature, they let any reader re-verify authorship, which is what makes BYOR (a
  * passive third-party relay) safe. See `PayloadCanonical` for the exact bytes signed.
  */
case class ShareRequest(
    id: UUID,
    secretId: SecretId,
    senderKey: PublicKey,
    recipientKey: PublicKey,
    label: Label,
    secretCreatedAt: Instant,
    transactionType: ShareTransactionType,
    state: ShareRequestState,
    shareId: Option[UUID],
    requestedAt: Instant,
    respondedAt: Option[Instant],
    ciphertext: Option[Array[Byte]],
    k: Option[Int],
    n: Option[Int],
    senderSignature: Signature,
    recipientSignature: Option[Signature]
)
