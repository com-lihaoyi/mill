import utest._

object ClientEnvTests extends TestSuite {
  def tests: Tests = Tests {
    test("clientEnv") {
      assert(sys.env.get("MILL_TEST_CLIENT_ENV") == Some("from-client"))
    }
  }
}
