package hellotest

import hello._
import utest._
import java.nio.file._
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
        assert(
          Files.list(Paths.get(sys.env("MILL_TEST_RESOURCE_FOLDER"))).toList.get(0) ==
            Paths.get(sys.env("MILL_TEST_RESOURCE_FOLDER") + "/hello-resource.txt")
        )
        assert(
          Files.readString(Paths.get(sys.env("MILL_TEST_RESOURCE_FOLDER") + "/hello-resource.txt")) ==
          "hello world resource text"
        )
      }
    }
  }

}
