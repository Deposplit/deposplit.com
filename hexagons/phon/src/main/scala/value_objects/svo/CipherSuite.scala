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

/** The matched pairing of signing algorithm + key-agreement algorithm an identity currently uses — see the
  * crypto-agility mechanism. One case exists today; the point of naming it explicitly is making a future fleet-wide
  * algorithm swap an additive new case rather than a breaking wire-format migration. Client-side mirror of
  * `hexagons/relay`'s `CipherSuite` (separate sbt subprojects, no dependency between them) —
  * byte-for-byte/wire-value-for-wire-value identical; keep both in sync.
  */
enum CipherSuite(val wireValue: String, val verifyKeyLength: Int, val encKeyLength: Int):
  case Ed25519X25519V1 extends CipherSuite("ed25519+x25519-v1", verifyKeyLength = 32, encKeyLength = 32)

object CipherSuite:
  def fromWire(s: String): Option[CipherSuite] = values.find(_.wireValue == s)

  /** The only suite this codebase's key generation can produce today. */
  val current: CipherSuite = Ed25519X25519V1
