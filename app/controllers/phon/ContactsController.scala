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
import play.api.mvc.Result
import value_objects.svo.CipherSuite

import java.util.UUID
import scala.util.Try

/** Contacts, and everything that can be done to one.
  *
  * phon has no camera, so the two ways in are pasting the payload a phone would have scanned and typing the keys out by
  * hand — the same two entries the iOS plus-menu offers, minus the lens. They are genuinely different acts, which is
  * why they reach different hexagon methods and offer different verification levels: `VeryHigh` means "we were in the
  * same room", so only the stand-in-for-a-scan path may claim it.
  */
@Singleton
class ContactsController @Inject() (
    val controllerComponents: ControllerComponents,
    override protected val identity: ForgettableIdentity,
    override protected val contactManagement: ContactManagement,
    override protected val shareManagement: ShareManagement,
    override protected val relaySettings: RelaySettings
) extends PhonSupport,
      Logging:

  def list() = Action { implicit request: Request[AnyContent] =>
    registered(renderList())
  }

  def addForm(mode: String) = Action { implicit request: Request[AnyContent] =>
    registered {
      render(
        shellFor("phon.title.addContact", back = Some(routes.ContactsController.list())),
        views.html.Phon.addContact(mode, PhonForms.payloadContactForm, PhonForms.manualContactForm)
      )
    }
  }

  def add() = Action { implicit request: Request[AnyContent] =>
    registered {
      val manual = request.body.asFormUrlEncoded.exists(_.contains("verifyKey"))
      if manual then addManually() else addFromPayload()
    }
  }

  private def addManually()(using request: Request[AnyContent]): Result =
    PhonForms.manualContactForm
      .bindFromRequest()
      .fold(
        withErrors => renderAddForm("manual", manualForm = withErrors),
        record =>
          Try(
            contactManagement.addManually(
              record.pseudonym.strip(),
              QrPayload.decodeKey(record.verifyKey.strip()),
              QrPayload.decodeKey(record.encKey.strip()),
              PhonForms.levelFrom(record.verificationLevel),
              record.relayBaseUrl.map(_.strip()).filter(_.nonEmpty),
              record.nickname
            )
          ).fold(
            failure => renderAddForm("manual", manualForm = withGlobalError(PhonForms.manualContactForm, failure)),
            _ => goTo(routes.ContactsController.list())
          )
      )

  private def addFromPayload()(using request: Request[AnyContent]): Result =
    PhonForms.payloadContactForm
      .bindFromRequest()
      .fold(
        withErrors => renderAddForm("payload", payloadForm = withErrors),
        record =>
          Try {
            val payload = QrPayload.decode(record.payload.strip())
            contactManagement.addFromQr(
              payload.pseudonym,
              QrPayload.decodeKey(payload.verifyKey),
              QrPayload.decodeKey(payload.encKey),
              CipherSuite.fromWire(payload.cipherSuite).getOrElse(CipherSuite.current),
              PhonForms.levelFrom(record.verificationLevel),
              payload.relay,
              record.nickname
            )
          }.fold(
            failure => renderAddForm("payload", payloadForm = withGlobalError(PhonForms.payloadContactForm, failure)),
            _ => goTo(routes.ContactsController.list())
          )
      )

  def relinkForm(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      contactManagement.listContacts().find(_.id == contactId) match
        case None          => NotFound
        case Some(contact) =>
          render(
            shellFor("phon.title.relink", back = Some(routes.ContactsController.list())),
            views.html.Phon.relinkContact(contact, PhonForms.relinkForm)
          )
    }
  }

  /** Updates in place, preserving the contact id: a relink is the same person presenting new keys, and minting a fresh
    * id would orphan every share this device already holds from them.
    */
  def relink(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      contactManagement.listContacts().find(_.id == contactId) match
        case None          => NotFound
        case Some(contact) =>
          PhonForms.relinkForm
            .bindFromRequest()
            .fold(
              withErrors =>
                render(
                  shellFor("phon.title.relink", back = Some(routes.ContactsController.list())),
                  views.html.Phon.relinkContact(contact, withErrors)
                ),
              record =>
                val keys = record.payload.map(_.strip()).filter(_.nonEmpty) match
                  case Some(raw) =>
                    Try {
                      val payload = QrPayload.decode(raw)
                      (
                        QrPayload.decodeKey(payload.verifyKey),
                        QrPayload.decodeKey(payload.encKey),
                        CipherSuite.fromWire(payload.cipherSuite)
                      )
                    }
                  case None =>
                    Try(
                      (
                        QrPayload.decodeKey(record.verifyKey.get.strip()),
                        QrPayload.decodeKey(record.encKey.get.strip()),
                        None
                      )
                    )
                keys
                  .flatMap((verifyKey, encKey, suite) =>
                    Try(
                      contactManagement.updateContact(
                        contactId,
                        Some(verifyKey),
                        Some(encKey),
                        suite,
                        Some(PhonForms.levelFrom(record.verificationLevel))
                      )
                    )
                  )
                  .fold(
                    failure =>
                      render(
                        shellFor("phon.title.relink", back = Some(routes.ContactsController.list())),
                        views.html.Phon.relinkContact(contact, withGlobalError(PhonForms.relinkForm, failure))
                      ),
                    _ => goTo(routes.ContactsController.list())
                  )
            )
    }
  }

  def rename(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      PhonForms.renameForm
        .bindFromRequest()
        .fold(
          _ => renderList(),
          record => {
            contactManagement.renameContact(contactId, record.nickname.map(_.strip()).filter(_.nonEmpty))
            renderList()
          }
        )
    }
  }

  /** The manual tick, for the contact who holds no share and sends nothing — and so can never produce the evidence that
    * would clear them on its own.
    */
  def markRelinked(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      contactManagement.markRelinked(contactId)
      renderList()
    }
  }

  def markKeyCompromised(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      contactManagement.markKeyCompromised(contactId)
      renderList()
    }
  }

  /** Low-stakes and reversible, unlike flagging a key, so it takes no confirmation — matching both mobile lists. */
  def toggleHeartbeat(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      contactManagement
        .listContacts()
        .find(_.id == contactId)
        .foreach(contact => shareManagement.setHeartbeatEmissionOptedOut(contactId, !contact.heartbeatEmissionOptedOut))
      renderList()
    }
  }

  def delete(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    registered {
      contactManagement.deleteContact(contactId)
      renderList()
    }
  }

  private def renderList()(using request: Request[AnyContent]) =
    render(
      shellFor("phon.title.contacts", back = Some(routes.HomeController.distributed())),
      views.html.Phon.contacts(
        PhonViewModels.contactRows(
          contactManagement.listContacts(),
          contactManagement.contactsAwaitingRelink(),
          shareManagement.listHeld()
        )
      )
    )

  private def renderAddForm(
      mode: String,
      payloadForm: play.api.data.Form[PhonForms.PayloadContactRecord] = PhonForms.payloadContactForm,
      manualForm: play.api.data.Form[PhonForms.ManualContactRecord] = PhonForms.manualContactForm
  )(using request: Request[AnyContent]) =
    render(
      shellFor("phon.title.addContact", back = Some(routes.ContactsController.list())),
      views.html.Phon.addContact(mode, payloadForm, manualForm),
      BadRequest
    )

  /** Key lengths and payload shape are checked by the hexagon and by the codec, not re-checked here — so what comes
    * back is an exception, and the screen says what it said rather than inventing a friendlier lie.
    */
  private def withGlobalError[A](form: play.api.data.Form[A], failure: Throwable)(using
      request: Request[AnyContent]
  ): play.api.data.Form[A] =
    form.bindFromRequest().withGlobalError(Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))
