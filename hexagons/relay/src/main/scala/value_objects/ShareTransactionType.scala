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

/** The kind of thing that happened (or is being asked to happen) to a share, phrased as a neutral transaction noun
  * rather than either party's verb — see deposplit.com/CLAUDE.md "Cross-cutting implementation chores" for why: naming
  * from a single named actor's point of view (Alice's, or Bob's) breaks down because the actor genuinely alternates —
  * Alice always opens Deposit/Retrieval/Removal, but the *holder* opens Inventory (holder → owner).
  *
  * `wireValue` is the single source of truth for this type's wire representation — the DB enum label, the JSON
  * `transactionType` value, and the string `PayloadCanonical` signs are all the same `wireValue`, looked up here rather
  * than re-derived by each adapter.
  */
enum ShareTransactionType(val wireValue: String):
  case Deposit extends ShareTransactionType("deposit")
  case Retrieval extends ShareTransactionType("retrieval")
  case Removal extends ShareTransactionType("removal")
  case Inventory extends ShareTransactionType("inventory")

object ShareTransactionType:
  def fromWire(s: String): Option[ShareTransactionType] = values.find(_.wireValue == s)
