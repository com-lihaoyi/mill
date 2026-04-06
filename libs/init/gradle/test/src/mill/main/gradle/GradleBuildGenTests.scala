package mill.main.gradle

import mill.main.buildgen.BuildGenChecker
import utest.*

trait GradleBuildGenTests extends TestSuite {

  def expectedDir: os.SubPath
  def extraArgs: Seq[String] = Seq()

  def tests = Tests {
    val checker = BuildGenChecker()
    test("6.0") {
      assert(checker.check(
        sourceRel = os.sub / "gradle-6-0",
        expectedRel = os.sub / expectedDir / "gradle-6-0",
        initArgs = Seq("--gradle-jvm-id", "11") ++ extraArgs
      ))
    }
    test("7.0") {
      assert(checker.check(
        sourceRel = os.sub / "gradle-7-0",
        expectedRel = os.sub / expectedDir / "gradle-7-0",
        initArgs = Seq("--gradle-jvm-id", "11") ++ extraArgs
      ))
    }
    test("8.0") {
      assert(checker.check(
        sourceRel = "gradle-8-0",
        expectedRel = os.sub / expectedDir / "gradle-8-0",
        initArgs = Seq("--gradle-jvm-id", "11") ++ extraArgs
      ))
    }
    test("9.0.0") {
      assert(checker.check(
        sourceRel = "gradle-9-0-0",
        expectedRel = os.sub / expectedDir / "gradle-9-0-0",
        initArgs = Seq("--gradle-jvm-id", "21") ++ extraArgs
      ))
    }
    test("with-args") {
      test("8.0") {
        assert(checker.check(
          sourceRel = "gradle-8-0",
          expectedRel = os.sub / expectedDir / "with-args/gradle-8-0",
          initArgs = Seq("--merge", "--no-meta", "--gradle-jvm-id", "17") ++ extraArgs
        ))
      }
    }
  }
}

object GradleBuildGenTests extends GradleBuildGenTests {
  def expectedDir: os.SubPath = "expected"
}

object GradleBuildGenYamlTests extends GradleBuildGenTests {
  def expectedDir: os.SubPath = "expected"
  override def extraArgs = Seq("--declarative", "true")
}

object GradleBuildGenScalaTests extends GradleBuildGenTests {
  def expectedDir: os.SubPath = "expected-scala"
  override def extraArgs = Seq("--declarative", "false")
}
