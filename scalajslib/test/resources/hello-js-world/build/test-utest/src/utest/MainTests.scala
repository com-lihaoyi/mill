import utest._

object MainTests extends TestSuite {

  def tests: Tests = Tests {
    test("vmName") {
      test("containJs") {
        assert(
          Main.vmName.contains("js")
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
