import utest.{TestSuite, Tests, test}

object EmptyPackageTests extends TestSuite {
  val tests = Tests {
    test("suites in the empty package should run") {}
  }
}
