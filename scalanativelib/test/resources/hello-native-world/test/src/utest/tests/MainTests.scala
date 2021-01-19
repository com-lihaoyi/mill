package hellotest

import hello._
import utest._

object MainTests extends TestSuite {

  val tests: Tests = Tests {
    'vmName - {
      'containNative - {
        assert(
          Main.vmName.contains("Native")
        )
      }
      'containScala - {
        assert(
          Main.vmName.contains("Scala")
        )
      }
    }
  }

}
