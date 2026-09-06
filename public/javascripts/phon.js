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

/*
 * Delegated rather than bound, because every screen arrives through an htmx swap: a handler
 * attached at load would be attached to markup that is about to be replaced.
 */

document.addEventListener("change", (event) => {
  if (event.target.id === "phonThemeSwitch") {
    const html = document.documentElement;
    html.setAttribute(
      "data-bs-theme",
      html.getAttribute("data-bs-theme") === "light" ? "dark" : "light"
    );
  }
});

/* Copying the payload is how a phony phone stands in for holding two handsets up to each other. */
document.addEventListener("click", (event) => {
  const button = event.target.closest("[data-phon-copy]");
  if (!button) return;
  const source = document.getElementById(button.getAttribute("data-phon-copy"));
  if (!source) return;
  navigator.clipboard.writeText(source.value ?? source.textContent).then(() => {
    const done = button.getAttribute("data-phon-copied");
    if (!done) return;
    const original = button.innerHTML;
    button.innerHTML = done;
    setTimeout(() => {
      button.innerHTML = original;
    }, 1500);
  });
});
