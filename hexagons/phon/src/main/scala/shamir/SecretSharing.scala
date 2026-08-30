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

package shamir

import java.security.SecureRandom
import scala.collection.mutable

// The polynomial used is: x⁸ + x⁴ + x³ + x + 1
//
// Lookup tables from:
//   https://github.com/hashicorp/vault/blob/9d46671659cbfe7bbd3e78d1073dfb22936a4437/shamir/tables.go
//   http://www.samiam.org/galois.html
//
// 0xe5 (229) is used as the generator.

object SecretSharing:

  // Provides log(X)/log(g) at each index X.
  private val logTable: Array[Int] = Array(
    0x00, 0xff, 0xc8, 0x08, 0x91, 0x10, 0xd0, 0x36, 0x5a, 0x3e, 0xd8, 0x43, 0x99, 0x77, 0xfe, 0x18, 0x23, 0x20, 0x07,
    0x70, 0xa1, 0x6c, 0x0c, 0x7f, 0x62, 0x8b, 0x40, 0x46, 0xc7, 0x4b, 0xe0, 0x0e, 0xeb, 0x16, 0xe8, 0xad, 0xcf, 0xcd,
    0x39, 0x53, 0x6a, 0x27, 0x35, 0x93, 0xd4, 0x4e, 0x48, 0xc3, 0x2b, 0x79, 0x54, 0x28, 0x09, 0x78, 0x0f, 0x21, 0x90,
    0x87, 0x14, 0x2a, 0xa9, 0x9c, 0xd6, 0x74, 0xb4, 0x7c, 0xde, 0xed, 0xb1, 0x86, 0x76, 0xa4, 0x98, 0xe2, 0x96, 0x8f,
    0x02, 0x32, 0x1c, 0xc1, 0x33, 0xee, 0xef, 0x81, 0xfd, 0x30, 0x5c, 0x13, 0x9d, 0x29, 0x17, 0xc4, 0x11, 0x44, 0x8c,
    0x80, 0xf3, 0x73, 0x42, 0x1e, 0x1d, 0xb5, 0xf0, 0x12, 0xd1, 0x5b, 0x41, 0xa2, 0xd7, 0x2c, 0xe9, 0xd5, 0x59, 0xcb,
    0x50, 0xa8, 0xdc, 0xfc, 0xf2, 0x56, 0x72, 0xa6, 0x65, 0x2f, 0x9f, 0x9b, 0x3d, 0xba, 0x7d, 0xc2, 0x45, 0x82, 0xa7,
    0x57, 0xb6, 0xa3, 0x7a, 0x75, 0x4f, 0xae, 0x3f, 0x37, 0x6d, 0x47, 0x61, 0xbe, 0xab, 0xd3, 0x5f, 0xb0, 0x58, 0xaf,
    0xca, 0x5e, 0xfa, 0x85, 0xe4, 0x4d, 0x8a, 0x05, 0xfb, 0x60, 0xb7, 0x7b, 0xb8, 0x26, 0x4a, 0x67, 0xc6, 0x1a, 0xf8,
    0x69, 0x25, 0xb3, 0xdb, 0xbd, 0x66, 0xdd, 0xf1, 0xd2, 0xdf, 0x03, 0x8d, 0x34, 0xd9, 0x92, 0x0d, 0x63, 0x55, 0xaa,
    0x49, 0xec, 0xbc, 0x95, 0x3c, 0x84, 0x0b, 0xf5, 0xe6, 0xe7, 0xe5, 0xac, 0x7e, 0x6e, 0xb9, 0xf9, 0xda, 0x8e, 0x9a,
    0xc9, 0x24, 0xe1, 0x0a, 0x15, 0x6b, 0x3a, 0xa0, 0x51, 0xf4, 0xea, 0xb2, 0x97, 0x9e, 0x5d, 0x22, 0x88, 0x94, 0xce,
    0x19, 0x01, 0x71, 0x4c, 0xa5, 0xe3, 0xc5, 0x31, 0xbb, 0xcc, 0x1f, 0x2d, 0x3b, 0x52, 0x6f, 0xf6, 0x2e, 0x89, 0xf7,
    0xc0, 0x68, 0x1b, 0x64, 0x04, 0x06, 0xbf, 0x83, 0x38
  )

  // Provides the exponentiation value at each index X.
  private val expTable: Array[Int] = Array(
    0x01, 0xe5, 0x4c, 0xb5, 0xfb, 0x9f, 0xfc, 0x12, 0x03, 0x34, 0xd4, 0xc4, 0x16, 0xba, 0x1f, 0x36, 0x05, 0x5c, 0x67,
    0x57, 0x3a, 0xd5, 0x21, 0x5a, 0x0f, 0xe4, 0xa9, 0xf9, 0x4e, 0x64, 0x63, 0xee, 0x11, 0x37, 0xe0, 0x10, 0xd2, 0xac,
    0xa5, 0x29, 0x33, 0x59, 0x3b, 0x30, 0x6d, 0xef, 0xf4, 0x7b, 0x55, 0xeb, 0x4d, 0x50, 0xb7, 0x2a, 0x07, 0x8d, 0xff,
    0x26, 0xd7, 0xf0, 0xc2, 0x7e, 0x09, 0x8c, 0x1a, 0x6a, 0x62, 0x0b, 0x5d, 0x82, 0x1b, 0x8f, 0x2e, 0xbe, 0xa6, 0x1d,
    0xe7, 0x9d, 0x2d, 0x8a, 0x72, 0xd9, 0xf1, 0x27, 0x32, 0xbc, 0x77, 0x85, 0x96, 0x70, 0x08, 0x69, 0x56, 0xdf, 0x99,
    0x94, 0xa1, 0x90, 0x18, 0xbb, 0xfa, 0x7a, 0xb0, 0xa7, 0xf8, 0xab, 0x28, 0xd6, 0x15, 0x8e, 0xcb, 0xf2, 0x13, 0xe6,
    0x78, 0x61, 0x3f, 0x89, 0x46, 0x0d, 0x35, 0x31, 0x88, 0xa3, 0x41, 0x80, 0xca, 0x17, 0x5f, 0x53, 0x83, 0xfe, 0xc3,
    0x9b, 0x45, 0x39, 0xe1, 0xf5, 0x9e, 0x19, 0x5e, 0xb6, 0xcf, 0x4b, 0x38, 0x04, 0xb9, 0x2b, 0xe2, 0xc1, 0x4a, 0xdd,
    0x48, 0x0c, 0xd0, 0x7d, 0x3d, 0x58, 0xde, 0x7c, 0xd8, 0x14, 0x6b, 0x87, 0x47, 0xe8, 0x79, 0x84, 0x73, 0x3c, 0xbd,
    0x92, 0xc9, 0x23, 0x8b, 0x97, 0x95, 0x44, 0xdc, 0xad, 0x40, 0x65, 0x86, 0xa2, 0xa4, 0xcc, 0x7f, 0xec, 0xc0, 0xaf,
    0x91, 0xfd, 0xf7, 0x4f, 0x81, 0x2f, 0x5b, 0xea, 0xa8, 0x1c, 0x02, 0xd1, 0x98, 0x71, 0xed, 0x25, 0xe3, 0x24, 0x06,
    0x68, 0xb3, 0x93, 0x2c, 0x6f, 0x3e, 0x6c, 0x0a, 0xb8, 0xce, 0xae, 0x74, 0xb1, 0x42, 0xb4, 0x1e, 0xd3, 0x49, 0xe9,
    0x9c, 0xc8, 0xc6, 0xc7, 0x22, 0x6e, 0xdb, 0x20, 0xbf, 0x43, 0x51, 0x52, 0x66, 0xb2, 0x76, 0x60, 0xda, 0xc5, 0xf3,
    0xf6, 0xaa, 0xcd, 0x9a, 0xa0, 0x75, 0x54, 0x0e, 0x01
  )

  private val secureRandom = SecureRandom()

  // GF(2^8) addition is XOR (also serves as subtraction).
  private def add(a: Int, b: Int): Int = a ^ b

  // GF(2^8) multiplication using log/exp tables.
  private def mult(a: Int, b: Int): Int =
    if a == 0 || b == 0 then 0
    else expTable((logTable(a) + logTable(b)) % 255)

  // GF(2^8) division using log/exp tables.
  private def div(a: Int, b: Int): Int =
    require(b != 0, "cannot divide by zero")
    if a == 0 then 0
    else expTable((logTable(a) - logTable(b) + 255) % 255)

  // Evaluates a polynomial at x using Horner's method.
  private def evaluate(coefficients: Array[Int], x: Int, degree: Int): Int =
    require(x != 0, "cannot evaluate secret polynomial at zero")
    var result = coefficients(degree)
    for i <- (degree - 1) to 0 by -1 do result = add(mult(result, x), coefficients(i))
    result

  // Lagrange interpolation at a given x, using the provided sample points.
  private def interpolatePolynomial(xSamples: Array[Int], ySamples: Array[Int], x: Int): Int =
    val limit = xSamples.length
    var result = 0
    for i <- 0 until limit do
      var basis = 1
      for j <- 0 until limit do
        if i != j then
          val num = add(x, xSamples(j))
          val denom = add(xSamples(i), xSamples(j))
          basis = mult(basis, div(num, denom))
      result = add(result, mult(ySamples(i), basis))
    result

  // Creates random polynomial coefficients with the given intercept (one secret byte).
  private def newCoefficients(intercept: Int, degree: Int): Array[Int] =
    val coefficients = Array.ofDim[Int](degree + 1)
    coefficients(0) = intercept
    val randomBytes = Array.ofDim[Byte](degree)
    secureRandom.nextBytes(randomBytes)
    for i <- 0 until degree do coefficients(i + 1) = randomBytes(i) & 0xff
    coefficients

  // Creates a pseudo-randomly shuffled set of x-coordinates drawn from [1, 256).
  // The shuffle is intentionally biased (same as the reference implementation) — this
  // does not affect the security properties of SSS.
  private def newCoordinates(): Array[Int] =
    val coordinates = Array.tabulate(255)(_ + 1)
    val randomBytes = Array.ofDim[Byte](255)
    secureRandom.nextBytes(randomBytes)
    for i <- 0 until 255 do
      val j = (randomBytes(i) & 0xff) % 255
      val temp = coordinates(i)
      coordinates(i) = coordinates(j)
      coordinates(j) = temp
    coordinates

  /** Splits `secret` into `shares` shares, requiring `threshold` of them to reconstruct.
    *
    * @param secret
    *   The secret to split. Must be non-empty.
    * @param shares
    *   Total number of shares to produce. Must be in [2, 255].
    * @param threshold
    *   Minimum shares required to reconstruct. Must be in [2, 255] and ≤ `shares`.
    * @return
    *   A list of `shares` byte arrays, each of length `secret.length + 1`. The last byte of each share is the
    *   x-coordinate; the preceding bytes are y-values.
    */
  def split(secret: Array[Byte], shares: Int, threshold: Int): List[Array[Byte]] =
    require(secret.nonEmpty, "secret cannot be empty")
    require(shares >= 2 && shares <= 255, "shares must be at least 2 and at most 255")
    require(threshold >= 2 && threshold <= 255, "threshold must be at least 2 and at most 255")
    require(shares >= threshold, "shares cannot be less than threshold")

    val secretLength = secret.length
    val xCoordinates = newCoordinates()
    val degree = threshold - 1

    val result = Array.tabulate(shares) { i =>
      val share = Array.ofDim[Byte](secretLength + 1)
      share(secretLength) = xCoordinates(i).toByte
      share
    }

    for i <- 0 until secretLength do
      val coefficients = newCoefficients(secret(i) & 0xff, degree)
      for j <- 0 until shares do result(j)(i) = evaluate(coefficients, xCoordinates(j), degree).toByte

    result.toList

  /** Reconstructs the secret from `shares`. The order of shares does not matter. Passing more than `threshold` shares
    * is fine; passing fewer will silently return garbage.
    *
    * @param shares
    *   A list of 2–255 shares, all of the same byte length (≥ 2), with unique x-coordinates.
    * @return
    *   The reconstructed secret (length = share length − 1).
    */
  def combine(shares: List[Array[Byte]]): Array[Byte] =
    require(shares.size >= 2 && shares.size <= 255, "shares must have at least 2 and at most 255 elements")
    val shareLength = shares.head.length
    require(shareLength >= 2, "each share must be at least 2 bytes")
    require(shares.forall(_.length == shareLength), "all shares must have the same byte length")

    val secretLength = shareLength - 1
    val xSamples = Array.ofDim[Int](shares.size)
    val seen = mutable.Set[Int]()

    for (share, i) <- shares.zipWithIndex do
      val x = share(shareLength - 1) & 0xff
      require(seen.add(x), "shares must contain unique x-coordinates but a duplicate was found")
      xSamples(i) = x

    val ySamples = Array.ofDim[Int](shares.size)
    Array.tabulate[Byte](secretLength) { byteIndex =>
      for j <- shares.indices do ySamples(j) = shares(j)(byteIndex) & 0xff
      interpolatePolynomial(xSamples, ySamples, 0).toByte
    }

  /** Result of [[combineWithIntegrity]]. `hasIntegrityMargin` is `false` only when exactly `threshold` shares were
    * supplied (nothing to cross-check against — the "reconstructed without integrity margin" case). `excludedIndices`
    * are positions in the input `shares` list identified as inconsistent with the rest and excluded from
    * reconstruction; empty when every share agreed.
    */
  final case class IntegrityCombineResult(secret: Array[Byte], excludedIndices: Set[Int], hasIntegrityMargin: Boolean)

  /** Thrown by [[combineWithIntegrity]] when more shares were collected than `threshold`, but no size-`threshold`
    * subset could be found whose agreement with the rest clears the Reed–Solomon unique-decoding-radius bound
    * (`⌊(collected - threshold) / 2⌋` correctable bad shares). Never silently guesses a secret.
    */
  final case class ReconstructionIntegrityException(message: String) extends Exception(message)

  // Safety valve against pathological C(m, threshold) blow-up for unrealistically large fan-outs —
  // n is already soft-capped in practice by an app-level operational-burden warning at split time,
  // so this is a generous, documented scope limit rather than a fully general polynomial-time
  // Reed-Solomon decoder (Berlekamp-Welch). Comfortably covers e.g. threshold=6, collected=14
  // (C(14,6) = 3,003).
  private val MaxIntegrityCombinationsTried = 5000

  /** Reconstructs from more than `threshold` shares by finding the largest mutually-consistent subset and using it —
    * classic Shamir has no built-in integrity, so passing extra shares to plain [[combine]] would silently mix in a bad
    * one and produce a wrong secret with no error signal.
    *
    * Algorithm: bounded-exhaustive maximum-agreement decoding. Every size-`threshold` subset of `shares` is a
    * "hypothesis"; for each, the implied secret is interpolated and every one of the `shares.size` inputs is checked
    * against it at *every* byte position (a corrupted or forged share is wrong as a whole, not selectively per-byte).
    * The hypothesis with the largest agreeing set wins. This is accepted only if it clears the Reed–Solomon
    * unique-decoding-radius bound (`agreeing.size >= shares.size - ⌊(shares.size - threshold) / 2⌋`) — a hard
    * mathematical guarantee (two distinct degree-`<threshold` polynomials can agree on at most `threshold - 1` points),
    * not a heuristic, so whenever this succeeds the result is provably the unique correct answer, not a guess.
    *
    * @throws ReconstructionIntegrityException
    *   if no subset clears that bound — this correctly *detects* a problem without guessing which share is at fault
    *   when the margin is too thin to correct it (e.g. exactly `threshold + 1` shares with one bad one), and correctly
    *   refuses to pick a spurious "majority" when more shares are bad than the margin can tolerate.
    */
  def combineWithIntegrity(shares: List[Array[Byte]], threshold: Int): IntegrityCombineResult =
    require(shares.size >= 2 && shares.size <= 255, "shares must have at least 2 and at most 255 elements")
    require(threshold >= 2 && threshold <= 255, "threshold must be at least 2 and at most 255")
    require(shares.size >= threshold, "shares cannot be less than threshold")
    val shareLength = shares.head.length
    require(shareLength >= 2, "each share must be at least 2 bytes")
    require(shares.forall(_.length == shareLength), "all shares must have the same byte length")

    val secretLength = shareLength - 1
    val m = shares.size
    val xSamples = Array.ofDim[Int](m)
    val seen = mutable.Set[Int]()
    for (share, i) <- shares.zipWithIndex do
      val x = share(shareLength - 1) & 0xff
      require(seen.add(x), "shares must contain unique x-coordinates but a duplicate was found")
      xSamples(i) = x

    if m == threshold then IntegrityCombineResult(combine(shares), Set.empty, hasIntegrityMargin = false)
    else
      // Reconstructs from a threshold-sized hypothesis (indices into `shares`), then returns
      // which of the full `m` shares agree with it at every byte position.
      def evaluateHypothesis(hypothesis: Array[Int]): (Array[Byte], Set[Int]) =
        val hypoX = hypothesis.map(xSamples(_))
        val secret = Array.ofDim[Byte](secretLength)
        val ySamples = Array.ofDim[Int](threshold)
        for byteIndex <- 0 until secretLength do
          for t <- 0 until threshold do ySamples(t) = shares(hypothesis(t))(byteIndex) & 0xff
          secret(byteIndex) = interpolatePolynomial(hypoX, ySamples, 0).toByte
        var agreeing = hypothesis.toSet
        for j <- 0 until m do
          if !agreeing.contains(j) then
            var matches = true
            var byteIndex = 0
            while matches && byteIndex < secretLength do
              for t <- 0 until threshold do ySamples(t) = shares(hypothesis(t))(byteIndex) & 0xff
              val predicted = interpolatePolynomial(hypoX, ySamples, xSamples(j))
              if predicted != (shares(j)(byteIndex) & 0xff) then matches = false
              byteIndex += 1
            if matches then agreeing = agreeing + j
        (secret, agreeing)

      val excess = m - threshold
      val correctable = excess / 2
      val acceptThreshold = m - correctable

      var bestSecret: Option[Array[Byte]] = None
      var bestAgreeing: Set[Int] = Set.empty
      var combo = Array.tabulate(threshold)(identity)
      var tried = 0
      var continue = true
      while continue do
        val (secret, agreeing) = evaluateHypothesis(combo)
        tried += 1
        if agreeing.size > bestAgreeing.size then
          bestSecret = Some(secret)
          bestAgreeing = agreeing
          if bestAgreeing.size == m then continue = false // unanimous — nothing can beat this
        if continue then
          if tried >= MaxIntegrityCombinationsTried then continue = false
          else
            nextCombination(combo, m) match
              case Some(next) => combo = next
              case None       => continue = false

      (bestSecret, bestAgreeing.size >= acceptThreshold) match
        case (Some(secret), true) =>
          IntegrityCombineResult(secret, (0 until m).toSet -- bestAgreeing, hasIntegrityMargin = true)
        case _ =>
          throw ReconstructionIntegrityException(
            s"Reconstruction integrity check failed: could not find $threshold or more mutually " +
              s"consistent shares among $m collected (largest consistent group: ${bestAgreeing.size})"
          )

  // Standard lexicographic "next combination" of size k from n elements (0-based indices); None
  // once the last combination has been produced.
  private def nextCombination(combo: Array[Int], n: Int): Option[Array[Int]] =
    val k = combo.length
    val next = combo.clone()
    var i = k - 1
    while i >= 0 && next(i) == n - k + i do i -= 1
    if i < 0 then None
    else
      next(i) += 1
      for j <- (i + 1) until k do next(j) = next(j - 1) + 1
      Some(next)
