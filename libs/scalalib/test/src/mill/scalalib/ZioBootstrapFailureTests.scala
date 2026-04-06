package mill.scalalib

import mill.testkit.{TestRootModule, UnitTester}
import mill.api.Discover
import mill.api.daemon.ExecResult
import mill.util.TokenReaders.*
import mill.{T, Task}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

// Make sure uncaught exceptions in test discovery, which takes place within a classloader
// or in a subprocess JvmWorker,
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
  object ZioBootstrapModule2 extends ZioBootstrapModuleBase {
    def customJvmVersion = "19"
  }

  override def tests: Tests = Tests {
    test("classloader") {
      UnitTester(ZioBootstrapModule, sourceRoot = resourcePath).scoped { eval =>
        val Left(ExecResult.Exception(throwable, _)) =
          eval.apply(ZioBootstrapModule.app.test.testForked()).runtimeChecked
        assert(
          throwable.toString ==
            "mill.api.daemon.Result$SerializedException: Layer initialization failed"
        )
      }
    }

    test("subprocess") {
      UnitTester(ZioBootstrapModule2, sourceRoot = resourcePath).scoped { eval =>
        val Left(ExecResult.Exception(throwable, _)) =
          eval.apply(ZioBootstrapModule2.app.test.testForked()).runtimeChecked
        assert(throwable.toString.contains("Layer initialization failed"))
      }
    }
  }
}
