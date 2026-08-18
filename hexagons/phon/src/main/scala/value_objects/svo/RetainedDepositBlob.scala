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

/** Item 12's deposit-retention rule — the sender retains each per-holder encrypted blob locally
  * until that holder's pickup is confirmed (relay-observed or heartbeat-attested), then discards
  * it. This is what makes a relay GC before pickup a cheap re-deposit rather than a lost share:
  * under item 7 each blob is encrypted to the *holder's* X25519 key, so the sender cannot decrypt
  * it herself — retaining all n is n opaque forward-only blobs, not a reconstructable secret
  * sitting on her device. `id` matches the originating deposit `ShareRequest`'s id (and therefore
  * `ShareMetadata.id`), so the record can be looked up and discarded by the same key
  * `syncDistributed` already keys off of. See deposplit.com/CLAUDE.md "What is next" item 12.
  */
case class RetainedDepositBlob(
    id: UUID,
    secretId: UUID,
    contactId: UUID,
    label: String,
    secretCreatedAt: Instant,
    ciphertext: Array[Byte],
    k: Int,
    n: Int
) extends Serializable:
  override def equals(other: Any): Boolean = other match
    case b: RetainedDepositBlob => id == b.id
    case _                      => false
  override def hashCode(): Int = id.hashCode()
