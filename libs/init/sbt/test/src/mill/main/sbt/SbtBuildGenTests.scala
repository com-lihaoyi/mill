package mill.main.sbt

import mill.main.buildgen.BuildGenChecker
import utest.*

object SbtBuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()
    val noArgs = Array.empty[String]
    val args = Array("--merge", "--no-meta")

    test("scala-seed-project") {
      val projectRoot = os.sub / "scala-seed-project"
      assert(
        checker.check(
          generate = SbtBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/scala-seed-project"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = SbtBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/scala-seed-project"
          )
        )
      }
    }
    // from https://github.com/pbassiner/sbt-multi-project-example/tree/master
    test("sbt-multi-project-example") {
      val projectRoot = os.sub / "sbt-multi-project-example"
      assert(
        checker.check(
          generate = SbtBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = "expected/sbt-multi-project-example"
        )
      )
      test("sbt-multi-project-example") {
        assert(
          checker.check(
            generate = SbtBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = "expected-with-args/sbt-multi-project-example"
          )
        )
      }
    }
    test("cross-version") {
      val projectRoot = os.sub / "cross-version"
      assert(
        checker.check(
          generate = SbtBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/cross-version"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = SbtBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/cross-version"
          )
        )
      }
    }
    test("crossproject") {
      val projectRoot = os.sub / "crossproject"
      assert(
        checker.check(
          generate = SbtBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/crossproject"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = SbtBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/crossproject"
          )
        )
      }
    }
    test("crossproject-cross-version") {
      val projectRoot = os.sub / "crossproject-cross-version"
      assert(
        checker.check(
          generate = SbtBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/crossproject-cross-version"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = SbtBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/crossproject-cross-version"
          )
        )
      }
    }
  }
}
