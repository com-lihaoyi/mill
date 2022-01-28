package mill.scalajslib

import mill._
import mill.define.Discover
import mill.scalajslib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

object TopLevelExportsTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "top-level-exports"

  object TopLevelExportsModule extends TestUtil.BaseModule {

    object topLevelExportsModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = "1.8.0"
      override def moduleKind = ModuleKind.ESModule
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "top-level-exports"

  val evaluator = TestEvaluator.static(TopLevelExportsModule)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("top level exports") {
      println(evaluator(TopLevelExportsModule.topLevelExportsModule.sources))
      val Right((PathRef(outFile, _, _), _)) =
        evaluator(TopLevelExportsModule.topLevelExportsModule.fastOpt)
      assert(os.exists(outFile))
      assert(os.exists(outFile / os.up / "a.js"))
      assert(os.exists(outFile / os.up / "a.js.map"))
      assert(os.exists(outFile / os.up / "b.js"))
      assert(os.exists(outFile / os.up / "b.js.map"))
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
