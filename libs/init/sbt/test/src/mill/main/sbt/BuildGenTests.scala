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
        "-t",
        "tests",
        "-D",
        "Dependencies"
      )
      test("sbt-multi-project-example") {
        val sourceRoot = os.sub / "sbt-multi-project-example"
        test("all") {
          val expectedRoot = os.sub / "expected/config/all/sbt-multi-project-example"
          assert(
            checker.check(SbtBuildGenMain.main(commonArgs), sourceRoot, expectedRoot)
          )
        }
      }
    }
  }
}
