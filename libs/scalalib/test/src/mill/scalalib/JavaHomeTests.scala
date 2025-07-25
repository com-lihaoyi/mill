package mill.javalib

import mill.api.daemon.ExecResult
import mill.api.{Discover, Task}
import mill.scalalib.ScalaModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.TokenReaders.*
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object JavaHomeTests extends TestSuite {

  object JavaJdk11DoesntCompile extends TestRootModule {

    object javamodule extends JavaModule {
      def jvmId = "temurin:11.0.25"
    }
    object scalamodule extends ScalaModule {
      def jvmId = "temurin:11.0.25"
      def scalaVersion = "2.13.14"
    }
    lazy val millDiscover = Discover[this.type]
  }

  object JavaJdk17Compiles extends TestRootModule {
    object javamodule extends JavaModule {
      def jvmId = "temurin:17.0.13"
    }
    object scalamodule extends ScalaModule {
      def jvmId = "temurin:17.0.13"

      def scalaVersion = "2.13.14"
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("compileApis") {
      val resourcePathCompile = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "java-scala-11"

      def captureOutput[A, R](module: TestRootModule, task: Task[A])(f:
        (Either[ExecResult.Failing[A], UnitTester.Result[A]], String) => R
      ): R = {
        val errStream = new ByteArrayOutputStream()
        UnitTester(module, resourcePathCompile, errStream = new PrintStream(errStream)).scoped { eval =>
          val result = eval.apply(task)
          val stderr = errStream.toString()
          f(result, stderr)
        }
      }

      test("jdk11java") {
        captureOutput(JavaJdk11DoesntCompile, JavaJdk11DoesntCompile.javamodule.compile) { (result, stderr) =>
          val Left(_) = result: @unchecked
          assert(stderr.contains("cannot find symbol"))
          assert(stderr.contains("method indent"))
        }
      }

      test("jdk17java") {
        captureOutput(JavaJdk17Compiles, JavaJdk17Compiles.javamodule.compile) { (result, _) =>
          val Right(_) = result: @unchecked
        }
      }

      test("jdk11scala") {
        captureOutput(JavaJdk11DoesntCompile, JavaJdk11DoesntCompile.scalamodule.compile) { (result, stderr) =>
          val Left(_) = result: @unchecked
          assert(stderr.contains("value indent is not a member of String"))
        }
      }

      test("jdk17scala") {
        captureOutput(JavaJdk17Compiles, JavaJdk17Compiles.scalamodule.compile) { (result, _) =>
          val Right(_) = result: @unchecked
        }
      }
    }

  }
}
