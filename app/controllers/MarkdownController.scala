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

package controllers

import jakarta.inject.Inject
import jakarta.inject.Singleton
import laika.api.MarkupParser
import laika.api.Renderer
import laika.ast.Document
import laika.ast.Header
import laika.ast.Text
import laika.format.Markdown
import play.api.Environment
import play.api.i18n.I18nSupport
import play.api.mvc.*

import scala.io.Codec
import scala.io.Source

case class MarkdownFilePath(sanitized: String)

object MarkdownFilePath {
  private val allowedPath = """[A-Za-z0-9][A-Za-z0-9/_-]*""".r

  /*
   * normalize() before the allowlist check — normalize first so foo/../bar becomes bar and passes the allowlist rather
  than being rejected on the .. before normalization resolves it. The startsWith("..") guard then catches anything that
  still tries to escape.
   * replace('\\', '/') — on Windows, Paths.get("foo\\bar").normalize() can produce backslash separators; the resource
  loader expects forward slashes.
   *- No dots in the allowlist — the .md extension is appended by the controller, so there's no legitimate reason for a
  dot in the incoming path segment. This also blocks .. from surviving in disguised forms.
   * No null-byte check needed — Paths.get throws InvalidPathException on null bytes on the JVM, which would propagate as
   a 500; if one would prefer a clean 400, one could add if (filePath.contains('\u0000')) before the Paths.get call.
   */
  private def sanitize(filePath: String): Either[String, String] = {
    val normalized = java.nio.file.Paths.get(filePath).normalize()
    if (normalized.isAbsolute || normalized.startsWith("..")) Left("invalid path")
    else {
      val s = normalized.toString.replace('\\', '/')
      if (allowedPath.matches(s)) Right(s) else Left("invalid path")
    }
  }

  implicit def pathBinder(implicit stringBinder: PathBindable[String]): PathBindable[MarkdownFilePath] =
    new PathBindable[MarkdownFilePath] {

      override def bind(key: String, value: String): Either[String, MarkdownFilePath] =
        stringBinder.bind(key, value).flatMap(sanitize(_).map(MarkdownFilePath(_)))

      override def unbind(key: String, markdownFilePath: MarkdownFilePath): String =
        markdownFilePath.sanitized
    }
}

@Singleton
class MarkdownController @Inject() (val controllerComponents: ControllerComponents, env: Environment)
    extends BaseController
    with I18nSupport {

  private val parser = MarkupParser.of(Markdown).using(Markdown.GitHubFlavor).build
  private val renderer = Renderer.of(laika.format.HTML).build

  private def getDocTitle(doc: Document) = doc.content
    .collect { case Header(1, content, _) =>
      content
        .collect { case Text(text, _) =>
          text
        }
        .mkString(" ")
    }
    .headOption
    .getOrElse("Markdown")

  def get(markdownFilePath: MarkdownFilePath) = Action { implicit request: Request[AnyContent] =>
    val language = request.lang.language
    val sanitizedMarkdownFilePath = markdownFilePath.sanitized
    Option(env.classLoader.getResourceAsStream(s"public/markdowns/$language/$sanitizedMarkdownFilePath.md"))
      .orElse(Option(env.classLoader.getResourceAsStream(s"public/markdowns/$sanitizedMarkdownFilePath.md")))
      .flatMap(is =>
        parser
          .parse(
            Source.fromInputStream(is)(Codec.UTF8).mkString
          ) // or use java.nio.Files, cf. Scala for the Impatient (§9.2) and https://horstmann.com/unblog/2023-04-09/index.html
          .flatMap(doc => renderer.render(doc).map((getDocTitle(doc), _)))
          .toOption
      )
      .map(titledHtml => Ok(views.html.markdown(titledHtml._1, titledHtml._2)))
      .getOrElse(NotFound)
  }

  /*
  private val transformer = Transformer
    .from(Markdown)
    .to(laika.format.HTML)
    .using(Markdown.GitHubFlavor)
    .build

  def get(unsanitizedMarkdownFilePath: String) = Action { implicit request: Request[AnyContent] =>
    val language = request.lang.language
    Option(env.classLoader.getResourceAsStream(s"public/markdowns/$language/$unsanitizedMarkdownFilePath.md"))
      .orElse(Option(env.classLoader.getResourceAsStream(s"public/markdowns/$unsanitizedMarkdownFilePath.md")))
      .flatMap(is => transformer.transform(Source.fromInputStream(is)(Codec.UTF8).mkString).toOption)
      .map(html => Ok(views.html.markdown("title", html)))
      .getOrElse(NotFound)
  }
   */
}
