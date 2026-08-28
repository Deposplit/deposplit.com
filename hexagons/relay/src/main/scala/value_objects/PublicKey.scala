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

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/** Raw bytes of a signing-algorithm public key, base64url-encoded on the wire.
  *
  * Not pinned to an exact length — see deposplit.com/CLAUDE.md item 14 ("variable-length keys"): a future signing
  * algorithm will not share Ed25519's 32-byte key size, so only a generous sanity bound is enforced here. Exact-length
  * validation against a known algorithm happens wherever a [[CipherSuite]] is actually asserted alongside the key (e.g.
  * `KeyRotationsService`).
  */
opaque type PublicKey = Array[Byte]

object PublicKey:
  // A sanity bound only, not algorithm-exact — see the type doc above. Not sized for any specific
  // future algorithm; revisit once one is actually chosen (deposplit.com/CLAUDE.md item 14).
  private val MaxLength = 128
  private val decoder = Base64.getUrlDecoder
  private val encoder = Base64.getUrlEncoder.withoutPadding

  def fromBase64Url(s: String): Either[String, PublicKey] =
    try
      val bytes = decoder.decode(s)
      if bytes.isEmpty || bytes.length > MaxLength then Left(s"public key must be 1..$MaxLength bytes")
      else Right(bytes)
    catch case _: IllegalArgumentException => Left(s"invalid base64url: $s")

  def fromBytes(bytes: Array[Byte]): Either[String, PublicKey] =
    if bytes.isEmpty || bytes.length > MaxLength then Left(s"public key must be 1..$MaxLength bytes")
    else Right(bytes)

  extension (pk: PublicKey)
    def toBase64Url: String = encoder.encodeToString(pk)
    def toBytes: Array[Byte] = pk

    /** Verifies a `signature` over `message`. Returns false on any error.
      *
      * Dispatches on signing algorithm — a trivial single-branch dispatch today (only Ed25519 exists), structured as a
      * named per-algorithm function so a second algorithm is an additional branch here rather than a rewrite. See
      * deposplit.com/CLAUDE.md item 14.
      */
    def verify(message: Array[Byte], signature: Signature): Boolean = verifyEd25519(pk, message, signature)

  private def verifyEd25519(pk: PublicKey, message: Array[Byte], signature: Signature): Boolean =
    try
      val verifier = new Ed25519Signer()
      verifier.init(false, new Ed25519PublicKeyParameters(pk, 0))
      verifier.update(message, 0, message.length)
      verifier.verifySignature(signature.toBytes)
    catch case _: Exception => false
