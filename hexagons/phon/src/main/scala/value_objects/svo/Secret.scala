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

import java.time.Instant
import java.util.UUID

/** Two-state lifecycle — see deposplit.com/CLAUDE.md "What is next" item 11. No `Discarded` tombstone: once every
  * holder confirms deletion (or the sender force-forgets), the `Secret` record is removed outright.
  */
enum SecretState:
  case Active, Discarding

/** Sender-side per-secret aggregate — the single source of truth for k/n/label/secretCreatedAt, keyed by secretId.
  * `ShareMetadata` rows reference this rather than duplicating its fields. See deposplit.com/CLAUDE.md "What is next"
  * item 11.
  */
case class Secret(
    id: UUID,
    label: String,
    k: Int,
    n: Int,
    secretCreatedAt: Instant,
    state: SecretState
) extends Serializable
