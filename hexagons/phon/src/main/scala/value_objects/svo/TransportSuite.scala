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

/** A ciphertext-only, 1-byte transport tag — deliberately lighter-weight than [[CipherSuite]] and
  * not JSON-facing, since it never rides the wire except as the leading byte of a ciphertext blob.
  * See deposplit.com/CLAUDE.md item 14: the ciphertext wire format becomes
  * `suiteTag(1) || nonce(12) || ciphertext+tag`. Needs no persistent state or trust mechanism —
  * item 7 already re-derives each deposit/retrieval leg fresh, so a device just always encrypts
  * with its current preferred suite and a decrypting device dispatches on the tag it reads.
  */
enum TransportSuite(val tag: Byte):
  case X25519HkdfSha256ChaCha20Poly1305 extends TransportSuite(1)

object TransportSuite:
  def fromTag(tag: Byte): Option[TransportSuite] = values.find(_.tag == tag)

  /** The only construction this codebase's encrypt/decrypt can produce today. */
  val current: TransportSuite = X25519HkdfSha256ChaCha20Poly1305
