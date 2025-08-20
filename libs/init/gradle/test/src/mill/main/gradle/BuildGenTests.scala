package mill.main.gradle

import mill.main.buildgen.BuildGenChecker
import utest.*

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()

    test("library") {
      val sourceRoot = os.sub / "library"
      val expectedRoot = os.sub / "expected/library"
      assert(
        checker.check(GradleBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }

    test("application-library") {
      val sourceRoot = os.sub / "application-library"
      val expectedRoot = os.sub / "expected/application-library"
      assert(
        checker.check(GradleBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }

    test("config") {
      val sourceRoot = os.sub / "application-library"
      val expectedRoot = os.sub / "expected/config"
      val args = Array(
        "--test-module-name",
        "tests",
        "--unify",
        "--no-meta-build"
      )
      assert(
        checker.check(GradleBuildGenMain.main(args), sourceRoot, expectedRoot)
      )
    }
  }
}
