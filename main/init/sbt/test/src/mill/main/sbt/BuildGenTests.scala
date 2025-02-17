package mill.main.sbt

import mill.main.buildgen.BuildGenChecker
import utest.*

object BuildGenTests extends TestSuite {

  def tests: Tests = Tests {
    val checker = BuildGenChecker()

    test("misc") {
      val sourceRoot = os.sub / "misc"
      val expectedRoot = os.sub / "expected/misc"
      assert(
        checker.check(SbtBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }
    
    // multi-modules
    test("multi-module") {
      val sourceRoot = os.sub / "multi-module"
      val expectedRoot = os.sub / "expected/multi-module"
      assert(
        checker.check(SbtBuildGenMain.main(Array.empty), sourceRoot, expectedRoot)
      )
    }
/*
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
        checker.check(SbtBuildGenMain.main(args), sourceRoot, expectedRoot)
      )
    }
*/
  }
}
