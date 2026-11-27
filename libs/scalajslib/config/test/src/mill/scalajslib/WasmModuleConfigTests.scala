package mill.scalajslib.config

import mill.*
import mill.api.Discover
import mill.scalajslib.api.*
import mill.scalalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object WasmModuleConfigTests extends TestSuite {
  object Module extends TestRootModule with ScalaJSConfigModule {
    def scalaVersion = "2.13.18"
    override def moduleKind = ModuleKind.WasmModule
    override def scalaJSExperimentalUseCustomDescriptors = true
    override def mainClass = Some("app.App")
    object test extends ScalaJSConfigTests with TestModule.Utest {
      def utestVersion = "0.8.9"
    }
    lazy val millDiscover = Discover[this.type]
  }
  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wasm-module"

  val tests = Tests {
    test("fast and full linking preserve Wasm module reports") {
      UnitTester(Module, resourcePath).scoped { eval =>
        val Right(config) = eval(Module.scalaJSConfig).runtimeChecked
        assert(config.value.wasmFeatures.experimentalUseCustomDescriptors)
        for (task <- Seq(Module.fastLinkJS, Module.fullLinkJS)) {
          val Right(result) = eval(task).runtimeChecked
          val main = result.value.publicModules.head
          assert(main.moduleKind == ModuleKind.WasmModule)
          assert(main.jsFileName == "main.wasm")
          assert(os.read.bytes(result.value.dest.path / main.jsFileName).take(8).toSeq ==
            Seq[Byte](0, 97, 115, 109, 1, 0, 0, 0))
          assert(!os.list(result.value.dest.path).exists(_.ext == "js"))
        }
      }
    }
    test("custom config rejects JavaScript run and test environments") {
      UnitTester(Module, resourcePath).scoped { eval =>
        val Left(run) = eval(Module.run()).runtimeChecked
        assert(run.toString.contains("Running or testing a ModuleKind.WasmModule"))
        val Left(testLink) = eval(Module.test.fastLinkJSTest).runtimeChecked
        assert(testLink.toString.contains("Running or testing a ModuleKind.WasmModule"))
      }
    }
  }
}
