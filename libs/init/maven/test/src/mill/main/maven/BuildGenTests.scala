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
    test("maven-samples-jvm-id") {
      val sourceRoot = os.sub / "maven-samples"
      val expectedRoot = os.sub / "expected/maven-samples-jvm-id"
      assert(
        checker.check(MavenBuildGenMain.main(Array("--jvm-id", "11")), sourceRoot, expectedRoot)
      )
    }

    test("config") {
      val sourceRoot = os.sub / "maven-samples"
      val expectedRoot = os.sub / "expected/config"
      val args = Array(
        "--base-module",
        "MyModule",
        "--jvm-id",
        "11",
        "--test-module",
        "tests",
        "--deps-object",
        "Deps",
        "--publish-properties",
        "--merge",
        "--cache-repository",
        "--process-plugins"
      )
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
