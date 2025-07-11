package mill.init

import utest.*

object ExampleListTests extends TestSuite {
  override def tests: Tests = Tests {
    test("init") {
      val deserialized = upickle.default.read[Seq[(String, String)]](os.read(os.resource / "exampleList.txt"))

      val expected = (
        "scalalib/basic/1-simple",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/SNAPSHOT/mill-dist-SNAPSHOT-example-scalalib-basic-1-simple.zip"
      )
      assert(deserialized.contains(expected))
    }
  }
}
