package mill.main.maven

import mill.main.buildgen.BuildGenChecker
import utest.*

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()

    // multi level nested modules
    test("maven-samples") {
      val sourceRoot = os.sub / "maven-samples"
      val expectedRoot = os.sub / "expected/maven-samples"
      assert(
        checker.check(MavenBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }

    test("config") {
      val sourceRoot = os.sub / "maven-samples"
      val expectedRoot = os.sub / "expected/config"
      val args =
        Array("--test-module", "tests", "--merge", "--publish-properties", "--no-meta-build")
      assert(
        checker.check(MavenBuildGenMain.main(args), sourceRoot, expectedRoot)
      )
    }

    // custom resources
    // custom repositories
    test("misc") {
      val sourceRoot = os.sub / "misc"
      val expectedRoot = os.sub / "expected/misc"
      assert(
        checker.check(MavenBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }
  }
}
