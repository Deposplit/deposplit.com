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

/** A box-only, 1-byte transport tag — deliberately lighter-weight than [[CipherSuite]], which names the key types, and
  * not JSON-facing, since it never rides the wire except as the leading byte of a box: `suiteTag(1) || enc(32) ||
  * ciphertext+tag(16)`. Needs no persistent state or trust mechanism — each box is sealed fresh, so a device just
  * always seals with its current preferred suite and an opening device dispatches on the tag it reads.
  */
enum TransportSuite(val tag: Byte):
  // HPKE base mode (RFC 9180): DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, with a fresh ephemeral
  // sender key per box. Tag 1 was a static-static box the sender could reopen, and is gone rather than kept readable,
  // so a leftover box from it fails loudly instead of misparsing.
  case HpkeX25519Sha256ChaCha20Poly1305 extends TransportSuite(2)

object TransportSuite:
  def fromTag(tag: Byte): Option[TransportSuite] = values.find(_.tag == tag)

  /** The only construction this codebase's encrypt/decrypt can produce today. */
  val current: TransportSuite = HpkeX25519Sha256ChaCha20Poly1305
