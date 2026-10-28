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

package value_objects.svo

import java.time.Instant
import java.util.UUID

/** The sender's record that a holder's pickup is not yet confirmed. Kept from the deposit until the pickup is confirmed
  * (relay-observed or heartbeat-attested), the holder withdraws, or the holder's `ShareMetadata` goes with an approved
  * removal or a Force Forget — then discarded. Each blob is sealed to the *holder's* X25519 key under an ephemeral key
  * discarded at sealing, so the sender cannot open it herself — retaining all n is n opaque forward-only blobs, not a
  * reconstructable secret sitting on her device.
  *
  * Nothing reads `ciphertext` back, and nothing re-deposits from it: the relay expires a deposit nobody picks up, like
  * any row, and a holder who never picked up never counted toward n_live. So the record serves only as the "not yet
  * confirmed by any channel" marker `syncDistributed` gates on. `id` matches the originating deposit `ShareRequest`'s
  * id (and therefore `ShareMetadata.id`), so the record can be looked up and discarded by the same key
  * `syncDistributed` already keys off of.
  */
case class RetainedDepositBlob(
    id: UUID,
    secretId: UUID,
    contactId: UUID,
    label: String,
    secretCreatedAt: Instant,
    ciphertext: Array[Byte],
    k: Int,
    n: Int,
    mimeType: MimeType
) extends Serializable:
  override def equals(other: Any): Boolean = other match
    case b: RetainedDepositBlob => id == b.id
    case _                      => false
  override def hashCode(): Int = id.hashCode()
