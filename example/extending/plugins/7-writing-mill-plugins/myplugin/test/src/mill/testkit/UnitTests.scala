package myplugin

import mill.testkit.{TestBaseModule, UnitTester}
import mill.define.Discover
import mill.util.TokenReaders._
import utest._

object UnitTests extends TestSuite {
  def tests: Tests = Tests {
    test("unit") {
      object build extends TestBaseModule with LineCountJavaModule {
        def lineCountResourceFileName = "line-count.txt"

        lazy val millDiscover = Discover[this.type]
      }

      val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
      UnitTester(build, resourceFolder / "unit-test-project").scoped { eval =>
        // Evaluating tasks by direct reference
        val Right(result) = eval(build.resources)
        assert(
          result.value.exists(pathref =>
            os.exists(pathref.path / "line-count.txt") &&
              os.read(pathref.path / "line-count.txt") == "18"
          )
        )

        // Evaluating tasks by passing in their Mill selector
        val Right(result2) = eval("resources")
        val Seq(pathrefs: Seq[mill.api.PathRef]) = result2.value
        assert(
          pathrefs.exists(pathref =>
            os.exists(pathref.path / "line-count.txt") &&
              os.read(pathref.path / "line-count.txt") == "18"
          )
        )
      }
    }
  }
}
