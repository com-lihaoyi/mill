package basic

import utest._

object MainTests extends TestSuite {

  def tests: Tests = Tests {
    'runMethod - {
      'zero - {
        assert(
          Main.runMethod(0) == 0
        )
      }
      'one - {
        assert(
          Main.runMethod(1) == 1
        )
      }
      // NB: only test runMethod(0) and runMethod(1) so we don't cover other cases
    }
  }
}