package mill.main.sbt

import utest.*

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    /* TODO enable tests after finalizing imported tasks and main args
    val checker = BuildGenChecker()

    test("scala-seed-project") {
      val sourceRoot = os.sub / "scala-seed-project"
      val expectedRoot = os.sub / "expected/scala-seed-project"
      assert(
        checker.check(SbtBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }

    // from https://github.com/pbassiner/sbt-multi-project-example/tree/master
    test("sbt-multi-project-example") {
      val sourceRoot = os.sub / "sbt-multi-project-example"
      val expectedRoot = os.sub / "expected/sbt-multi-project-example"
      assert(
        checker.check(SbtBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }

    test("config") {
      val commonArgs = Array(
        "--base-module",
        "BaseModule",
        "--jvm-id",
        "11",
        "--test-module",
        "tests",
        "--deps-object",
        "Deps",
        "--merge"
      )
      test("sbt-multi-project-example") {
        val sourceRoot = os.sub / "sbt-multi-project-example"
        test("without-base-project") {
          val expectedRoot =
            os.sub / "expected/config/without-base-project/sbt-multi-project-example"
          val args = commonArgs
          assert(
            checker.check(SbtBuildGenMain.main(args), sourceRoot, expectedRoot)
          )
        }
        test("all") {
          val expectedRoot = os.sub / "expected/config/all/sbt-multi-project-example"
          val args = commonArgs ++ Array("--baseProject", "common")
          assert(
            checker.check(SbtBuildGenMain.main(args), sourceRoot, expectedRoot)
          )
        }
      }
    }
     */
  }
}
