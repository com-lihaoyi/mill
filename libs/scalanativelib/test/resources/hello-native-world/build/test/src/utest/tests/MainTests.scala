package hellotest

import hello.*
import utest.*
import java.nio.file.*
import java.util.stream.Collectors
object MainTests extends TestSuite {

  val tests: Tests = Tests {
    test("vmName") {
      test("containNative") {
        assert(
          Main.vmName.contains("Native")
        )
      }
      test("containScala") {
        assert(
          Main.vmName.contains("Scala")
        )
      }
      test("resource") {
        val expected = new java.util.ArrayList[Path]()
        expected.add(Paths.get(sys.env("MILL_TEST_RESOURCE_DIR") + "/hello-resource.txt"))
        val listed =
          Files.list(Paths.get(sys.env("MILL_TEST_RESOURCE_DIR"))).collect(Collectors.toList())
        assert(listed == expected)
        assert(
          Files.readString(
            Paths.get(sys.env("MILL_TEST_RESOURCE_DIR") + "/hello-resource.txt")
          ) ==
            "hello world resource text"
        )
      }
    }
  }

}
