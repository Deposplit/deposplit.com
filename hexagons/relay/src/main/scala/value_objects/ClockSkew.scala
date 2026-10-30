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

import java.time.Duration

/** How far ahead of this relay's clock a client's may run before a time it stamps is refused as lying in the future.
  *
  * No two clocks agree exactly, and over a short link a request arrives before its own timestamp has come due here: a
  * phone a tenth of a second fast, reaching a relay through `adb reverse` in a millisecond, had every request refused.
  * A minute absorbs any clock kept by network time, and costs little: a captured request still expires five minutes
  * after the time it carries, so its life is stretched only by however far its sender's clock already runs ahead.
  *
  * Both the transport nonce and a heartbeat's `emittedAt` are held to it.
  */
object ClockSkew:
  val MaxAhead: Duration = Duration.ofMinutes(1)
