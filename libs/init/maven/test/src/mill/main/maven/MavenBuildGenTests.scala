package mill.main.maven

import mill.main.buildgen.BuildGenChecker
import utest.*

object MavenBuildGenTests extends TestSuite {
  def tests = Tests {
    val checker = BuildGenChecker()
    test("maven-samples") {
      assert(checker.check(
        generate = MavenBuildGenMain.main(Array.empty),
        sourceRel = os.sub / "maven-samples",
        expectedRel = os.sub / "expected/maven-samples"
      ))
    }
    test("quickstart") {
      assert(checker.check(
        generate = MavenBuildGenMain.main(Array.empty),
        sourceRel = os.sub / "quickstart",
        expectedRel = os.sub / "expected/quickstart"
      ))
    }
    test("spring-start") {
      assert(checker.check(
        generate = MavenBuildGenMain.main(Array.empty),
        sourceRel = os.sub / "spring-start",
        expectedRel = os.sub / "expected/spring-start"
      ))
    }
    test("with-args") {
      test("quickstart") {
        assert(checker.check(
          generate = MavenBuildGenMain.main(Array("--merge", "--no-meta", "--publish-properties")),
          sourceRel = os.sub / "quickstart",
          expectedRel = os.sub / "expected/with-args/quickstart"
        ))
      }
    }
  }
}
