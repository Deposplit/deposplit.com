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

import java.util.UUID

/** The outcome of `ShareManagement.reconstruct`'s over-determination cross-check. `NoMargin` means exactly k shares
  * were available (no surplus to check against — the "reconstructed without integrity margin" case). `Confirmed` means
  * more than k were collected and all of them agreed. `ExcludedSuspects` means more than k were collected, at least one
  * disagreed, and the disagreeing share(s) were identified and excluded — the reconstructed secret still comes from a
  * group large enough to make that exclusion provably correct (see `shamir.SecretSharing.combineWithIntegrity`), not a
  * guess.
  */
enum ReconstructionIntegrity extends Serializable:
  case NoMargin
  case Confirmed
  case ExcludedSuspects(excludedContactIds: Set[UUID])

/** `mimeType` is the owner's own record of what she split, carried alongside the bytes so a caller deciding how to
  * render them never has to go back to the `Secret` aggregate and risk pairing bytes with the wrong type.
  */
case class ReconstructionResult(
    secret: Array[Byte],
    integrity: ReconstructionIntegrity,
    mimeType: MimeType
) extends Serializable
