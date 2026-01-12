package mill.main.maven

import mill.main.buildgen.BuildGenChecker
import utest.*

object MavenBuildGenTests extends TestSuite {

  def tests = Tests {
    val checker = BuildGenChecker()
    test("maven-samples") {
      test("YAML") {
        assert(checker.check(
          sourceRel = os.sub / "maven-samples",
          expectedRel = os.sub / "expected/maven-samples"
        ))
      }
      test("Scala") {
        assert(checker.check(
          sourceRel = os.sub / "maven-samples",
          expectedRel = os.sub / "expected-scala/maven-samples",
          initArgs = Seq("--declarative", "false")
        ))
      }
    }
    test("quickstart") {
      test("YAML") {
        assert(checker.check(
          sourceRel = os.sub / "quickstart",
          expectedRel = os.sub / "expected/quickstart"
        ))
      }
      test("Scala") {
        assert(checker.check(
          sourceRel = os.sub / "quickstart",
          expectedRel = os.sub / "expected-scala/quickstart",
          initArgs = Seq("--declarative", "false")
        ))
      }
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
          initArgs = args
        ))
      }
      test("quickstart") {
        assert(checker.check(
          sourceRel = os.sub / "quickstart",
          expectedRel = os.sub / "expected/with-args/quickstart",
          initArgs = args
        ))
      }
    }
  }
}
