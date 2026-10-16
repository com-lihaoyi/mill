package mill.scalajslib

import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.T

/**
 * Since Scala 3.8, the Scala.js standard library IR comes from
 * `scalajs-scalalib_2.13:<Scala 3 version>`. The Scala.js test bridge depends on
 * `scalajs-scalalib_2.13:2.13.x+<Scala.js version>` transitively; it must not shadow
 * the Scala 3 one on the test link classpath (the linker keeps the first IR file of a class).
 */
object Scala3StdlibTests extends TestSuite {

  object Scala3Stdlib extends TestRootModule with ScalaJSModule {
    override def scalaVersion = "3.10.0"
    override def scalaJSVersion = "1.22.0"

    object test extends ScalaJSTests with TestScalaJSModule.Junit4ScalaJs

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "scala3-stdlib"

  val tests: Tests = Tests {
    test("testLinksAgainstScala3Stdlib") {
      UnitTester(Scala3Stdlib, millSourcePath).scoped { evaluator =>
        val Right(result) = evaluator(Scala3Stdlib.test.testForked()).runtimeChecked
        val statuses = result.value.results.map(r => (r.selector, r.status)).sorted
        assert(statuses == Seq(
          ("app.LibTests.inTest", "Success"),
          ("app.LibTests.none", "Success"),
          ("app.LibTests.some", "Success")
        ))
      }
    }
  }
}
