package mill.scalajslib

import mill.api.Discover
import mill.scalajslib.api._
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest._

object OutputPatternsTests extends TestSuite {

  object OutputPatternsModule extends TestRootModule {

    object build extends ScalaJSModule {
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion =
        sys.props.getOrElse("TEST_SCALAJS_VERSION", ???) // at least "1.12.0"
      override def moduleKind = ModuleKind.CommonJSModule
      override def scalaJSOutputPatterns = OutputPatterns.fromJSFile("%s.mjs")
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-js-world"

  val tests: Tests = Tests {
    test("output patterns") {
      UnitTester(OutputPatternsModule, millSourcePath).scoped { evaluator =>
        val Right(result) =
          evaluator(OutputPatternsModule.build.fastLinkJS): @unchecked
        val publicModules = result.value.publicModules.toSeq
        assert(publicModules.length == 1)
        val main = publicModules(0)
        assert(main.jsFileName == "main.mjs")
        assert(os.exists(result.value.dest.path / "main.mjs"))
        assert(main.sourceMapName == Some("main.mjs.map"))
        assert(os.exists(result.value.dest.path / "main.mjs.map"))
      }
    }
  }
}
