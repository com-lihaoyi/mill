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
      val Right((report, _)) =
        evaluator(TopLevelExportsModule.topLevelExportsModule.fastLinkJS)
      val publicModules = report.publicModules.toSeq
      assert(publicModules.length == 2)
      val b = publicModules(0)
      assert(os.exists(b.jsFile.path))
      assert(b.jsFile.path.last == "b.js")
      assert(os.exists(b.jsFile.path / os.up / "b.js.map"))
      val a = publicModules(1)
      assert(os.exists(a.jsFile.path))
      assert(a.jsFile.path.last == "a.js")
      assert(os.exists(a.jsFile.path / os.up / "a.js.map"))
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
