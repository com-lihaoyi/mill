package hellotest

import hello._
import utest._

object MainTests extends TestSuite {

  def tests: Tests = Tests {
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
