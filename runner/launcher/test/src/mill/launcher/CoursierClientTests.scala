package mill.launcher

import mill.util.CoursierConfig
import utest.*

object CoursierClientTests extends TestSuite {
  val tests: Tests = Tests {
    test("coursierCacheSendsMillUserAgent") {
      val agent = CoursierClient.coursierCache().userAgent
      assert(agent == Some(CoursierConfig.defaultUserAgent()))
      assert(agent.exists(_.startsWith("Mill")))
    }
  }
}
