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

import driven_ports.persistence.CustodyHeartbeatRepository
import value_objects.*

import java.util.UUID

// ---------------------------------------------------------------------------
// In-memory test double — upserts by (holderKey, ownerKey), same latest-wins semantics as the
// real Anorm repository.
// ---------------------------------------------------------------------------
class InMemoryCustodyHeartbeatRepository extends CustodyHeartbeatRepository:

  private var heartbeats: Seq[CustodyHeartbeat] = Seq.empty

  private def sameKey(a: PublicKey, b: PublicKey): Boolean = a.toBase64Url == b.toBase64Url

  override def upsertHeartbeat(heartbeat: CustodyHeartbeat): CustodyHeartbeat =
    heartbeats = heartbeats.filterNot(h =>
      sameKey(h.holderKey, heartbeat.holderKey) && sameKey(h.ownerKey, heartbeat.ownerKey)
    ) :+ heartbeat
    heartbeat

  override def getHeartbeatsForOwner(ownerKey: PublicKey): Seq[CustodyHeartbeat] =
    heartbeats.filter(h => sameKey(h.ownerKey, ownerKey))

// ---------------------------------------------------------------------------
// Tests — reuses ShareRequestsServiceTests's Fixtures (same package) for keypairs.
// ---------------------------------------------------------------------------
class CustodyHeartbeatsServiceTests extends munit.FunSuite:

  import Fixtures.*

  private def newService(): (InMemoryCustodyHeartbeatRepository, CustodyHeartbeatsService) =
    val repo = InMemoryCustodyHeartbeatRepository()
    (repo, CustodyHeartbeatsService(repo))

  /** Signs and pushes a heartbeat — the signing counterpart of `service.pushHeartbeat`. */
  private def push(
      service: CustodyHeartbeatsService,
      holder: PublicKey,
      owner: PublicKey,
      secretIds: Seq[UUID] = Seq.empty,
      optedOut: Boolean = false
  ): Either[Error, CustodyHeartbeat] =
    val sig = signerFor(holder).sign(PayloadCanonical.forHeartbeat(owner, secretIds, optedOut))
    service.pushHeartbeat(holder, owner, secretIds, optedOut, sig)

  test("pushHeartbeat with a valid signature succeeds and stores the row") {
    val (repo, service) = newService()
    val secretIds = Seq(UUID.randomUUID(), UUID.randomUUID())
    val result = push(service, bob, alice, secretIds)
    assert(result.isRight)
    val heartbeat = result.getOrElse(fail("not right"))
    assertEquals(heartbeat.holderKey.toBase64Url, bob.toBase64Url)
    assertEquals(heartbeat.ownerKey.toBase64Url, alice.toBase64Url)
    assertEquals(heartbeat.secretIds.toSet, secretIds.toSet)
    assertEquals(heartbeat.optedOut, false)
    assertEquals(repo.getHeartbeatsForOwner(alice).size, 1)
  }

  test("pushHeartbeat returns BadRequest when the signature doesn't verify against holderKey") {
    val (_, service) = newService()
    // Signed by alice but claiming to be bob's heartbeat — signature won't verify against bob.
    val badSig = signerFor(alice).sign(PayloadCanonical.forHeartbeat(alice, Seq.empty, false))
    val result = service.pushHeartbeat(bob, alice, Seq.empty, false, badSig)
    assertEquals(result, Left(Error.BadRequest))
  }

  test("pushHeartbeat upserts — a second push from the same holder to the same owner replaces the first") {
    val (repo, service) = newService()
    val firstSecrets = Seq(UUID.randomUUID())
    val secondSecrets = Seq(UUID.randomUUID(), UUID.randomUUID())
    push(service, bob, alice, firstSecrets)
    val second = push(service, bob, alice, secondSecrets).getOrElse(fail("push failed"))

    val forAlice = repo.getHeartbeatsForOwner(alice)
    assertEquals(forAlice.size, 1)
    assertEquals(forAlice.head.id, second.id)
    assertEquals(forAlice.head.secretIds.toSet, secondSecrets.toSet)
  }

  test("pushHeartbeat with optedOut true is stored and still upserts against a prior non-opted-out heartbeat") {
    val (repo, service) = newService()
    push(service, bob, alice, Seq(UUID.randomUUID()))
    push(service, bob, alice, Seq.empty, optedOut = true)

    val forAlice = repo.getHeartbeatsForOwner(alice)
    assertEquals(forAlice.size, 1)
    assertEquals(forAlice.head.optedOut, true)
  }

  test("listHeartbeats returns only rows addressed to the caller, one per holder") {
    val (_, service) = newService()
    push(service, bob, alice)
    push(service, charlie, alice)
    push(service, charlie, bob)
    val forAlice = service.listHeartbeats(alice).getOrElse(fail("not right"))
    assertEquals(forAlice.map(_.holderKey.toBase64Url).toSet, Set(bob.toBase64Url, charlie.toBase64Url))
  }

  test("listHeartbeats returns an empty list for an owner with no heartbeats") {
    val (_, service) = newService()
    assertEquals(service.listHeartbeats(alice), Right(Seq.empty))
  }
