import org.junit._

class ArgsParserTests {

  @Test def one(): Unit = {
    val result = ArgsParser.parse("hello:world")
    assert(
      result.length == 2,
      result == Seq("hello", "world")
    )
  }
  @Test def two(): Unit = { // we fail this test to check testing in scala.js
    val result = ArgsParser.parse("hello:world")
    assert(
      result.length == 80
    )
  }

}
