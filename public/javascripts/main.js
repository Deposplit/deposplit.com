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

const DARK = "dark";
const LIGHT = "light";

let bsTheme;
if (localStorage.getItem("bsTheme") !== null) {
  bsTheme = localStorage.getItem("bsTheme");
} else {
  bsTheme = matchMedia('(prefers-color-scheme: dark)').matches ? DARK : LIGHT;
  localStorage.setItem("bsTheme", bsTheme);
}
document.documentElement.dataset.bsTheme = bsTheme;

if (document.getElementById('bsThemeSwitch') !== null) {
  const bsThemeSwitch = document.getElementById('bsThemeSwitch');
  bsThemeSwitch.checked = bsTheme === DARK;
  bsThemeSwitch.ariaChecked = bsTheme === DARK;
  bsThemeSwitch.addEventListener('change', switchBootstrapTheme);
}

function switchBootstrapTheme() {
  bsTheme = bsTheme === LIGHT ? DARK : LIGHT;
  document.documentElement.dataset.bsTheme = bsTheme;
  localStorage.setItem("bsTheme", bsTheme);
}
