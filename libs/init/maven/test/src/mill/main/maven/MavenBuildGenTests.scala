package mill.main.maven

import mill.main.buildgen.BuildGenChecker
import utest.*

object MavenBuildGenTests extends TestSuite {
  def tests = Tests {
    val checker = BuildGenChecker()
    test("maven-samples") {
      assert(checker.check(
        sourceRel = os.sub / "maven-samples",
        expectedRel = os.sub / "expected/maven-samples"
      ))
    }
    test("quickstart") {
      assert(checker.check(
        sourceRel = os.sub / "quickstart",
        expectedRel = os.sub / "expected/quickstart"
      ))
    }
    test("spring-start") {
      assert(checker.check(
        sourceRel = os.sub / "spring-start",
        expectedRel = os.sub / "expected/spring-start"
      ))
    }
    test("with-args") {
      val args = Seq("--publish-properties", "--merge", "--no-meta")
      test("maven-samples") {
        assert(checker.check(
          sourceRel = os.sub / "maven-samples",
          expectedRel = os.sub / "expected/with-args/maven-samples",
          mainArgs = args
        ))
      }
      test("quickstart") {
        assert(checker.check(
          sourceRel = os.sub / "quickstart",
          expectedRel = os.sub / "expected/with-args/quickstart",
          mainArgs = args
        ))
      }
    }
  }
}
