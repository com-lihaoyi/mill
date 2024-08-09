package mill.scalajslib

import mill.api.Result
import mill.define.Discover
import mill.util.{TestEvaluator, TestUtil}
import utest._
import mill.define.Task
import mill.scalajslib.api._

object EsModuleRemapTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "esModuleRemap"

  val remapTo = "https://cdn.jsdelivr.net/gh/stdlib-js/array-base-linspace@esm/index.mjs"

  object EsModuleRemap extends TestUtil.BaseModule {

    object sourceMapModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = "1.16.0"
      override def scalaJSSourceMap = false
      override def moduleKind = ModuleKind.ESModule

      override def scalaJSImportMap: Task[Seq[ESModuleImportMapping]] = Seq(
        ESModuleImportMapping.Prefix("@stdlib/linspace", remapTo)
      )
    }

    object OldJsModule extends ScalaJSModule {
      override def millSourcePath = workspacePath
      override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def scalaJSVersion = "1.15.0"
      override def scalaJSSourceMap = false
      override def moduleKind = ModuleKind.ESModule

      override def scalaJSImportMap: Task[Seq[ESModuleImportMapping]] = Seq(
        ESModuleImportMapping.Prefix("@stdlib/linspace", remapTo)
      )
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "esModuleRemap"

  val evaluator = TestEvaluator.static(EsModuleRemap)

  val tests: Tests = Tests {
    prepareWorkspace()

    test("should remap the esmodule") {
      val Right((report, _)) =
        evaluator(EsModuleRemap.sourceMapModule.fastLinkJS)
      val publicModules = report.publicModules.toSeq
      assert(publicModules.length == 1)
      val main = publicModules.head
      assert(main.jsFileName == "main.js")
      val mainPath = report.dest.path / "main.js"
      assert(os.exists(mainPath))
      val rawJs = os.read.lines(mainPath)
      assert(rawJs(1).contains(remapTo))
    }

    test("should throw for older scalaJS versions") {
      val Left(Result.Exception(ex, _)) = evaluator(EsModuleRemap.OldJsModule.fastLinkJS)
      val error = ex.getMessage
      assert(error == "scalaJSImportMap is not supported with Scala.js < 1.16.")
    }

  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
