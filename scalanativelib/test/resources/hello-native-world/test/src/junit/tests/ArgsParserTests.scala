package hellotest

import hello._
import org.junit.Test

class ArgsParserTests {
  @Test
  def one() = {
    val result = ArgsParser.parse("hello:world")
    assertEquals(result.length, 2)
    assertEquals(result, Seq("hello", "world"))
  }
  // we fail this test to check testing in Scala Native
  @Test
  def two() = {
    val result = ArgsParser.parse("hello:world")
    assertEquals(result.length, 80)
  }
}
