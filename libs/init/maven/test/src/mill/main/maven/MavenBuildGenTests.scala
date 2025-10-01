package mill.main.maven

import mill.main.buildgen.BuildGenChecker
import utest.*

object MavenBuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()
    val noArgs = Array.empty[String]
    val args = Array("--merge", "--no-meta")

    test("maven-samples") {
      val projectRoot = os.sub / "maven-samples"
      assert(
        checker.check(
          generate = MavenBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/maven-samples"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = MavenBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/maven-samples"
          )
        )
      }
    }
    test("quickstart") {
      val projectRoot = os.sub / "quickstart"
      assert(
        checker.check(
          generate = MavenBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/quickstart"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = MavenBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/quickstart"
          )
        )
      }
    }
    test("spring-start") {
      // throws ModelBuildingException that is ignored
      val projectRoot = os.sub / "spring-start"
      assert(
        checker.check(
          generate = MavenBuildGenMain.main(noArgs),
          sourceRel = projectRoot,
          expectedRel = os.sub / "expected/spring-start"
        )
      )
      test("with-args") {
        assert(
          checker.check(
            generate = MavenBuildGenMain.main(args),
            sourceRel = projectRoot,
            expectedRel = os.sub / "expected-with-args/spring-start"
          )
        )
      }
    }
  }
}
