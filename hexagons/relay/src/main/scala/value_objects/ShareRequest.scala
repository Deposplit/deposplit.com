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
  * `shareId` is None for PickUp rows (they are the root share record).
  * For Retrieve and Delete rows it holds the id of the originating PickUp request,
  * supplied by the client and stored opaquely by the relay.
  *
  * `ciphertext` semantics differ by type:
  *   - PickUp:   provided by Alice at creation; delivered to Bob on approval and cleared.
  *   - Retrieve: provided by Bob on approval; stored until Alice collects it.
  *   - Delete:   always None.
  */
case class ShareRequest(
    id: UUID,
    secretId: SecretId,
    senderKey: PublicKey,
    recipientKey: PublicKey,
    label: Label,
    secretCreatedAt: Instant,
    requestType: ShareRequestType,
    state: ShareRequestState,
    shareId: Option[UUID],
    requestedAt: Instant,
    respondedAt: Option[Instant],
    ciphertext: Option[Array[Byte]]
)
