package mill.scalajslib

import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*
import mill.scalajslib.api.*
import mill.T

object WasmtimeTests extends TestSuite {

  trait ScalaWasmComponent extends ScalaJSModule {
    override def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    override def scalaJSOrganization = "io.github.scala-wasm"
    override def scalaJSVersion = "1.22.1-wasm.6"
    override def moduleKind = ModuleKind.WasmComponent
    override def esFeatures = super.esFeatures().withESVersion(ESVersion.ES2022)
    override def scalaJSUseWebAssembly: T[Boolean] = true
    override def jsEnvConfig = JsEnvConfig.Wasmtime()
  }

  object Command extends TestRootModule with ScalaWasmComponent {
    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  object WithJunit extends TestRootModule with ScalaWasmComponent {
    object test extends ScalaJSTests with TestScalaJSModule.Junit4ScalaJs

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  object OnNode extends TestRootModule with ScalaWasmComponent {
    override def jsEnvConfig = JsEnvConfig.NodeJs()

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "wasmtime"

  private def available(tool: String): Boolean =
    scala.util.Try(os.proc(tool, "--version").call(check = false).exitCode == 0)
      .getOrElse(false)

  // Linking a component needs wasm-tools; running it needs wasmtime
  private def toolsAvailable: Boolean = available("wasm-tools") && available("wasmtime")

  val tests: Tests = Tests {
    test("jsEnvDeps") {
      UnitTester(Command, resources / "ok").scoped { evaluator =>
        val Right(cp) = evaluator(Command.scalaJSLinkerClasspath).runtimeChecked
        assert(cp.value.exists(_.path.last.startsWith("scalajs-env-wasmtime_3-")))
      }
    }

    test("run") {
      if (toolsAvailable) {
        UnitTester(Command, resources / "ok").scoped { evaluator =>
          val Right(_) = evaluator(Command.run()).runtimeChecked
        }
        UnitTester(Command, resources / "fail").scoped { evaluator =>
          val Left(_) = evaluator(Command.run()).runtimeChecked
        }
      }
    }

    test("junit") {
      if (toolsAvailable) {
        UnitTester(WithJunit, resources / "junit").scoped { evaluator =>
          val Right(result) = evaluator(WithJunit.test.testForked()).runtimeChecked
          val results = result.value.results
          assert(results.map(r => (r.fullyQualifiedName, r.status)) == Seq(
            ("app.AdderTests", "Success")
          ))
        }
      }
    }

    test("componentNeedsWasmtime") {
      if (available("wasm-tools")) {
        UnitTester(OnNode, resources / "ok").scoped { evaluator =>
          val Left(failure) = evaluator(OnNode.run()).runtimeChecked
          assert(failure.toString.contains("JsEnvConfig.Wasmtime()"))
        }
      }
    }
  }
}
