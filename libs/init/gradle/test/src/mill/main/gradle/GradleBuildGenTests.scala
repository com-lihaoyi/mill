package mill.main.gradle

import mill.main.buildgen.BuildGenChecker
import utest.*

object GradleBuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()
    val args = Array("--merge", "--no-meta")
    val argsJvm11 = Array("--gradle-jvm-id", "11")
    val argsJvm17 = Array("--gradle-jvm-id", "17")

    test("6.0") - {
      val projectRoot = os.sub / "gradle-6-0"
      assert(
        checker.check(
          generate = GradleBuildGenMain.main(argsJvm11),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/gradle-6-0"
        )
      )
      test("with-args") - {
        assert(
          checker.check(
            generate = GradleBuildGenMain.main(args ++ argsJvm11),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/gradle-6-0"
          )
        )
      }
    }
    test("7.0") - {
      val projectRoot = os.sub / "gradle-7-0"
      assert(
        checker.check(
          generate = GradleBuildGenMain.main(argsJvm11),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/gradle-7-0"
        )
      )
      test("with-args") - {
        assert(
          checker.check(
            generate = GradleBuildGenMain.main(args ++ argsJvm11),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/gradle-7-0"
          )
        )
      }
    }
    test("8.0") - {
      val projectRoot = os.sub / "gradle-8-0"
      assert(
        checker.check(
          generate = GradleBuildGenMain.main(argsJvm11),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/gradle-8-0"
        )
      )
      test("with-args") - {
        assert(
          checker.check(
            generate = GradleBuildGenMain.main(args ++ argsJvm17),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/gradle-8-0"
          )
        )
      }
    }
    test("9.0.0") - {
      val projectRoot = os.sub / "gradle-9-0-0"
      assert(
        checker.check(
          generate = GradleBuildGenMain.main(argsJvm17),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/gradle-9-0-0"
        )
      )
      test("with-args") - {
        assert(
          checker.check(
            generate = GradleBuildGenMain.main(args ++ argsJvm17),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/gradle-9-0-0"
          )
        )
      }
    }
  }
}
