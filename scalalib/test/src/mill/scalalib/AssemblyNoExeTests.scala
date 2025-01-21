package mill.scalalib

import mill.testkit.{UnitTester, TestBaseModule}
import utest._

object AssemblyNoExeTests extends TestSuite with AssemblyTestUtils {

  def tests: Tests = Tests {
    test("Assembly") {
      test("noExe") {
        test("small") - UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
          val Right(result) = eval(TestCase.noExe.small.assembly)
          runAssembly(result.value.path, TestCase.millSourcePath)

        }
        test("large") - UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
          val Right(result) = eval(TestCase.noExe.large.assembly)
          runAssembly(result.value.path, TestCase.millSourcePath)

        }
      }
    }
  }
}
