package mill.util

import utest.*

object CoursierConfigTests extends TestSuite {
  val tests: Tests = Tests {
    test("userAgent") {
      test("format") {
        val agent = CoursierConfig.userAgent(millVersion = "1.3.1", coursierVersion = "2.1.27")
        assert(agent == "Mill/1.3 (https://github.com/com-lihaoyi/mill) Coursier/2.1")
      }
      test("unparseableVersions") {
        val agent = CoursierConfig.userAgent(millVersion = "", coursierVersion = "")
        assert(agent == "Mill (https://github.com/com-lihaoyi/mill) Coursier")
      }
      test("default") {
        val agent = CoursierConfig.defaultUserAgent(props = Map.empty)
        assert(agent == CoursierConfig.userAgent(BuildInfo.millVersion, BuildInfo.coursierVersion))
        assert(agent.startsWith("Mill"))
        assert(!agent.contains(". "))
      }
      test("javaPropertyOverride") {
        val agent =
          CoursierConfig.defaultUserAgent(props = Map("coursier.http.agent" -> "Custom/1.0"))
        assert(agent == "Custom/1.0")
      }
      test("defaultConfig") {
        assert(CoursierConfig.default().userAgent == Some(CoursierConfig.defaultUserAgent()))
      }
    }
  }
}
