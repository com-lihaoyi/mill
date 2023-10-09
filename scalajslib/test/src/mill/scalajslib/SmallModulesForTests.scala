package mill.scalajslib

import mill.define.Discover
import mill.scalajslib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

object SmallModulesForTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "small-modules-for"

  object SmallModulesForModule extends TestUtil.BaseModule {

    object smallModulesForModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = "1.10.0"
      override def moduleKind = ModuleKind.ESModule
      override def moduleSplitStyle = ModuleSplitStyle.SmallModulesFor(List("app"))
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "small-modules-for"

  val evaluator = TestEvaluator.static(SmallModulesForModule)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("ModuleSplitStyle.SmallModulesFor") {
      println(evaluator(SmallModulesForModule.smallModulesForModule.sources))
      val Right((report, _)) =
        evaluator(SmallModulesForModule.smallModulesForModule.fastLinkJS)
      val publicModules = report.publicModules
      test("it should have a single publicModule") {
        assert(publicModules.size == 1)
      }
      val mainModule = publicModules.head
      val modulesLength = os.list(report.dest.path).length
      test("my.Foo should not have its own file since it is in a separate package") {
        assert(!os.exists(report.dest.path / "otherpackage.Foo.js"))
      }
      assert(modulesLength == 10)
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
