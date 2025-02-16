package mill.main.sbt

import mill.main.buildgen.BuildGenChecker
import utest.*

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()

    test("scala-seed-project") {
      val sourceRoot = os.sub / "scala-seed-project"
      val expectedRoot = os.sub / "expected/scala-seed-project"
      assert(
        checker.check(SbtBuildGenMain.main(Array.empty), sourceRoot, expectedRoot, true)
      )
    }

    // from https://github.com/pbassiner/sbt-multi-project-example/tree/master
    test("sbt-multi-project-example") {
      val sourceRoot = os.sub / "sbt-multi-project-example"
      val expectedRoot = os.sub / "expected/sbt-multi-project-example"
      assert(
        checker.check(SbtBuildGenMain.main(Array.empty), sourceRoot, expectedRoot, true)
      )
    }

    /*
    test("config") {
      val sourceRoot = os.sub / "application-library"
      val expectedRoot = os.sub / "expected/config"
      val args = Array(
        "--base-module",
        "BaseModule",
        "--base-project",
        "utilities",
        "--jvm-id",
        "11",
        "--test-module",
        "tests",
        "--deps-object",
        "Deps",
        "--merge"
      )
      assert(
        checker.check(GradleBuildGenMain.main(args), sourceRoot, expectedRoot)
      )
    }
     */
  }
}
