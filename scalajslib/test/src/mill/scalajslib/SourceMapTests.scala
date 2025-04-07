package mill.scalajslib

import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object SourceMapTests extends TestSuite {
  object SourceMapModule extends TestBaseModule {

    object build extends ScalaJSModule {
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion =
        sys.props.getOrElse("TEST_SCALAJS_VERSION", ???) // at least 1.8.0
      override def scalaJSSourceMap = false
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  val evaluator = UnitTester(SourceMapModule, millSourcePath)

  val tests: Tests = Tests {
    test("should disable source maps") {
      val Right(result) =
        evaluator(SourceMapModule.build.fastLinkJS): @unchecked
      val publicModules = result.value.publicModules.toSeq
      assert(publicModules.length == 1)
      val main = publicModules.head
      assert(main.jsFileName == "main.js")
      assert(os.exists(result.value.dest.path / "main.js"))
      assert(!os.exists(result.value.dest.path / "main.js.map"))
    }
  }
}
