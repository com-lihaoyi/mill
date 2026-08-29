package mill.scalalib

import mill.testkit.{TestRootModule, UnitTester}
import mill.api.Discover
import mill.api.daemon.ExecResult
import mill.util.TokenReaders.*
import mill.{T, Task}
import utest.*

// Make sure uncaught exceptions during test-framework task materialization are propagated from
// the forked test process with both the default and a custom JVM.
object ZioBootstrapFailureTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "ziobootstrap"

  trait ZioBootstrapModuleBase extends TestRootModule {
    def customJvmVersion: String
    object app extends SbtModule {
      override def scalaVersion = "2.13.18"
      override def mvnDeps = Seq(
        mvn"dev.zio::zio:2.1.16"
      )

      object test extends SbtTests with TestModule.ZioTest {
        override def zioTestVersion: T[String] = Task { "2.1.16" }
      }
      def jvmVersion = customJvmVersion
    }

    lazy val millDiscover = Discover[this.type]
  }
  object ZioBootstrapModule extends ZioBootstrapModuleBase {
    def customJvmVersion = ""
  }
  object ZioBootstrapCustomJvmModule extends ZioBootstrapModuleBase {
    def customJvmVersion = "19"
  }

  override def tests: Tests = Tests {
    test("defaultJvm") {
      UnitTester(ZioBootstrapModule, sourceRoot = resourcePath).scoped { eval =>
        val Left(ExecResult.Exception(throwable, _)) =
          eval.apply(ZioBootstrapModule.app.test.testForked()).runtimeChecked
        assert(
          throwable.toString.startsWith(
            "mill.api.daemon.Result$SerializedException: Layer initialization failed"
          ),
          throwable.getStackTrace.exists(_.getClassName == "example.FailingSpec$")
        )
      }
    }

    test("customJvm") {
      UnitTester(ZioBootstrapCustomJvmModule, sourceRoot = resourcePath).scoped { eval =>
        val Left(ExecResult.Exception(throwable, _)) =
          eval.apply(ZioBootstrapCustomJvmModule.app.test.testForked()).runtimeChecked
        assert(throwable.toString.contains("Layer initialization failed"))
      }
    }
  }
}
