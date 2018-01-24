import utest._

object MainTests extends TestSuite {

  def tests: Tests = Tests {
    'vmName - {
      'containJs - {
        assert(
          Main.vmName.contains("js")
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
