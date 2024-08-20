package mill.scalajslib

import mill.define.Discover
import mill.testkit.TestEvaluator
import mill.testkit.MillTestKit
import utest._

object SourceMapTests extends TestSuite {
  val workspacePath = MillTestKit.getOutPathStatic() / "source-map"

  object SourceMapModule extends mill.testkit.BaseModule {

    object sourceMapModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion =
        sys.props.getOrElse("TEST_SCALAJS_VERSION", ???) // at least 1.8.0
      override def scalaJSSourceMap = false
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val evaluator = TestEvaluator.static(SourceMapModule)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("should disable source maps") {
      val Right(result) =
        evaluator(SourceMapModule.sourceMapModule.fastLinkJS)
      val publicModules = result.value.publicModules.toSeq
      assert(publicModules.length == 1)
      val main = publicModules.head
      assert(main.jsFileName == "main.js")
      assert(os.exists(result.value.dest.path / "main.js"))
      assert(!os.exists(result.value.dest.path / "main.js.map"))
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
