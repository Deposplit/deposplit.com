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
import value_objects.svo.ReconstructionResult
import value_objects.svo.SecretLimits
import value_objects.svo.ShareTransactionType

import java.nio.file.Files
import java.util.UUID
import scala.util.Try

/** Secrets this device split and handed out: depositing one, watching what its holders do, asking for it back, and
  * putting it back together.
  */
@Singleton
class SecretsController @Inject() (
    val controllerComponents: ControllerComponents,
    override protected val identity: ForgettableIdentity,
    override protected val contactManagement: ContactManagement,
    override protected val shareManagement: ShareManagement,
    override protected val relaySettings: RelaySettings
) extends PhonSupport,
      Logging:

  def depositForm() = Action { implicit request: Request[AnyContent] =>
    registered {
      render(
        shellFor("phon.title.deposit", back = Some(routes.HomeController.distributed())),
        views.html.Phon.deposit(PhonForms.depositForm, contactManagement.listContacts())
      )
    }
  }

  def deposit() = Action { implicit request: Request[AnyContent] =>
    registered {
      PhonForms.depositForm
        .bindFromRequest()
        .fold(
          withErrors =>
            render(
              shellFor("phon.title.deposit", back = Some(routes.HomeController.distributed())),
              views.html.Phon.deposit(withErrors, contactManagement.listContacts()),
              BadRequest
            ),
          record =>
            PhonForms.depositPayload(record.secret, uploadedBytes) match
              case Left(problem)            => depositFailed(messagesApi.preferred(request)(problem.messageKey))
              case Right((bytes, mimeType)) =>
                val chosen = record.contacts.toSet.map(UUID.fromString)
                Try(
                  shareManagement.deposit(
                    bytes,
                    record.label.strip(),
                    contactManagement.listContacts().filter(contact => chosen.contains(contact.id)),
                    record.k,
                    mimeType
                  )
                ).fold(
                  // The size cap lives in the hexagon and throws, so an oversized secret arrives here
                  // as a message rather than as a rule this screen has to know.
                  failure => depositFailed(Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName)),
                  _ => goTo(routes.HomeController.distributed())
                )
        )
    }
  }

  /** Read only once the size is known to be sane. The hexagon's cap is still the rule — this is a guard against pulling
    * an arbitrarily large upload off disk to find out, which is a question `fileSize` already answers.
    */
  private def uploadedBytes(using request: Request[AnyContent]): Option[Array[Byte]] =
    request.body.asMultipartFormData
      .flatMap(_.file("secretFile"))
      .filter(_.fileSize > 0)
      .filter(_.fileSize <= SecretLimits.MaxSecretBytes)
      .map(part => Files.readAllBytes(part.ref.path))

  private def depositFailed(message: String)(using request: Request[AnyContent]) =
    render(
      shellFor("phon.title.deposit", back = Some(routes.HomeController.distributed())),
      views.html.Phon
        .deposit(PhonForms.depositForm.bindFromRequest().withGlobalError(message), contactManagement.listContacts()),
      BadRequest
    )

  /** A secret's own screen: its holders, and the three actions that operate on the whole secret rather than on any one
    * holder — asking for copies, putting it back together, and letting the collected copies go again.
    */
  def secretDetail(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered(renderSecretDetail(secretId, reconstruction = None))
  }

  def shareDetail(shareId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered(renderShareDetail(shareId))
  }

  /** Opens one request of one kind against one holder. The kind rides in the body rather than the path because it is a
    * choice made on the screen, not a different resource.
    */
  def openRequest(shareId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      PhonForms.openRequestForm
        .bindFromRequest()
        .fold(
          _ => renderShareDetail(shareId),
          record => {
            ShareTransactionType
              .fromWire(record.transactionType)
              .foreach(kind => Try(shareManagement.openRequest(shareId, kind)))
            renderShareDetail(shareId)
          }
        )
    }
  }

  /** Opens a retrieval against every holder that lacks a live one, in one press. Still worth pressing once k copies are
    * in: a surplus is what lets reconstruct cross-check the shares it already has.
    */
  def requestAll(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.requestAll(secretId))
      renderSecretDetail(secretId, reconstruction = None)
    }
  }

  /** A pure read: it collects the approved shares and puts them together, and tears nothing down. Letting the copies go
    * again is `clearCollected`, and destroying the secret altogether is `destroy` — each its own deliberate act.
    */
  def reconstruct(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      val outcome = Try(shareManagement.reconstruct(secretId))
      renderSecretDetail(secretId, reconstruction = Some(outcome))
    }
  }

  /** Hands back the copies collected from holders — and any ask still waiting for an answer — without touching the
    * split itself. The holders keep their shares, so the secret can be asked for again.
    */
  def clearCollected(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.clearCollectedShares(secretId))
      renderSecretDetail(secretId, reconstruction = None)
    }
  }

  def destroy(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.destroySecret(secretId))
      goTo(routes.HomeController.distributed())
    }
  }

  /** The escape hatch for a Destroying secret whose holders will never all answer — a permanently dark phone, which in
    * a teaching session is simply one that was closed.
    */
  def forceForget(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      Try(shareManagement.forceForgetSecret(secretId))
      goTo(routes.HomeController.distributed())
    }
  }

  /** Reconstruct, then re-split to a fresh set of holders. One route, four phases, as on both platforms — the wizard is
    * a state machine over what has already happened, not four separate screens.
    */
  def repair(secretId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      groupFor(secretId) match
        case None        => NotFound
        case Some(group) =>
          render(
            shellFor("phon.title.repair", back = Some(routes.SecretsController.secretDetail(secretId))),
            views.html.Phon.repair(group, contactManagement.listContacts(), PhonForms.depositForm)
          )
    }
  }

  private def groupFor(secretId: UUID) =
    PhonViewModels
      .secretGroups(
        shareManagement.listSecrets(),
        shareManagement.listDistributed(),
        Try(shareManagement.listSentRequests()).getOrElse(Nil),
        contactManagement.listContacts()
      )
      .find(_.secret.id == secretId)

  private def renderSecretDetail(secretId: UUID, reconstruction: Option[Try[ReconstructionResult]])(using
      request: Request[AnyContent]
  ) =
    groupFor(secretId) match
      case None        => goTo(routes.HomeController.distributed())
      case Some(group) =>
        render(
          shellFor("phon.title.secretDetail", back = Some(routes.HomeController.distributed())),
          views.html.Phon.secretDetail(group, reconstruction)
        )

  /** Back goes to the secret, not to the tab: a holder's screen is reached through the secret they hold a piece of. */
  private def renderShareDetail(shareId: UUID)(using request: Request[AnyContent]) =
    shareManagement.listDistributed().find(_.id == shareId) match
      case None           => NotFound
      case Some(metadata) =>
        groupFor(metadata.secretId) match
          case None        => NotFound
          case Some(group) =>
            group.holders.find(_.shareId == shareId) match
              case None         => NotFound
              case Some(holder) =>
                render(
                  shellFor(
                    "phon.title.shareDetail",
                    back = Some(routes.SecretsController.secretDetail(metadata.secretId))
                  ),
                  views.html.Phon.shareDetail(group, holder)
                )
