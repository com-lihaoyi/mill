package mill.scalajslib

import mill.*
import mill.api.Discover
import mill.scalajslib.api.*
import mill.scalalib.TestModule
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object WasmModuleTests extends TestSuite {
  object Modules extends TestRootModule {
    trait Base extends ScalaJSModule {
      def scalaVersion = "2.13.18"
      def scalaJSVersion = "1.23.0"
      override def sources = Task.Sources(Modules.moduleDir / "src")
      override def mainClass = Some("app.App")
    }
    object bare extends Base {
      override def moduleKind = ModuleKind.WasmModule
      override def sources = Task.Sources(Modules.moduleDir / "src", Modules.moduleDir / "exports")
      object test extends ScalaJSTests with TestModule.Utest {
        def utestVersion = "0.8.9"
      }
    }
    object descriptors extends Base {
      override def sources = Task.Sources(Modules.moduleDir / "src", Modules.moduleDir / "exports")
      override def moduleKind = ModuleKind.WasmModule
      override def scalaJSExperimentalUseCustomDescriptors = true
      object test extends ScalaJSTests with TestModule.Utest {
        def utestVersion = "0.8.9"
      }
    }
    object es extends Base {
      override def sources = Task.Sources(Modules.moduleDir / "js-exports")
      override def moduleKind = ModuleKind.ESModule
      override def scalaJSUseWebAssembly = true
      override def esFeatures = super.esFeatures().withESVersion(ESVersion.ES2022)
      override def scalaJSExperimentalUseCustomDescriptors = true
    }
    object js extends Base {
      object test extends ScalaJSTests with TestModule.Utest {
        def utestVersion = "0.8.9"
      }
    }
    object old extends Base {
      override def scalaJSVersion = "1.22.0"
      override def moduleKind = ModuleKind.WasmModule
    }
    object oldDescriptors extends Base {
      override def scalaJSVersion = "1.22.0"
      override def moduleKind = ModuleKind.ESModule
      override def scalaJSUseWebAssembly = true
      override def esFeatures = super.esFeatures().withESVersion(ESVersion.ES2022)
      override def scalaJSExperimentalUseCustomDescriptors = true
    }
    override lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wasm-module"

  def checkWasm(report: Report): Unit = {
    val main = report.publicModules.find(_.moduleID == "main").get
    assert(main.moduleKind == ModuleKind.WasmModule)
    assert(main.jsFileName == "main.wasm")
    val dest = report.dest.path
    assert(os.read.bytes(dest / main.jsFileName).take(8).toSeq ==
      Seq[Byte](0, 97, 115, 109, 1, 0, 0, 0))
    assert(os.exists(dest / "main.wasm.map"))
    assert(!os.list(dest).exists(p => p.ext == "js" || p.ext == "mjs"))
  }

  val tests = Tests {
    test("bare fast and full linking") {
      UnitTester(Modules, resourcePath).scoped { eval =>
        val Right(fast) = eval(Modules.bare.fastLinkJS).runtimeChecked
        checkWasm(fast.value)
        val Right(full) = eval(Modules.bare.fullLinkJS).runtimeChecked
        checkWasm(full.value)
      }
    }
    test("Custom Descriptors link in both Wasm modes") {
      UnitTester(Modules, resourcePath).scoped { eval =>
        val Right(bare) = eval(Modules.descriptors.fastLinkJS).runtimeChecked
        checkWasm(bare.value)
        val Right(es) = eval(Modules.es.fastLinkJS).runtimeChecked
        val main = es.value.publicModules.head
        assert(main.moduleKind == ModuleKind.ESModule)
        assert(main.jsFileName == "main.js")
        assert(os.exists(es.value.dest.path / "main.wasm"))
        val Right(inherited) =
          eval(Modules.descriptors.test.scalaJSExperimentalUseCustomDescriptors).runtimeChecked
        assert(inherited.value)
      }
    }
    test("old linker rejects new options with diagnostics") {
      UnitTester(Modules, resourcePath).scoped { eval =>
        val Left(bare) = eval(Modules.old.fastLinkJS).runtimeChecked
        assert(bare.toString.contains("ModuleKind.WasmModule requires Scala.js >= 1.23.0"))
        val Left(descriptors) = eval(Modules.oldDescriptors.fastLinkJS).runtimeChecked
        assert(descriptors.toString.contains(
          "scalaJSExperimentalUseCustomDescriptors requires Scala.js >= 1.23.0"
        ))
      }
    }
    test("Scala.js 1.23 JavaScript run and test remain supported") {
      UnitTester(Modules, resourcePath).scoped { eval =>
        val Right(_) = eval(Modules.js.run()).runtimeChecked
        val Right(result) = eval(Modules.js.test.testForked()).runtimeChecked
        assert(result.value.results.nonEmpty)
        assert(result.value.results.forall(_.status == "Success"))
      }
    }
    test("JavaScript run and test environments reject bare Wasm") {
      UnitTester(Modules, resourcePath).scoped { eval =>
        val Left(run) = eval(Modules.bare.run()).runtimeChecked
        assert(run.toString.contains("Running or testing a ModuleKind.WasmModule"))
        val Left(testLink) = eval(Modules.bare.test.fastLinkJSTest).runtimeChecked
        assert(testLink.toString.contains("Running or testing a ModuleKind.WasmModule"))
      }
    }
  }
}
