package mill.scalajslib

import mill.api.ExecResult
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import mill.define.Target
import mill.T
import mill.scalajslib.api._

object EsModuleRemapTests extends TestSuite {
  val remapTo = "https://cdn.jsdelivr.net/gh/stdlib-js/array-base-linspace@esm/index.mjs"

  object EsModuleRemap extends TestBaseModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    override def scalaJSVersion = "1.16.0"

    override def scalaJSSourceMap = false

    override def moduleKind = ModuleKind.ESModule

    override def scalaJSImportMap: T[Seq[ESModuleImportMapping]] = Seq(
      ESModuleImportMapping.Prefix("@stdlib/linspace", remapTo)
    )

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  object OldJsModule extends TestBaseModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion = "1.15.0"
    override def scalaJSSourceMap = false
    override def moduleKind = ModuleKind.ESModule

    override def scalaJSImportMap: T[Seq[ESModuleImportMapping]] = Seq(
      ESModuleImportMapping.Prefix("@stdlib/linspace", remapTo)
    )

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "esModuleRemap"

  val tests: Tests = Tests {
    test("should remap the esmodule") {
      val evaluator = UnitTester(EsModuleRemap, millSourcePath)
      val Right(result) =
        evaluator(EsModuleRemap.fastLinkJS): @unchecked
      val publicModules = result.value.publicModules.toSeq
      assert(publicModules.length == 1)
      val main = publicModules.head
      assert(main.jsFileName == "main.js")
      val mainPath = result.value.dest.path / "main.js"
      assert(os.exists(mainPath))
      val rawJs = os.read.lines(mainPath)
      assert(rawJs(1).contains(remapTo))
    }

    test("should throw for older scalaJS versions") {
      val evaluator = UnitTester(EsModuleRemap, millSourcePath)
      val Left(ExecResult.Exception(ex, _)) = evaluator(OldJsModule.fastLinkJS): @unchecked
      val error = ex.getMessage
      assert(error == "scalaJSImportMap is not supported with Scala.js < 1.16.")
    }

  }
}
