package mill.main.sbt

import mill.main.buildgen.BuildGenChecker
import utest.*

object SbtBuildGenTests extends TestSuite {
  def tests: Tests = Tests {
    val checker = BuildGenChecker()
    test("scala-seed-project") {
      assert(checker.check(
        generate = SbtBuildGenMain.main(Array.empty[String]),
        sourceRel = os.sub / "scala-seed-project",
        expectedRel = os.sub / "expected/scala-seed-project"
      ))
    }
    // from https://github.com/pbassiner/sbt-multi-project-example/tree/master
    test("sbt-multi-project-example") {
      assert(checker.check(
        generate = SbtBuildGenMain.main(Array.empty[String]),
        sourceRel = os.sub / "sbt-multi-project-example",
        expectedRel = "expected/sbt-multi-project-example"
      ))
    }
    test("cross-version") {
      assert(checker.check(
        generate = SbtBuildGenMain.main(Array.empty[String]),
        sourceRel = os.sub / "cross-version",
        expectedRel = os.sub / "expected/cross-version"
      ))
    }
    test("crossproject") {
      assert(checker.check(
        generate = SbtBuildGenMain.main(Array.empty[String]),
        sourceRel = os.sub / "crossproject",
        expectedRel = os.sub / "expected/crossproject"
      ))
    }
    test("crossproject-cross-version") {
      assert(checker.check(
        generate = SbtBuildGenMain.main(Array.empty[String]),
        sourceRel = os.sub / "crossproject-cross-version",
        expectedRel = os.sub / "expected/crossproject-cross-version"
      ))
    }
    test("with-args") {
      test("crossproject-cross-version") {
        assert(checker.check(
          generate = SbtBuildGenMain.main(Array("--merge", "--no-meta")),
          sourceRel = os.sub / "crossproject-cross-version",
          expectedRel = os.sub / "expected/with-args/crossproject-cross-version"
        ))
      }
    }
  }
}
