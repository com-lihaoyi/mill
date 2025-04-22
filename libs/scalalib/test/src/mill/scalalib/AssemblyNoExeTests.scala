package mill.scalalib

import mill.testkit.UnitTester
import utest._

object AssemblyNoExeTests extends TestSuite with AssemblyTestUtils {

  def tests: Tests = Tests {
    test("Assembly") {
      test("noExe") {
        test("small") - UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
          val Right(result) = eval(TestCase.noExe.small.assembly): @unchecked
          runAssembly(result.value.path, TestCase.moduleDir)

        }
        test("large") - UnitTester(TestCase, sourceRoot = sources).scoped { eval =>
          val Right(result) = eval(TestCase.noExe.large.assembly): @unchecked
          runAssembly(result.value.path, TestCase.moduleDir)

        }
      }
    }
  }
}
