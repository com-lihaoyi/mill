package hellotest

import hello._
import org.scalatest.matchers.should.Matchers
import org.scalatest.flatspec.AnyFlatSpec

class ArgsParserSpec extends AnyFlatSpec with Matchers {

  behavior of "ArgsParser"

  "parse" should "one" in {
    val result = ArgsParser.parse("hello:world")
    result should have length 2
    result should contain only ("hello", "world")
  }

  it should "two" in {
    val result = ArgsParser.parse("hello:world")
    result should have length 80
  }

}
