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

import com.google.zxing.BarcodeFormat
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import driven_ports.ForgettableIdentityStore
import driving_ports.ContactManagement
import driving_ports.ForgettableIdentity
import driving_ports.ShareManagement
import jakarta.inject.Inject
import jakarta.inject.Singleton
import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.mvc.AnyContent
import play.api.mvc.BaseController
import play.api.mvc.ControllerComponents
import play.api.mvc.Cookie
import play.api.mvc.DiscardingCookie
import play.api.mvc.Request

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

class PhonyPhoneController @Inject() (
    val controllerComponents: ControllerComponents,
    val identity: ForgettableIdentity,
    val contactManagement: ContactManagement,
    val shareManagement: ShareManagement
) extends BaseController,
      I18nSupport,
      Logging:

  def createPseudonym() = Action { implicit request: Request[AnyContent] =>
    logger.debug("creating pseudonym …")
    pseudonymForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          logger.debug("… failed to create pseudonym")
          BadRequest(views.html.Phon.pseudonymForm(formWithErrors))
        },
        pseudonymRecord => {
          identity.register(pseudonymRecord.pseudonym)
          logger.debug("… created pseudonym")
          Redirect(routes.PhonyPhoneController.readPseudonym())
            .flashing("success" -> "createdPseudonym")
        }
      )
  }

  def readPseudonym() = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .phonyPhone(
            QrPayload(identity.pseudonym(), identity.edPublicKey(), identity.xPublicKey()),
            contactManagement,
            contactForm
          )
      )
    else Ok(views.html.Phon.pseudonymForm(pseudonymForm))
    end if
  }

  def deletePseudonym() = Action { implicit request: Request[AnyContent] =>
    identity.unregister()
    NoContent.withHeaders("HX-Redirect" -> routes.PhonyPhoneController.readPseudonym().absoluteURL())
  }

  def readQrCode() = Action { implicit request: Request[AnyContent] =>
    if !identity.isRegistered() then Conflict
    else
      val payload =
        QrPayload.encode(identity.pseudonym(), identity.edPublicKey(), identity.xPublicKey())
      val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256)
      val image = MatrixToImageWriter.toBufferedImage(bitMatrix)
      val baos = java.io.ByteArrayOutputStream()
      javax.imageio.ImageIO.write(image, "PNG", baos)
      val base64 = java.util.Base64.getEncoder.encodeToString(baos.toByteArray)
      Ok(s"""<img src="data:image/png;base64,$base64">""").as("text/html")
    end if
  }

  def createContact() = Action { implicit request: Request[AnyContent] =>
    logger.debug("creating contact …")
    contactForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          logger.debug("… failed to create contact")
          BadRequest(
            views.html.Phon.contactsTable(
              contactManagement,
              formWithErrors
            )
          )
        },
        contactRecord => {
          contactManagement
            .addManually(
              contactRecord.pseudonym,
              QrPayload.decodeKey(contactRecord.signKey),
              QrPayload.decodeKey(contactRecord.transKey)
            )
          logger.debug("… created contact")
          Redirect(routes.PhonyPhoneController.readContacts())
            .flashing("success" -> "createdContact")
        }
      )
  }

  def readContacts() = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .contactsTable(
            contactManagement,
            contactForm
          )
      )
    else Ok(views.html.Phon.pseudonymForm(pseudonymForm))
    end if
  }

  def deleteContact(contactId: UUID) = Action { implicit request: Request[AnyContent] =>
    contactManagement.deleteContact(contactId)
    /* NoContent <- https://four.htmx.org/reference/attributes/hx-delete#notes -> */
    Ok
  }

  def getSecretSharingForm = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .secretSharingForm(
            secretSharingForm,
            contactManagement.listContacts()
          )
      )
    else Redirect(routes.PhonyPhoneController.readPseudonym())
    end if
  }

  def createMySecret() = Action { implicit request: Request[AnyContent] =>
    logger.debug("sharing secret …")
    secretSharingForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          logger.debug("… failed to share secret")
          BadRequest(
            views.html.Phon.secretSharingForm(
              formWithErrors,
              contactManagement.listContacts()
            )
          )
        },
        secretSharingRecord => {
          val contactIds = secretSharingRecord.contacts.toSet.map(UUID.fromString(_))
          shareManagement.deposit(
            secretSharingRecord.secret.trim.getBytes(StandardCharsets.UTF_8),
            secretSharingRecord.label.trim,
            contactManagement.listContacts().filter(contact => contactIds.contains(contact.id)),
            secretSharingRecord.k
          )
          logger.debug("… shared secret")
          Redirect(routes.PhonyPhoneController.readPseudonym())
            .flashing("success" -> "sharedSecret")
        }
      )
  }

  def readMySecrets = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .mySecrets(
            shareManagement
          )
      )
    else Ok(views.html.Phon.pseudonymForm(pseudonymForm))
    end if
  }

  def createTheirShares() = Action { implicit request: Request[AnyContent] =>
    Try {
      shareManagement.syncInbox()
      Redirect(routes.PhonyPhoneController.readTheirShares())
        .withCookies(Cookie("latestInboxSync", Instant.now().toString))
        .flashing("success" -> "synchedInbox")
    }.getOrElse(InternalServerError)
  }

  def readTheirShares = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .theirShares(
            shareManagement
          )
      )
    else Ok(views.html.Phon.pseudonymForm(pseudonymForm))
    end if
  }

  def readPendingRequests = Action { implicit request: Request[AnyContent] =>
    if identity.isRegistered() then
      Ok(
        views.html.Phon
          .pendingRequests(
            shareManagement
          )
      )
    else Ok(views.html.Phon.pseudonymForm(pseudonymForm))
    end if
  }
