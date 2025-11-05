package mill.main.gradle

import mill.main.buildgen.BuildGenChecker
import utest.*

object GradleBuildGenTests extends TestSuite {
  def tests = Tests {
    val checker = BuildGenChecker()
    test("6.0") {
      assert(checker.check(
        sourceRel = os.sub / "gradle-6-0",
        expectedRel = os.sub / "expected/gradle-6-0"
      ))
    }
    test("7.0") {
      assert(checker.check(
        sourceRel = os.sub / "gradle-7-0",
        expectedRel = os.sub / "expected/gradle-7-0"
      ))
    }
    test("8.0") {
      assert(checker.check(
        sourceRel = "gradle-8-0",
        expectedRel = os.sub / "expected/gradle-8-0"
      ))
    }
    test("9.0.0") {
      assert(checker.check(
        sourceRel = "gradle-9-0-0",
        expectedRel = os.sub / "expected/gradle-9-0-0",
        envJvmId = "zulu:21"
      ))
    }
    test("with-args") {
      test("8.0") {
        assert(checker.check(
          sourceRel = "gradle-8-0",
          expectedRel = os.sub / "expected/with-args/gradle-8-0",
          mainArgs = Seq("--merge", "--no-meta"),
          envJvmId = "zulu:17"
        ))
      }
    }
  }
}
