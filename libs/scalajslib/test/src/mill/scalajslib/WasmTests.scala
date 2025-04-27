package mill.scalajslib

import mill.api.ExecResult
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import mill.scalajslib.api._
import mill.T

object WasmTests extends TestSuite {
  val remapTo = "https://cdn.jsdelivr.net/gh/stdlib-js/array-base-linspace@esm/index.mjs"

  object Wasm extends TestBaseModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    override def scalaJSVersion = "1.17.0"

    override def moduleKind = ModuleKind.ESModule

    override def moduleSplitStyle = ModuleSplitStyle.FewestModules

    override def scalaJSExperimentalUseWebAssembly: T[Boolean] = true

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  object OldWasmModule extends TestBaseModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion = "1.16.0"

    override def moduleKind = ModuleKind.ESModule
    override def moduleSplitStyle = ModuleSplitStyle.FewestModules

    override def scalaJSExperimentalUseWebAssembly: T[Boolean] = true

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wasm"

  val tests: Tests = Tests {
    test("should emit wasm") {
      val evaluator = UnitTester(Wasm, millSourcePath)
      val Right(result) =
        evaluator(Wasm.fastLinkJS): @unchecked
      val publicModules = result.value.publicModules.toSeq
      val path = result.value.dest.path
      val main = publicModules.head
      assert(main.jsFileName == "main.js")
      val mainPath = path / "main.js"
      assert(os.exists(mainPath))
      val wasmPath = path / "main.wasm"
      assert(os.exists(wasmPath))
      val wasmMapPath = path / "main.wasm.map"
      assert(os.exists(wasmMapPath))
    }

    test("wasm is runnable") {
      val evaluator = UnitTester(Wasm, millSourcePath)
      val Right(result) = evaluator(Wasm.fastLinkJS): @unchecked
      val path = result.value.dest.path
      os.proc("node", "--experimental-wasm-exnref", "main.js").call(
        cwd = path,
        check = true,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit
      )

    }

    test("should throw for older scalaJS versions") {
      val evaluator = UnitTester(OldWasmModule, millSourcePath)
      val Left(ExecResult.Exception(ex, _)) = evaluator(OldWasmModule.fastLinkJS): @unchecked
      val error = ex.getMessage
      assert(error == "Emitting wasm is not supported with Scala.js < 1.17")
    }

  }
}
