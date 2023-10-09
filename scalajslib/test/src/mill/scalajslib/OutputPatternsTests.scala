package mill.scalajslib

import mill.define.Discover
import mill.scalajslib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

object OutputPatternsTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-js-world"

  object OutputPatternsModule extends TestUtil.BaseModule {

    object outputPatternsModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = "1.12.0"
      override def moduleKind = ModuleKind.CommonJSModule
      override def scalaJSOutputPatterns = OutputPatterns.fromJSFile("%s.mjs")
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val evaluator = TestEvaluator.static(OutputPatternsModule)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("output patterns") {
      val Right((report, _)) =
        evaluator(OutputPatternsModule.outputPatternsModule.fastLinkJS)
      val publicModules = report.publicModules.toSeq
      assert(publicModules.length == 1)
      val main = publicModules(0)
      assert(main.jsFileName == "main.mjs")
      assert(os.exists(report.dest.path / "main.mjs"))
      assert(main.sourceMapName == Some("main.mjs.map"))
      assert(os.exists(report.dest.path / "main.mjs.map"))
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
