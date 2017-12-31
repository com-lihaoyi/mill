package jawn
package support.argonaut

import argonaut._
import Argonaut._
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.prop.Checkers
import org.scalatest.{Matchers, FlatSpec}
import scala.util.Try

object ParserSpec {
  case class Example(a: Int, b: Long, c: Double)

  val exampleCodecJson: CodecJson[Example] =
    casecodec3(Example.apply, Example.unapply)("a", "b", "c")

  implicit val exampleCaseClassArbitrary: Arbitrary[Example] = Arbitrary(
    for {
      a <- arbitrary[Int]
      b <- arbitrary[Long]
      c <- arbitrary[Double]
    } yield Example(a, b, c)
  )
}

class ParserSpec extends FlatSpec with Matchers with Checkers {
  import ParserSpec._
  import jawn.support.argonaut.Parser.facade

  "The Argonaut support Parser" should "correctly marshal case classes with Long values" in {
    check { (e: Example) =>
      val jsonString: String = exampleCodecJson.encode(e).nospaces
      val json: Try[Json] = jawn.Parser.parseFromString(jsonString)
      exampleCodecJson.decodeJson(json.get).toOption match {
        case None => fail()
        case Some(example) => example == e
      }
    }
  }
}
