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

package driving_ports

import value_objects.*

import java.util.UUID

/** Item 9's signed `rotate(K_old -> K_new)` push — a holder's proactive "I am now K_new,
  * previously K_old" notice, addressed to one contact at a time. Deliberately separate from
  * `ShareRequests`: a rotation notice has no `secretId` and no consent phase, so it does not fit
  * that port's share-shaped contract.
  */
trait KeyRotations:

  /** Pushes a signed rotation notice to one contact. The authenticated caller becomes
    * `oldVerifyKey` — `signature` must verify against it over `PayloadCanonical.forRotation`,
    * proving the caller still controls the old private key. Returns `BadRequest` if it doesn't
    * verify, or if `newVerifyKey`/`newEncKey`'s byte length doesn't match what `newCipherSuite`
    * declares (item 14).
    *
    * No consent phase and no conflict check — fire-and-forget, the same shape as Inventory
    * (deposplit.com/CLAUDE.md "What is next" item 8): the recipient polls, auto-verifies, and
    * deletes the row once consumed.
    */
  def pushRotation(
      oldVerifyKey: PublicKey,
      recipientKey: PublicKey,
      newVerifyKey: PublicKey,
      newEncKey: X25519Key,
      newCipherSuite: CipherSuite,
      signature: Signature
  ): Either[Error, KeyRotation]

  /** Rotation notices addressed to `recipientKey`. */
  def listRotations(recipientKey: PublicKey): Either[Error, Seq[KeyRotation]]

  /** Deletes a rotation notice once consumed. Only the recipient may delete it — unlike
    * `ShareRequest` rows, there's no sender-side reason to read one back.
    */
  def deleteRotation(recipientKey: PublicKey, id: UUID): Either[Error, Unit]
