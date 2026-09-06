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
import value_objects.svo.RegenerateIdentityResult

import scala.util.Try

/** The identity itself: registering one, showing it to other phones, and the settings that hang off it.
  *
  * There is no keys-lost screen, unlike the mobile apps. `IdentityIntegrity` says why: phon keeps its keys and its
  * state in the same files, so the two cannot come apart, and `KeysLost` is unreachable by construction. A screen for a
  * state that cannot happen would be dead code.
  */
@Singleton
class PhonyPhoneController @Inject() (
    val controllerComponents: ControllerComponents,
    override protected val identity: ForgettableIdentity,
    override protected val contactManagement: ContactManagement,
    override protected val shareManagement: ShareManagement,
    override protected val relaySettings: RelaySettings
) extends PhonSupport,
      Logging:

  def register() = Action { implicit request: Request[AnyContent] =>
    PhonForms.pseudonymForm
      .bindFromRequest()
      .fold(
        withErrors => BadRequest(views.html.Phon.signIn(withErrors)),
        record => {
          identity.register(record.pseudonym.strip())
          Redirect(routes.HomeController.distributed())
        }
      )
  }

  /** The reset button a test phone needs and a real one has no equivalent of. Redirects rather than swapping, because
    * what comes back is the sign-in gate, not a screen.
    */
  def unregister() = Action { implicit request: Request[AnyContent] =>
    identity.unregister()
    NoContent.withHeaders("HX-Redirect" -> routes.HomeController.distributed().url)
  }

  def qrCode() = Action { implicit request: Request[AnyContent] =>
    registered {
      render(
        shellFor("phon.title.qr", back = Some(routes.HomeController.distributed())),
        views.html.Phon.qrCode(payload)
      )
    }
  }

  /** Rendered on demand rather than with the screen: generating it costs nothing here, but the same button on a real
    * phone is what makes the code appear, and phon is a teaching tool before it is a convenience.
    */
  def qrImage() = Action { implicit request: Request[AnyContent] =>
    payload match
      case None            => Conflict
      case Some(qrPayload) =>
        val bitMatrix = QRCodeWriter().encode(QrPayload.encode(qrPayload), BarcodeFormat.QR_CODE, 256, 256)
        val image = MatrixToImageWriter.toBufferedImage(bitMatrix)
        val bytes = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "PNG", bytes)
        val base64 = java.util.Base64.getEncoder.encodeToString(bytes.toByteArray)
        Ok(s"""<img class="img-fluid" alt="" src="data:image/png;base64,$base64">""").as("text/html")
  }

  def settings() = Action { implicit request: Request[AnyContent] =>
    registered(
      renderSettings(PhonForms.relayForm.fill(PhonForms.RelayRecord(Some(relaySettings.defaultRelayBaseUrl()))))
    )
  }

  def saveRelay() = Action { implicit request: Request[AnyContent] =>
    registered {
      PhonForms.relayForm
        .bindFromRequest()
        .fold(
          withErrors => renderSettings(withErrors),
          record => {
            relaySettings.setDefaultRelayBaseUrl(record.relayBaseUrl)
            renderSettings(
              PhonForms.relayForm.fill(PhonForms.RelayRecord(Some(relaySettings.defaultRelayBaseUrl()))),
              notice = Some("phon.settings.relaySaved")
            )
          }
        )
    }
  }

  /** Rotating on purpose, while the old keys are still in hand — not the same thing as recovering from having lost
    * them. Every contact is told, signed by the identity being replaced, before the new one is activated.
    */
  def regenerateIdentity() = Action { implicit request: Request[AnyContent] =>
    registered {
      val outcome = Try(shareManagement.regenerateIdentity()).toOption
      renderSettings(
        PhonForms.relayForm.fill(PhonForms.RelayRecord(Some(relaySettings.defaultRelayBaseUrl()))),
        regenerated = outcome
      )
    }
  }

  private def renderSettings(
      relayForm: play.api.data.Form[PhonForms.RelayRecord],
      notice: Option[String] = None,
      regenerated: Option[RegenerateIdentityResult] = None
  )(using request: Request[AnyContent]) =
    render(
      shellFor("phon.title.settings", back = Some(routes.HomeController.distributed())),
      views.html.Phon.settings(
        relayForm,
        identity.pseudonym(),
        identity.identityCreatedAt(),
        contactManagement.listContacts().size,
        notice,
        regenerated
      )
    )

  private def payload: Option[QrPayload] =
    identity
      .verifyKey()
      .zip(identity.encKey())
      .map((verifyKey, encKey) =>
        QrPayload(identity.pseudonym(), verifyKey, encKey, Some(relaySettings.defaultRelayBaseUrl()))
      )
