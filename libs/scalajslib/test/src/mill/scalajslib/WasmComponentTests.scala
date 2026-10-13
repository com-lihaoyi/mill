package mill.scalajslib

import mill.api.Discover
import mill.api.ExecResult
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.scalajslib.api.*
import mill.T

object WasmComponentTests extends TestSuite {

  /** A module linked by scala-wasm, a Scala.js fork that implements the Component Model */
  object ScalaWasm extends TestRootModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSOrganization = "io.github.scala-wasm"
    override def scalaJSVersion = "1.22.1-wasm.6"
    override def moduleKind = ModuleKind.WasmComponent
    override def esFeatures = super.esFeatures().withESVersion(ESVersion.ES2022)
    override def scalaJSUseWebAssembly: T[Boolean] = true

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  /** Upstream Scala.js has no `ModuleKind.WasmComponent` */
  object Upstream extends TestRootModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSVersion = "1.22.0"
    override def moduleKind = ModuleKind.WasmComponent
    override def esFeatures = super.esFeatures().withESVersion(ESVersion.ES2022)
    override def scalaJSUseWebAssembly: T[Boolean] = true

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  object NoWasmBackend extends TestRootModule with ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSOrganization = "io.github.scala-wasm"
    override def scalaJSVersion = "1.22.1-wasm.6"
    override def moduleKind = ModuleKind.WasmComponent

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wasm-component"
  // plain Scala.js sources, without scala-wasm's WIT annotations
  val upstreamSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wasm"

  private def failureMessage(result: Either[ExecResult.Failing[?], ?]): String =
    result match {
      case Left(failing) => failing.toString
      case Right(value) => throw new java.lang.AssertionError(s"expected a failure, got $value")
    }

  private def wasmToolsAvailable: Boolean =
    scala.util.Try(os.proc("wasm-tools", "--version").call(check = false).exitCode == 0)
      .getOrElse(false)

  val tests: Tests = Tests {
    test("scalaJSOrganization") {
      UnitTester(ScalaWasm, millSourcePath).scoped { evaluator =>
        val Right(deps) = evaluator(ScalaWasm.mandatoryMvnDeps).runtimeChecked
        val scalaJSDeps = deps.value.filter(_.name.startsWith("scalajs-"))
        assert(scalaJSDeps.nonEmpty)
        assert(scalaJSDeps.forall(_.organization == "io.github.scala-wasm"))

        val Right(linkerCp) = evaluator(ScalaWasm.scalaJSLinkerClasspath).runtimeChecked
        val linkerJars = linkerCp.value.map(_.path.last)
        assert(linkerJars.contains("scalajs-linker_2.13-1.22.1-wasm.6.jar"))
        assert(linkerCp.value.exists { p =>
          p.path.last == "scalajs-linker_2.13-1.22.1-wasm.6.jar" &&
          p.path.segments.contains("scala-wasm")
        })
      }
    }

    test("linkWasmComponent") {
      UnitTester(ScalaWasm, millSourcePath).scoped { evaluator =>
        val result = evaluator(ScalaWasm.fastLinkJS)
        if (wasmToolsAvailable) {
          val Right(report) = result.runtimeChecked
          val main = report.value.publicModules.head
          assert(main.moduleKind == ModuleKind.WasmComponent)
          val bytes = os.read.bytes(report.value.dest.path / "main.wasm")
          // `\0asm` magic, then the Component Model layer (0x0d 0x00 0x01 0x00);
          // a core Wasm module would have version 0x01 0x00 0x00 0x00 instead
          assert(bytes.take(4).sameElements(Array[Byte](0, 'a', 's', 'm')))
          assert(bytes.slice(4, 8).sameElements(Array[Byte](0x0d, 0x00, 0x01, 0x00)))
          // The component exports the `@WitExport` function of the test sources
          val wit = os.proc("wasm-tools", "component", "wit", report.value.dest.path / "main.wasm")
            .call().out.text()
          assert(wit.contains("export mill:test/adder@0.1.0;"))
          assert(wit.contains("add: func(a: s32, b: s32) -> s32;"))
        } else {
          // scala-wasm componentizes with `wasm-tools component new`, which is only
          // ever invoked for `ModuleKind.WasmComponent`
          val msg = failureMessage(result)
          assert(msg.contains("wasm-tools"))
        }
      }
    }

    test("upstreamScalaJSRejectsWasmComponent") {
      UnitTester(Upstream, upstreamSourcePath).scoped { evaluator =>
        val msg = failureMessage(evaluator(Upstream.fastLinkJS))
        assert(msg.contains("ModuleKind.WasmComponent is not supported by Scala.js 1.22.0"))
      }
    }

    test("wasmComponentRequiresWasmBackend") {
      UnitTester(NoWasmBackend, millSourcePath).scoped { evaluator =>
        val msg = failureMessage(evaluator(NoWasmBackend.fastLinkJS))
        assert(msg.contains("requires the WebAssembly backend"))
      }
    }
  }
}
