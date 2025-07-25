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

      def testSuccess(module: TestRootModule, task: Task[?]): Unit = {
        captureOutput(module, task) { (result, _) =>
          val Right(_) = result: @unchecked
        }
      }

      def testFailure(module: TestRootModule, task: Task[?], errors: String*): Unit = {
        captureOutput(module, task) { (result, stderr) =>
          val Left(_) = result: @unchecked
          errors.foreach(error => assert(stderr.contains(error)))
        }
      }

      test("jdk11") {
        test("java") {
          def doTest[A](task: Task[A]): Unit =
            testFailure(JavaJdk11DoesntCompile, task, "package java.lang.runtime does not exist")

          test("compile") - doTest(JavaJdk11DoesntCompile.javamodule.compile)
          test("docJar") - doTest(JavaJdk11DoesntCompile.javamodule.docJar)
        }


        test("scala") {
          def doTest[A](task: Task[A]): Unit =
            testFailure(JavaJdk11DoesntCompile, task, "value indent is not a member of String")

          test("compile") - doTest(JavaJdk11DoesntCompile.scalamodule.compile)
          test("docJar") - doTest(JavaJdk11DoesntCompile.scalamodule.docJar)
        }
      }

      test("jdk17") {
        test("java") {
          test("compile") - testSuccess(JavaJdk17Compiles, JavaJdk17Compiles.javamodule.compile)
          test("docJar") - testSuccess(JavaJdk17Compiles, JavaJdk17Compiles.javamodule.docJar)
        }

        test("scala") {
          test("compile") - testSuccess(JavaJdk17Compiles, JavaJdk17Compiles.scalamodule.compile)
          test("docJar") - testSuccess(JavaJdk17Compiles, JavaJdk17Compiles.scalamodule.docJar)
        }
      }
    }
  }
}
