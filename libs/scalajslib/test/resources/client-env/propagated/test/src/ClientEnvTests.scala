import scala.scalajs.js
import utest._

object ClientEnvTests extends TestSuite {
  def tests: Tests = Tests {
    test("clientEnv") {
      assert(js.Dynamic.global.process.env.MILL_TEST_CLIENT_ENV.asInstanceOf[Any] == "from-client")
    }
  }
}
