package mill.main.maven

import mill.main.buildgen.BuildGenChecker
import utest.*

trait MavenBuildGenTests extends TestSuite {

  def expectedDir: os.SubPath
  def extraArgs: Seq[String] = Seq()

  def tests = Tests {
    val checker = BuildGenChecker()
    test("maven-samples") {
      assert(checker.check(
        sourceRel = os.sub / "maven-samples",
        expectedRel = os.sub / expectedDir / "maven-samples",
        initArgs = Seq() ++ extraArgs
      ))
    }
    test("quickstart") {
      assert(checker.check(
        sourceRel = os.sub / "quickstart",
        expectedRel = os.sub / expectedDir / "quickstart",
        initArgs = Seq() ++ extraArgs
      ))

    }
    test("spring-start") {
      assert(checker.check(
        sourceRel = os.sub / "spring-start",
        expectedRel = os.sub / expectedDir / "spring-start",
        initArgs = Seq() ++ extraArgs
      ))
    }
    test("with-args") {
      val args = Seq("--publish-properties", "--merge", "--no-meta")
      test("maven-samples") {
        assert(checker.check(
          sourceRel = os.sub / "maven-samples",
          expectedRel = os.sub / expectedDir / "with-args/maven-samples",
          initArgs = args ++ extraArgs
        ))
      }
      test("quickstart") {
        assert(checker.check(
          sourceRel = os.sub / "quickstart",
          expectedRel = os.sub / expectedDir / "with-args/quickstart",
          initArgs = args ++ extraArgs
        ))
      }
    }
  }
}

object MavenBuildGenTests extends MavenBuildGenTests {
  override def expectedDir: os.SubPath = "expected"
}

object MavenBuildGenYamlTests extends MavenBuildGenTests {
  override def expectedDir: os.SubPath = "expected"
  override def extraArgs = Seq("--declarative", "true")
}

object MavenBuildGenScalaTests extends MavenBuildGenTests {
  override def expectedDir: os.SubPath = "expected-scala"
  override def extraArgs = Seq("--declarative", "false")
}
