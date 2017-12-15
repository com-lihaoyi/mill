package jawn
package parser

import org.scalatest._
import prop._
import org.scalacheck.Arbitrary._
import org.scalacheck._
import Gen._
import Arbitrary.arbitrary

import scala.util.{Try, Success, Failure}

class SyntaxCheck extends PropSpec with Matchers with PropertyChecks {

  sealed trait J {
    def build: String = this match {
      case JAtom(s) => s
      case JArray(js) => js.map(_.build).mkString("[", ",", "]")
      case JObject(js) => js.map { case (k, v) =>
        val kk = "\"" + k + "\""
        val vv = v.build
        s"$kk: $vv"
      }.mkString("{", ",", "}")
    }
  }

  case class JAtom(s: String) extends J
  case class JArray(js: List[J]) extends J
  case class JObject(js: Map[String, J]) extends J

  val jatom: Gen[JAtom] =
    Gen.oneOf(
      "null", "true", "false", "1234", "-99", "16.0", "2e9",
      "-4.44E-10", "11e+14", "\"foo\"", "\"\"", "\"bar\"",
      "\"qux\"", "\"duh\"", "\"abc\"", "\"xyz\"", "\"zzzzzz\"",
      "\"\\u1234\"").map(JAtom(_))

  def jarray(lvl: Int): Gen[JArray] =
    Gen.containerOf[List, J](jvalue(lvl + 1)).map(JArray(_))

  val keys = Gen.oneOf("foo", "bar", "qux", "abc", "def", "xyz")
  def jitem(lvl: Int): Gen[(String, J)] =
    for { s <- keys; j <- jvalue(lvl) } yield (s, j)

  def jobject(lvl: Int): Gen[JObject] =
    Gen.containerOf[List, (String, J)](jitem(lvl + 1)).map(ts => JObject(ts.toMap))

  def jvalue(lvl: Int): Gen[J] =
    if (lvl < 3) {
      Gen.frequency((16, 'ato), (1, 'arr), (2, 'obj)).flatMap {
        case 'ato => jatom
        case 'arr => jarray(lvl)
        case 'obj => jobject(lvl)
      }
    } else {
      jatom
    }

  implicit lazy val arbJValue: Arbitrary[J] =
    Arbitrary(jvalue(0))

  import java.nio.ByteBuffer

  def isValidSyntax(s: String): Boolean = {
    val cs = java.nio.CharBuffer.wrap(s.toCharArray)
    val r0 = Parser.parseFromCharSequence(cs)(NullFacade).isSuccess
    val r1 = Parser.parseFromString(s)(NullFacade).isSuccess
    val bb = ByteBuffer.wrap(s.getBytes("UTF-8"))
    val r2 = Parser.parseFromByteBuffer(bb)(NullFacade).isSuccess
    if (r0 == r1) r1 else sys.error(s"CharSequence/String parsing disagree($r0, $r1): $s")
    if (r1 == r2) r1 else sys.error(s"String/ByteBuffer parsing disagree($r1, $r2): $s")

    TestUtil.withTemp(s) { t =>
      Parser.parseFromFile(t)(NullFacade).isSuccess
    }

    val async = AsyncParser[Unit](AsyncParser.SingleValue)
    val r3 = async.absorb(s)(NullFacade).isRight && async.finish()(NullFacade).isRight
    if (r1 == r3) r1 else sys.error(s"Sync/Async parsing disagree($r1, $r3): $s")
  }

  property("syntax-checking") {
    forAll { (j: J) => isValidSyntax(j.build) shouldBe true }
  }

  def qs(s: String): String = "\"" + s + "\""

  property("unicode is ok") {
    isValidSyntax(qs("ö")) shouldBe true
    isValidSyntax(qs("ö\\\\")) shouldBe true
    isValidSyntax(qs("\\\\ö")) shouldBe true
  }

  property("literal TAB is invalid") { isValidSyntax(qs("\t")) shouldBe false }
  property("literal NL is invalid") { isValidSyntax(qs("\n")) shouldBe false }
  property("literal CR is invalid") { isValidSyntax(qs("\r")) shouldBe false }
  property("literal NUL is invalid") { isValidSyntax(qs("\u0000")) shouldBe false }
  property("literal BS TAB is invalid") { isValidSyntax(qs("\\\t")) shouldBe false }
  property("literal BS NL is invalid") { isValidSyntax(qs("\\\n")) shouldBe false }
  property("literal BS CR is invalid") { isValidSyntax(qs("\\\r")) shouldBe false }
  property("literal BS NUL is invalid") { isValidSyntax(qs("\\\u0000")) shouldBe false }
  property("literal BS ZERO is invalid") { isValidSyntax(qs("\\0")) shouldBe false }
  property("literal BS X is invalid") { isValidSyntax(qs("\\x")) shouldBe false }

  property("0 is ok") { isValidSyntax("0") shouldBe true }
  property("0e is invalid") { isValidSyntax("0e") shouldBe false }
  property("123e is invalid") { isValidSyntax("123e") shouldBe false }
  property(".999 is invalid") { isValidSyntax(".999") shouldBe false }
  property("0.999 is ok") { isValidSyntax("0.999") shouldBe true }
  property("-.999 is invalid") { isValidSyntax("-.999") shouldBe false }
  property("-0.999 is ok") { isValidSyntax("-0.999") shouldBe true }
  property("+0.999 is invalid") { isValidSyntax("+0.999") shouldBe false }
  property("--0.999 is invalid") { isValidSyntax("--0.999") shouldBe false }
  property("01 is invalid") { isValidSyntax("01") shouldBe false }
  property("1e is invalid") { isValidSyntax("1e") shouldBe false }
  property("1e- is invalid") { isValidSyntax("1e+") shouldBe false }
  property("1e+ is invalid") { isValidSyntax("1e-") shouldBe false }
  property("1. is invalid") { isValidSyntax("1.") shouldBe false }
  property("1.e is invalid") { isValidSyntax("1.e") shouldBe false }
  property("1.e9 is invalid") { isValidSyntax("1.e9") shouldBe false }
  property("1.e- is invalid") { isValidSyntax("1.e+") shouldBe false }
  property("1.e+ is invalid") { isValidSyntax("1.e-") shouldBe false }
  property("1.1e is invalid") { isValidSyntax("1.1e") shouldBe false }
  property("1.1e- is invalid") { isValidSyntax("1.1e-") shouldBe false }
  property("1.1e+ is invalid") { isValidSyntax("1.1e+") shouldBe false }
  property("1.1e1 is ok") { isValidSyntax("1.1e1") shouldBe true }
  property("1.1e-1 is ok") { isValidSyntax("1.1e-1") shouldBe true }
  property("1.1e+1 is ok") { isValidSyntax("1.1e+1") shouldBe true }
  property("1+ is invalid") { isValidSyntax("1+") shouldBe false }
  property("1- is invalid") { isValidSyntax("1-") shouldBe false }
}
