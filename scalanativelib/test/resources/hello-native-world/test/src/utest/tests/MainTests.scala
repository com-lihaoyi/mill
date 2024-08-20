package hellotest

import hello._
import utest._

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
    }
  }

}
