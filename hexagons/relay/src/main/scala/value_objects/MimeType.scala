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

/** The sender-declared media type of the secret a Deposit or Inventory row is about — `"text/plain"` for typed text,
  * `"image/png"` or `"image/jpeg"` for a picked image.
  *
  * Sender-supplied and best-effort, exactly the trust level `label` already has: the relay routes it and never
  * interprets it, never sniffs the bytes, and could not check the claim if it wanted to — the payload is ciphertext it
  * cannot read. Only the recipient acts on it, when deciding how to render a reconstructed secret.
  *
  * No classifiers here (`isText`/`isImage` and the like live on the clients' own MimeType) because nothing in the relay
  * ever needs to know what the string means.
  */
opaque type MimeType = String

object MimeType:
  def apply(s: String): MimeType = s

  extension (m: MimeType) def value: String = m
