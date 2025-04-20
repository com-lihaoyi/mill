package hellotest

import hello._
import utest._

object ArgsParserTests extends TestSuite {

  def tests: Tests = Tests {
    test("one") {
      val result = ArgsParser.parse("hello:world")
      assert(
        result.length == 2,
        result == Seq("hello", "world")
      )
    }
    test("two") { // we fail this test to check testing in Scala Native
      val result = ArgsParser.parse("hello:world")
      assert(
        result.length == 80
      )
    }
  }

}
