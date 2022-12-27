package mill.scalajslib

import mill._
import mill.define.Discover
import mill.scalajslib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.define.Target

object SourceMapTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "source-map"

  object SourceMapModule extends TestUtil.BaseModule {

    object sourceMapModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = "1.8.0"
      override def scalaJSSourceMap = false
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val evaluator = TestEvaluator.static(SourceMapModule)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("should disable source maps") {
      val Right((report, _)) =
        evaluator(SourceMapModule.sourceMapModule.fastLinkJS)
      val publicModules = report.publicModules.toSeq
      assert(publicModules.length == 1)
      val main = publicModules.head
      assert(main.jsFileName == "main.js")
      assert(os.exists(report.dest.path / "main.js"))
      assert(!os.exists(report.dest.path / "main.js.map"))
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
