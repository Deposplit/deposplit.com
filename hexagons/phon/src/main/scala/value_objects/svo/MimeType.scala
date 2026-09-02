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

/** The sender-declared media type of a secret — `"text/plain"` for typed text, `"image/png"` or `"image/jpeg"` for a
  * picked image.
  *
  * Best-effort in general, at exactly the trust level `label` already has: a receiving device cannot check the claim
  * against the bytes, and the relay could not check it either, seeing only ciphertext. For a secret this device splits
  * itself the claim is nevertheless true by construction, because [[MimeType.sniffed]] reads it off the payload rather
  * than believing whatever handed the bytes over.
  *
  * The mobile apps' MimeType additionally classifies (`isText`/`isImage`) for their rendering fork. phon has no
  * reconstruct screen, so it carries the value without interpreting it.
  */
case class MimeType(value: String) extends Serializable

object MimeType:
  val Default: MimeType = MimeType("text/plain")
  val Png: MimeType = MimeType("image/png")
  val Jpeg: MimeType = MimeType("image/jpeg")

  private val PngMagic: Array[Byte] =
    Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
  private val JpegMagic: Array[Byte] = Array(0xff, 0xd8, 0xff).map(_.toByte)

  /** The image type these bytes actually are, or `None` for anything else.
    *
    * The accepted set is PNG and JPEG deliberately, and is the whole of it: every additional format is more decoder
    * surface reached by attacker-chosen bytes, for a use case nobody has asked for yet. SVG in particular is scriptable
    * and will not be added.
    *
    * Recognition is by leading bytes, never by a file name or by what a picker claimed, so the declared type of a
    * secret this device splits cannot disagree with its payload.
    */
  def sniffed(bytes: Array[Byte]): Option[MimeType] =
    if startsWith(bytes, PngMagic) then Some(Png)
    else if startsWith(bytes, JpegMagic) then Some(Jpeg)
    else None

  private def startsWith(bytes: Array[Byte], magic: Array[Byte]): Boolean =
    bytes.length >= magic.length && magic.indices.forall(i => bytes(i) == magic(i))
