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
import value_objects.svo.Contact
import value_objects.svo.VerificationLevel

import java.io.File
import java.time.Instant
import java.util.UUID

/** Every mutation phon performs on a contact — rename, the compromised flag, a relink, and the heartbeat stamp
  * `ShareService` writes on each push — reads the contact, copies it, and hands it back to `save`. So `save` has to
  * replace. It used to append, which duplicated the row on every one of those and left `getById` finding the stale copy
  * first, because it is a `find` over the same buffer.
  *
  * The hexagon cannot catch that: its in-memory fakes all upsert, so they encode the contract the port states rather
  * than the behaviour the adapter had. This suite is the only place the file-backed one is exercised at all.
  *
  * Every case uses a port of its own, because the port is what names the file.
  */
class FileContactRepositorySpec extends PlaySpec {

  private def onPort(port: Int)(check: FileContactRepository => Unit): Unit =
    val file = File(s"./.devDBs/contacts$port.ser")
    File("./.devDBs").mkdirs()
    file.delete()
    try check(FileContactRepository(Configuration("http.port" -> port)))
    finally file.delete()

  private def contact(name: String, id: UUID = UUID.randomUUID()): Contact = Contact(
    id = id,
    pseudonym = name,
    verifyKey = Array.fill(32)(0x01.toByte),
    encKey = Array.fill(32)(0x02.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = Some(Instant.now()),
    addedAt = Instant.now()
  )

  "FileContactRepository" should {

    "replace a contact it already holds rather than adding a second one" in {
      onPort(19011) { repository =>
        val alice = contact("Alice")
        repository.save(alice)
        repository.save(alice.copy(nickname = Some("Ali")))
        repository.getAll().size mustBe 1
        repository.getAll().head.nickname mustBe Some("Ali")
      }
    }

    // The half that made an update look lost as well as duplicated: getById is a find, so an appended
    // copy never won - the caller kept reading the version it had just replaced.
    "report the version it was last given, not the one that was replaced" in {
      onPort(19012) { repository =>
        val alice = contact("Alice")
        repository.save(alice)
        repository.save(alice.copy(nickname = Some("Ali")))
        repository.getById(alice.id).flatMap(_.nickname) mustBe Some("Ali")
        repository.getByVerifyKey(alice.verifyKey).flatMap(_.nickname) mustBe Some("Ali")
      }
    }

    "still hold one contact after a restart" in {
      onPort(19013) { repository =>
        val alice = contact("Alice")
        repository.save(alice)
        repository.save(alice.copy(nickname = Some("Ali")))
        val reopened = FileContactRepository(Configuration("http.port" -> 19013))
        reopened.getAll().size mustBe 1
        reopened.getAll().head.nickname mustBe Some("Ali")
      }
    }

    // Guards the fix against over-correcting into a single-slot store.
    "keep two different contacts apart" in {
      onPort(19014) { repository =>
        repository.save(contact("Alice"))
        repository.save(contact("Bob"))
        repository.getAll().map(_.pseudonym) must contain allOf ("Alice", "Bob")
        repository.getAll().size mustBe 2
      }
    }
  }
}
