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

package driven_adapters.phon

import org.scalatestplus.play.*
import play.api.Configuration
import value_objects.svo.HeldShare
import value_objects.svo.MimeType

import java.io.File
import java.time.Instant
import java.util.UUID

/** The held-share store had the same appending `save` the contact store had. Nothing exercises it yet — the one caller
  * is guarded by an `alreadyHeld` check in `ShareService`, so a second pickup of the same share never reaches it —
  * which is exactly why it is worth pinning: the guard is what makes the shape harmless, not the store.
  *
  * Every case uses a port of its own, because the port is what names the file.
  */
class FileShareRepositorySpec extends PlaySpec {

  private def onPort(port: Int)(check: FileShareRepository => Unit): Unit =
    val file = File(s"./.devDBs/heldshares$port.ser")
    File("./.devDBs").mkdirs()
    file.delete()
    try check(FileShareRepository(Configuration("http.port" -> port)))
    finally file.delete()

  private def share(label: String): HeldShare = HeldShare(
    id = UUID.randomUUID(),
    secretId = UUID.randomUUID(),
    label = label,
    contactId = UUID.randomUUID(),
    createdAt = Instant.now(),
    pickedUpAt = Instant.now(),
    plaintextShare = Array.fill(32)(0x07.toByte),
    k = 2,
    n = 3,
    mimeType = MimeType.Default
  )

  "FileShareRepository" should {

    "replace a share it already holds rather than adding a second one" in {
      onPort(19021) { repository =>
        val held = share("passport")
        repository.save(held)
        repository.save(held.copy(label = "passport scan"))
        repository.getAll().size mustBe 1
        repository.getAll().head.label mustBe "passport scan"
      }
    }

    "report the share for a secret it holds" in {
      onPort(19022) { repository =>
        val held = share("passport")
        repository.save(held)
        repository.getPlaintextShare(held.secretId).map(_.toSeq) mustBe Some(held.plaintextShare.toSeq)
        repository.getPlaintextShare(UUID.randomUUID()) mustBe None
      }
    }
  }
}
