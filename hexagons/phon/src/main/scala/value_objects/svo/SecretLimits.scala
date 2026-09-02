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

/** The largest secret that may be split.
  *
  * Shamir shares are byte-wise: an S-byte secret becomes n shares of S bytes each, every one of them encrypted,
  * base64-encoded into its own request, and held by the relay until its holder picks it up — while the sender retains a
  * copy of all n until every pickup is confirmed. The cost of a secret is therefore several times its size, several
  * times over, which is why there is a limit at all and why it is this modest.
  *
  * It applies to every secret uniformly, typed text included, and is enforced in the domain rather than at an input
  * form so that no entry point can slip past it — a re-split during a repair least of all.
  *
  * The relay enforces a bound of its own, deliberately looser: it cannot know what any client's limit is.
  */
object SecretLimits:
  val MaxSecretBytes: Int = 256 * 1024
