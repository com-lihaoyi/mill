package mill.scalalib

import mill.testkit.{TestRootModule, UnitTester}
import mill.api.Discover
import mill.util.TokenReaders.*
import mill.{T, Task}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object ZioBootstrapFailureTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "ziobootstrap"

  object ZioBootstrapModule extends TestRootModule {
    object app extends SbtModule {
      override def scalaVersion = "2.13.18"
      override def mvnDeps = Seq(
        mvn"dev.zio::zio:2.1.16"
      )

      object test extends SbtTests with TestModule.ZioTest {
        override def zioTestVersion: T[String] = Task { "2.1.16" }
      }
    }

    lazy val millDiscover = Discover[this.type]
  }

  override def tests: Tests = Tests {
    test("bootstrapFailureDoesNotLeakClassloader") {
      val outStream = new ByteArrayOutputStream()
      val errStream = new ByteArrayOutputStream()
      UnitTester(
        ZioBootstrapModule,
        sourceRoot = resourcePath,
        outStream = new PrintStream(outStream, true),
        errStream = new PrintStream(errStream, true)
      ).scoped { eval =>
        val checked = eval.apply(ZioBootstrapModule.app.test.testForked()).runtimeChecked
        val rendered = checked.toString +
          outStream.toString("UTF-8") +
          errStream.toString("UTF-8")
        pprint.log(rendered)
        assert(!rendered.contains("NoClassDefFoundError"))
      }
    }
  }
}
