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

// Outcome of ShareManagement.regenerateIdentity() — how many of this device's contacts were
// successfully notified of the new key via a signed rotation push before the new identity was
// activated locally. A contact not reached here never learns of the new key automatically; there
// is no retry mechanism, matching pushRotation's own one-shot semantics.
case class RegenerateIdentityResult(
    notifiedContacts: Int,
    totalContacts: Int,
    // Whether the pre-rotation drain — collecting anything still addressed to the old identity —
    // completed. Rotation proceeds either way, deliberately: an unreachable relay must not be able
    // to block a user who is rotating precisely because they think their key is compromised.
    //
    // Reported rather than silently dropped so the UI can say what was skipped. It is a warning,
    // not a loss: the displaced decKey is retained one generation, so a deposit that was not
    // drained still opens on a later poll.
    drainSucceeded: Boolean
) extends Serializable
