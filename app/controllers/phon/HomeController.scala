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

package controllers.phon

import driven_ports.RelaySettings
import driving_ports.ContactManagement
import driving_ports.ForgettableIdentity
import driving_ports.ShareManagement
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Logging
import play.api.mvc.AnyContent
import play.api.mvc.ControllerComponents
import play.api.mvc.Request

import scala.util.Try

/** The three tabs, and the second-phase sync each of them asks for once it has rendered.
  *
  * Splitting local rendering from relay traffic is not an optimisation: `syncInbox`, `syncDistributed`,
  * `listSentRequests` and `listPendingRequests` all block on the network, and a phone whose relay is down must still
  * show what it knows. So the screen renders from local storage, and the page then fetches the synced version over
  * itself - the same two-phase load both mobile home view models do, minus the coroutines.
  */
@Singleton
class HomeController @Inject() (
    val controllerComponents: ControllerComponents,
    override protected val identity: ForgettableIdentity,
    override protected val contactManagement: ContactManagement,
    override protected val shareManagement: ShareManagement,
    override protected val relaySettings: RelaySettings
) extends PhonSupport,
      Logging:

  def index() = Action { implicit request: Request[AnyContent] =>
    registered(Redirect(routes.HomeController.distributed(), TEMPORARY_REDIRECT))
  }

  // ── Distributed ───────────────────────────────────────────────────────────────────────────

  def distributed() = Action { implicit request: Request[AnyContent] =>
    registered(renderDistributed(syncPending = true, unreachable = Set.empty))
  }

  def syncDistributed() = Action { implicit request: Request[AnyContent] =>
    registered {
      val report = Try(shareManagement.syncDistributed()).toOption
      renderDistributed(syncPending = false, unreachable = report.fold(Set.empty)(_.unreachableRelays))
    }
  }

  private def renderDistributed(syncPending: Boolean, unreachable: Set[String])(using
      request: Request[AnyContent]
  ) =
    // Requests are a relay read, so the first pass groups without them: the cards render with their
    // holders and health, and the per-holder request state fills in a moment later.
    val sent = if syncPending then None else Try(shareManagement.listSentRequests()).toOption
    val groups = PhonViewModels.secretGroups(
      shareManagement.listSecrets(),
      shareManagement.listDistributed(),
      sent.fold(Nil)(_.items),
      contactManagement.listContacts()
    )
    render(
      shellFor(
        "phon.title.distributed",
        tab = Some(PhonTab.Distributed),
        unreachableRelays = unreachable ++ sent.fold(Set.empty)(_.unreachableRelays)
      ),
      views.html.Phon.distributed(groups, syncPending)
    )

  // ── Held ──────────────────────────────────────────────────────────────────────────────────

  def held(sort: String) = Action { implicit request: Request[AnyContent] =>
    registered(renderHeld(HeldSortOrder.fromWire(sort), syncPending = true, unreachable = Set.empty))
  }

  def syncHeld(sort: String) = Action { implicit request: Request[AnyContent] =>
    registered {
      val report = Try(shareManagement.syncInbox()).toOption
      renderHeld(
        HeldSortOrder.fromWire(sort),
        syncPending = false,
        unreachable = report.fold(Set.empty)(_.unreachableRelays)
      )
    }
  }

  private def renderHeld(order: HeldSortOrder, syncPending: Boolean, unreachable: Set[String])(using
      request: Request[AnyContent]
  ) =
    val rows = PhonViewModels.heldRows(shareManagement.listHeld(), contactManagement.listContacts(), order)
    render(
      shellFor("phon.title.held", tab = Some(PhonTab.Held), unreachableRelays = unreachable),
      views.html.Phon.held(rows, order, syncPending)
    )

  // ── Requests ──────────────────────────────────────────────────────────────────────────────

  def requests() = Action { implicit request: Request[AnyContent] =>
    registered(renderRequests(syncPending = true))
  }

  def syncRequests() = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.syncInbox())
      renderRequests(syncPending = false)
    }
  }

  private def renderRequests(syncPending: Boolean)(using request: Request[AnyContent]) =
    val contacts = contactManagement.listContacts()
    // Key conflicts are local and durable — captured when a rotation notice was refused, kept because the relay may
    // lose its own state at any moment — so they render in the first pass, above the requests they explain.
    val conflicts = PhonViewModels.keyConflictRows(shareManagement.listKeyConflicts(), contacts)
    // A pending request exists only on the relay, so what the relays that answered returned is shown, and each one that
    // did not is named rather than passed off as having nothing to ask.
    val pending = if syncPending then None else Try(shareManagement.listPendingRequests()).toOption
    val rows = PhonViewModels.requestRows(
      pending.fold(Nil)(_.items),
      contacts,
      shareManagement.listHeld(),
      java.time.Instant.now()
    )
    render(
      shellFor(
        "phon.title.requests",
        tab = Some(PhonTab.Requests),
        unreachableRelays = pending.fold(Set.empty)(_.unreachableRelays),
        relayWarningKey = "phon.advisory.relayUnreachableRequests"
      ),
      views.html.Phon.requests(conflicts, rows, syncPending)
    )
