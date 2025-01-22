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

    test("misc") {
      test("custom-resources") {
        val sourceRoot = os.sub / "misc/custom-resources"
        val expectedRoot = os.sub / "expected/misc/custom-resources"
        assert(
          checker.check(MavenBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
        )
      }
    }
  }
}
