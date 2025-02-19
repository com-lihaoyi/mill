package mill.scalalib

import mill.api.ExecResult
import scala.util.Properties
import mill.testkit.UnitTester
import utest._

// Ensure the assembly is runnable, even if we have assembled lots of dependencies into it
// Reproduction of issues:
// - https://github.com/com-lihaoyi/mill/issues/528
// - https://github.com/com-lihaoyi/mill/issues/2650

object AssemblyExeTests extends TestSuite with AssemblyTestUtils {

  def tests: Tests = Tests {
    test("Assembly") {
      test("exe") {
        test("small") - UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
          val Right(result) = eval(TestCase.exe.small.assembly): @unchecked
          val originalPath = result.value.path
          val resolvedPath =
            if (Properties.isWin) {
              val winPath = originalPath / os.up / s"${originalPath.last}.bat"
              os.copy(originalPath, winPath)
              winPath
            } else originalPath
          runAssembly(resolvedPath, TestCase.moduleDir, checkExe = true)
        }

        test("large-should-fail") - UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
          val Left(ExecResult.Failure(msg)) =
            eval(TestCase.exe.large.assembly): @unchecked
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
    }
  }
}
