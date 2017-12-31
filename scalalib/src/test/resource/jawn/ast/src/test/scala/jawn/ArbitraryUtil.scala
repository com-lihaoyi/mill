package jawn
package ast

import org.scalacheck._
import Gen._
import Arbitrary.arbitrary

object ArbitraryUtil {

  // JSON doesn't allow NaN, PositiveInfinity, or NegativeInfinity
  def isFinite(n: Double): Boolean =
    !java.lang.Double.isNaN(n) && !java.lang.Double.isInfinite(n)

  val jnull    = Gen.const(JNull)
  val jboolean = Gen.oneOf(JTrue :: JFalse :: Nil)
  val jlong    = arbitrary[Long].map(LongNum(_))
  val jdouble  = arbitrary[Double].filter(isFinite).map(DoubleNum(_))
  val jstring  = arbitrary[String].map(JString(_))

  // Totally unscientific atom frequencies.
  val jatom: Gen[JAtom] =
    Gen.frequency(
      (1, jnull),
      (8, jboolean),
      (8, jlong),
      (8, jdouble),
      (16, jstring))

  // Use lvl to limit the depth of our jvalues.
  // Otherwise we will end up with SOE real fast.

  val MaxLevel: Int = 3

  def jarray(lvl: Int): Gen[JArray] =
    Gen.containerOf[Array, JValue](jvalue(lvl + 1)).map(JArray(_))

  def jitem(lvl: Int): Gen[(String, JValue)] =
    for { s <- arbitrary[String]; j <- jvalue(lvl) } yield (s, j)

  def jobject(lvl: Int): Gen[JObject] =
    Gen.containerOf[Vector, (String, JValue)](jitem(lvl + 1)).map(JObject.fromSeq)

  def jvalue(lvl: Int = 0): Gen[JValue] =
    if (lvl >= MaxLevel) jatom
    else Gen.frequency((16, jatom), (1, jarray(lvl)), (2, jobject(lvl)))

  implicit lazy val arbitraryJValue: Arbitrary[JValue] =
    Arbitrary(jvalue())
}
