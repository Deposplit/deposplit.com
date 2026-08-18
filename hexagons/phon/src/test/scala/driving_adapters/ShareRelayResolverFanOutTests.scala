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

package driving_adapters

import driven_ports.ShareRelay
import driven_ports.ShareRelayResolver
import value_objects.svo.*

import java.time.Instant
import java.util.UUID

/** BYOR proof-of-architecture: a device's contacts may point at different relays, and fan-out
  * methods (`syncInbox` etc.) must poll every distinct one, merge results, and not let one
  * unreachable relay blank out results from the others. See deposplit.com/CLAUDE.md's BYOR
  * section and `ShareService.allRelays`.
  */
private class TwoRelayResolver(default: ShareRelay, byorUrl: String, byor: ShareRelay) extends ShareRelayResolver:
  override def resolve(relayBaseUrl: Option[String]): ShareRelay = relayBaseUrl match
    case None                  => default
    case Some(u) if u == byorUrl => byor
    case Some(other)           => throw IllegalArgumentException(s"no fixture relay for $other")

class ShareRelayResolverFanOutTests extends munit.FunSuite:

  private val aliceKeys = TestKeyPair.generate()
  private val charlieKeys = TestKeyPair.generate()
  private val byorUrl = "http://byor.example:9000"

  private val aliceContact = Contact(
    id = UUID.randomUUID(),
    pseudonym = "alice",
    edPublicKey = aliceKeys.publicKey,
    xPublicKey = Array.fill(32)(0x01.toByte),
    verificationLevel = VerificationLevel.VeryHigh,
    verifiedAt = None,
    addedAt = Instant.now(),
    relayBaseUrl = None
  )
  private val charlieContact = aliceContact.copy(
    id = UUID.randomUUID(),
    pseudonym = "charlie",
    edPublicKey = charlieKeys.publicKey,
    relayBaseUrl = Some(byorUrl)
  )

  private def depositRow(id: UUID, senderKeys: TestKeyPair, recipientKey: Array[Byte]): ShareRequest =
    val secretId = UUID.randomUUID()
    val label = "fan-out test"
    val createdAt = Instant.now()
    val ciphertext = Array[Byte](9, 9, 9)
    val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.Deposit, recipientKey, label, createdAt, None, Some(ciphertext), Some(2), Some(3))
    ShareRequest(
      id = id,
      secretId = secretId,
      senderKey = senderKeys.publicKey,
      recipientKey = recipientKey,
      label = label,
      secretCreatedAt = createdAt,
      transactionType = ShareTransactionType.Deposit,
      state = ShareRequestState.Pending,
      shareId = None,
      requestedAt = Instant.now(),
      respondedAt = None,
      ciphertext = Some(ciphertext),
      k = Some(2),
      n = Some(3),
      senderSignature = senderKeys.sign(canon),
      recipientSignature = None
    )

  test("syncInbox polls both the default relay and a contact's BYOR relay, merging results") {
    val defaultRelay = FakeShareRelay()
    val byorRelay = FakeShareRelay()
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val contactRepo = FakeContactRepository(List(aliceContact, charlieContact))
    val svc = ShareService(
      relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = FakeShareMetadataRepository(),
      secretRepository = FakeSecretRepository(),
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      keyConflictRepository = FakeKeyConflictRepository(),
      retainedDepositRepository = FakeRetainedDepositRepository(),
      identity = bobIdentity
    )

    val fromAliceOnDefault = depositRow(UUID.randomUUID(), aliceKeys, bobIdentity.edPublicKey())
    val fromCharlieOnByor = depositRow(UUID.randomUUID(), charlieKeys, bobIdentity.edPublicKey())
    defaultRelay.pending = List(fromAliceOnDefault)
    defaultRelay.byId = Map(fromAliceOnDefault.id -> fromAliceOnDefault)
    byorRelay.pending = List(fromCharlieOnByor)
    byorRelay.byId = Map(fromCharlieOnByor.id -> fromCharlieOnByor)

    svc.syncInbox()

    assertEquals(defaultRelay.respondCalls, List(fromAliceOnDefault.id))
    assertEquals(byorRelay.respondCalls, List(fromCharlieOnByor.id))
    assertEquals(shareRepo.getAll().map(_.id).toSet, Set(fromAliceOnDefault.id, fromCharlieOnByor.id))
  }

  test("syncInbox still processes the reachable relay when the other is unreachable") {
    val defaultRelay = FakeShareRelay()
    val byorRelay = FakeShareRelay(unreachable = true)
    val bobIdentity = IdentityService(InMemoryForgettableIdentityStore())
    bobIdentity.register("bob")
    val shareRepo = FakeShareRepository()
    val contactRepo = FakeContactRepository(List(aliceContact, charlieContact))
    val svc = ShareService(
      relayResolver = TwoRelayResolver(defaultRelay, byorUrl, byorRelay),
      encryption = NoOpShareEncryption,
      shareRepository = shareRepo,
      shareMetadataRepository = FakeShareMetadataRepository(),
      secretRepository = FakeSecretRepository(),
      contactRepository = contactRepo,
      contactManagement = ContactService(contactRepo),
      keyConflictRepository = FakeKeyConflictRepository(),
      retainedDepositRepository = FakeRetainedDepositRepository(),
      identity = bobIdentity
    )

    val fromAliceOnDefault = depositRow(UUID.randomUUID(), aliceKeys, bobIdentity.edPublicKey())
    defaultRelay.pending = List(fromAliceOnDefault)
    defaultRelay.byId = Map(fromAliceOnDefault.id -> fromAliceOnDefault)

    svc.syncInbox()

    assertEquals(defaultRelay.respondCalls, List(fromAliceOnDefault.id))
    assertEquals(shareRepo.getAll().map(_.id), List(fromAliceOnDefault.id))
  }
