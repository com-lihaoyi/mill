package hellotest

import hello._
import utest._

object ArgsParserTests extends TestSuite {

  def tests: Tests = Tests {
    'one - {
      val result = ArgsParser.parse("hello:world")
      assert(
        result.length == 2,
        result == Seq("hello", "world")
      )
    }
    'two - { // we fail this test to check testing in scala.js
      val result = ArgsParser.parse("hello:world")
      assert(
        result.length == 80
      )
    }
  }

}
