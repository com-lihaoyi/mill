package jawn
package ast

import java.lang.Double.{isNaN, isInfinite}
import scala.collection.mutable
import scala.util.hashing.MurmurHash3

class WrongValueException(e: String, g: String) extends Exception(s"expected $e, got $g")

class InvalidNumException(s: String) extends Exception(s"invalid number: $s")

sealed abstract class JValue {

  def valueType: String

  def getBoolean: Option[Boolean] = None
  def getString: Option[String] = None
  def getInt: Option[Int] = None
  def getLong: Option[Long] = None
  def getDouble: Option[Double] = None
  def getBigInt: Option[BigInt] = None
  def getBigDecimal: Option[BigDecimal] = None

  def asBoolean: Boolean = throw new WrongValueException("boolean", valueType)
  def asString: String = throw new WrongValueException("string", valueType)
  def asInt: Int = throw new WrongValueException("number", valueType)
  def asLong: Long = throw new WrongValueException("number", valueType)
  def asDouble: Double = throw new WrongValueException("number", valueType)
  def asBigInt: BigInt = throw new WrongValueException("number", valueType)
  def asBigDecimal: BigDecimal = throw new WrongValueException("number", valueType)

  def get(i: Int): JValue = JNull
  def set(i: Int, v: JValue): Unit = throw new WrongValueException("array", valueType)

  def get(s: String): JValue = JNull
  def set(s: String, v: JValue): Unit = throw new WrongValueException("object", valueType)
  def remove(s: String): Option[JValue] = None

  final def atomic: Option[JAtom] =
    this match {
      case v: JAtom => Some(v)
      case _ => None
    }

  final def isNull: Boolean =
    this == JNull

  final def nonNull: Boolean =
    this != JNull

  final def render(): String =
    CanonicalRenderer.render(this)

  final def render(r: Renderer): String =
    r.render(this)

  override def toString: String =
    CanonicalRenderer.render(this)
}

object JValue {
  implicit val facade: Facade[JValue] = JawnFacade
}

sealed abstract class JAtom extends JValue {
  def fold[A](f1: String => A, f2: Double => A, f3: Boolean => A, f4: => A): A =
    this match {
      case JString(s) => f1(s)
      case v: JNum => f2(v.asDouble)
      case JTrue => f3(true)
      case JFalse => f3(false)
      case JNull => f4
    }
}

case object JNull extends JAtom {
  final def valueType: String = "null"
}

sealed abstract class JBool extends JAtom {
  final def valueType: String = "boolean"
  final override def getBoolean: Option[Boolean] = Some(this == JTrue)
  final override def asBoolean: Boolean = this == JTrue
}

object JBool {
  final val True: JBool = JTrue
  final val False: JBool = JFalse

  final def apply(b: Boolean): JBool = if (b) JTrue else JFalse
}

case object JTrue extends JBool
case object JFalse extends JBool

case class JString(s: String) extends JAtom {
  final def valueType: String = "string"
  final override def getString: Option[String] = Some(s)
  final override def asString: String = s
}

object JString {
  final val empty = JString("")
}

sealed abstract class JNum extends JAtom {
  final def valueType: String = "number"
}

object JNum { self =>

  /**
   * Create a JNum from a Long.
   *
   * This is identical to calling the LongNum(_) constructor.
   */
  final def apply(n: Long): JNum =
    LongNum(n)

  /**
   * Create a JNum from a Double.
   *
   * This factory constructor performs some error-checking (ensures
   * that the given value is a finite Double). If you have already
   * done this error-checking, you can use the DoubleNum(_) or
   * DeferNum(_) constructors directly.
   */
  final def apply(n: Double): JNum =
    if (isNaN(n) || isInfinite(n)) throw new InvalidNumException(n.toString)
    else DoubleNum(n)

  /**
   * Create a JNum from a String.
   *
   * This factory constructor validates the string (essentially,
   * parsing it as a JSON value). If you are already sure this string
   * is a valid JSON number, you can use the DeferLong(_) or
   * DeferNum(_) constructors directly.
   */
  final def apply(s: String): JNum =
    JParser.parseUnsafe(s) match {
      case jnum: JNum => jnum
      case _ => throw new InvalidNumException(s)
    }

  final def hybridEq(x: Long, y: Double): Boolean = {
    val z = x.toDouble
    y == z && z.toLong == x
  }

  final val zero: JNum = LongNum(0)
  final val one: JNum = LongNum(1)
}

case class LongNum(n: Long) extends JNum {

  final override def getInt: Option[Int] = Some(n.toInt)
  final override def getLong: Option[Long] = Some(n)
  final override def getDouble: Option[Double] = Some(n.toDouble)
  final override def getBigInt: Option[BigInt] = Some(BigInt(n))
  final override def getBigDecimal: Option[BigDecimal] = Some(BigDecimal(n))

  final override def asInt: Int = n.toInt
  final override def asLong: Long = n
  final override def asDouble: Double = n.toDouble
  final override def asBigInt: BigInt = BigInt(n)
  final override def asBigDecimal: BigDecimal = BigDecimal(n)

  final override def hashCode: Int = n.##

  final override def equals(that: Any): Boolean =
    that match {
      case LongNum(n2) => n == n2
      case DoubleNum(n2) => JNum.hybridEq(n, n2)
      case jn: JNum => jn == this
      case _ => false
    }
}

case class DoubleNum(n: Double) extends JNum {

  final override def getInt: Option[Int] = Some(n.toInt)
  final override def getLong: Option[Long] = Some(n.toLong)
  final override def getDouble: Option[Double] = Some(n)
  final override def getBigInt: Option[BigInt] = Some(BigDecimal(n).toBigInt)
  final override def getBigDecimal: Option[BigDecimal] = Some(BigDecimal(n))

  final override def asInt: Int = n.toInt
  final override def asLong: Long = n.toLong
  final override def asDouble: Double = n
  final override def asBigInt: BigInt = BigDecimal(n).toBigInt
  final override def asBigDecimal: BigDecimal = BigDecimal(n)

  final override def hashCode: Int = n.##

  final override def equals(that: Any): Boolean =
    that match {
      case LongNum(n2) => JNum.hybridEq(n2, n)
      case DoubleNum(n2) => n == n2
      case jn: JNum => jn == this
      case _ => false
    }
}

case class DeferLong(s: String) extends JNum {

  lazy val n: Long = util.parseLongUnsafe(s)

  final override def getInt: Option[Int] = Some(n.toInt)
  final override def getLong: Option[Long] = Some(n)
  final override def getDouble: Option[Double] = Some(n.toDouble)
  final override def getBigInt: Option[BigInt] = Some(BigInt(s))
  final override def getBigDecimal: Option[BigDecimal] = Some(BigDecimal(s))

  final override def asInt: Int = n.toInt
  final override def asLong: Long = n
  final override def asDouble: Double = n.toDouble
  final override def asBigInt: BigInt = BigInt(s)
  final override def asBigDecimal: BigDecimal = BigDecimal(s)

  final override def hashCode: Int = n.##

  final override def equals(that: Any): Boolean =
    that match {
      case LongNum(n2) => n == n2
      case DoubleNum(n2) => JNum.hybridEq(n, n2)
      case jn: DeferLong => n == jn.asLong
      case jn: DeferNum => JNum.hybridEq(n, jn.asDouble)
      case _ => false
    }
}

case class DeferNum(s: String) extends JNum {

  lazy val n: Double = java.lang.Double.parseDouble(s)

  final override def getInt: Option[Int] = Some(n.toInt)
  final override def getLong: Option[Long] = Some(util.parseLongUnsafe(s))
  final override def getDouble: Option[Double] = Some(n)
  final override def getBigInt: Option[BigInt] = Some(BigDecimal(s).toBigInt)
  final override def getBigDecimal: Option[BigDecimal] = Some(BigDecimal(s))

  final override def asInt: Int = n.toInt
  final override def asLong: Long = util.parseLongUnsafe(s)
  final override def asDouble: Double = n
  final override def asBigInt: BigInt = BigDecimal(s).toBigInt
  final override def asBigDecimal: BigDecimal = BigDecimal(s)

  final override def hashCode: Int = n.##

  final override def equals(that: Any): Boolean =
    that match {
      case LongNum(n2) => JNum.hybridEq(n2, n)
      case DoubleNum(n2) => n == n2
      case jn: DeferLong => JNum.hybridEq(jn.asLong, n)
      case jn: DeferNum => n == jn.asDouble
      case _ => false
    }
}

case class JArray(vs: Array[JValue]) extends JValue {
  final def valueType: String = "array"

  final override def get(i: Int): JValue =
    if (0 <= i && i < vs.length) vs(i) else JNull

  final override def set(i: Int, v: JValue): Unit =
    vs(i) = v

  final override def hashCode: Int = MurmurHash3.arrayHash(vs)

  final override def equals(that: Any): Boolean =
    that match {
      case JArray(vs2) =>
        if (vs.length != vs2.length) return false
        var i = 0
        while (i < vs.length) {
          if (vs(i) != vs2(i)) return false
          i += 1
        }
        true
      case _ =>
        false
    }
}

object JArray { self =>
  final def empty: JArray =
    JArray(new Array[JValue](0))

  final def fromSeq(js: Seq[JValue]): JArray =
    JArray(js.toArray)
}

case class JObject(vs: mutable.Map[String, JValue]) extends JValue {
  final def valueType: String = "object"

  final override def get(k: String): JValue =
    vs.getOrElse(k, JNull)

  final override def set(k: String, v: JValue): Unit =
    vs.put(k, v)

  final override def remove(k: String): Option[JValue] =
    vs.remove(k)
}

object JObject { self =>
  final def empty: JObject =
    JObject(mutable.Map.empty)

  final def fromSeq(js: Seq[(String, JValue)]): JObject =
    JObject(mutable.Map(js: _*))
}
