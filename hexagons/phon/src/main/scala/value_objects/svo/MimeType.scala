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

/** The sender-declared media type of a secret — `"text/plain"` for everything that can be split today.
  *
  * Sender-supplied and best-effort, exactly the trust level `label` already has: nothing sniffs the bytes to check the
  * claim. It rides the deposit payload and the inventory push so a holder can hand it back during recovery, and so a
  * recipient can decide how to render a reconstructed secret.
  *
  * The mobile apps' MimeType additionally classifies (`isText`/`isImage`) for that rendering fork. phon has no
  * reconstruct screen, so it carries the value without interpreting it.
  */
case class MimeType(value: String) extends Serializable

object MimeType:
  val Default: MimeType = MimeType("text/plain")
