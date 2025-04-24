import utest._
import shared.Utils

object MainTests extends TestSuite {
  def tests: Tests = Tests {
    'Lib - {
      'addTwice - {
        assert(
          Lib.addTwice(1, 2) == 6
        )
      }
      'parse - {
        assert(
          Lib.parse("hello:world") == Seq("hello", "world")
        )
      }
    }
    'shared - {
      'add - {
        assert(
          Utils.add(1, 2) == 3
        )
      }
    }
  }
}
