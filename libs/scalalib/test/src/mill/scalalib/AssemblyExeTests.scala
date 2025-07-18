package mill.scalalib


import mill.api.Task.Simple as T
import mill.api.{Discover, ExecResult, Module, Task}

import mill.testkit.{TestRootModule, UnitTester}
import mill.util.Jvm
import utest.*
import mill.*

// Ensure the assembly is runnable, even if we have assembled lots of dependencies into it
// Reproduction of issues:
// - https://github.com/com-lihaoyi/mill/issues/528
// - https://github.com/com-lihaoyi/mill/issues/2650

object AssemblyExeTests extends TestSuite {


  object TestCase extends TestRootModule {

    trait ExtraDeps extends ScalaModule {
      def scalaVersion = "2.13.11"

      def sources = Task.Sources(mill.api.BuildCtx.workspaceRoot / "src")

      def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"com.lihaoyi::scalatags:0.8.2",
        mvn"com.lihaoyi::mainargs:0.4.0",
        mvn"org.apache.avro:avro:1.11.1",
        mvn"org.typelevel::cats-core:2.9.0",
        mvn"org.apache.spark::spark-core:3.4.0"
      )
    }

    object noExe extends ExtraDeps {
      override def prependShellScript: T[String] = ""
    }

    object exe extends ExtraDeps

    lazy val millDiscover = Discover[this.type]
  }

  val sources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "assembly"


  def tests: Tests = Tests {
    test("exe") {
      UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
        val Left(ExecResult.Failure(msg)) =
          eval(TestCase.exe.assembly): @unchecked
        val expectedMsg =
          """The created assembly jar contains more than 65535 ZIP entries.
            |JARs of that size are known to not work correctly with a prepended shell script.
            |Either reduce the entries count of the assembly or disable the prepended shell script with:
            |
            |  def prependShellScript = ""
            |""".stripMargin
        assert(msg == expectedMsg)

      }
    }
    test("noExe") {
      UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
        val Right(result) = eval(TestCase.noExe.assembly): @unchecked
        os.call(
          cmd = (Jvm.javaExe, "-jar", result.value.path, "--text", "tutu"),
          env = Map.empty[String, String],
          cwd = TestCase.moduleDir,
          stdin = os.Inherit,
          stdout = os.Inherit
        )

      }
    }
  }
}
