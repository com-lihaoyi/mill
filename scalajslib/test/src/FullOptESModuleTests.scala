package mill.scalajslib

import mill._
import mill.define.Discover
import mill.scalajslib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

object FullOptESModuleTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-js-world"

  object FullOptESModuleModule extends TestUtil.BaseModule {
    override def millSourcePath = workspacePath

    object fullOptESModuleModule extends ScalaJSModule {
      override def scalaVersion = "2.13.4"
      override def scalaJSVersion = "1.7.0"
      override def moduleKind = ModuleKind.ESModule
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val fullOptESModuleModuleEvaluator = TestEvaluator.static(FullOptESModuleModule)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("fullOpt with ESModule moduleKind") {
      val result =
        fullOptESModuleModuleEvaluator(FullOptESModuleModule.fullOptESModuleModule.fullOpt)
      assert(result.isRight)
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
