package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object VersionChangeTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester.*
      val javaVersion1 = eval(("javaVersion"))
      assert(!javaVersion1.out.contains("19.0.2"))

      os.write.over(workspacePath / ".mill-jvm-version", "temurin:19.0.2")


      val javaVersion2 = eval(("javaVersion"))
      assertGoldenLiteral(
        os.read.lines(tester.workspacePath / "out/mill-invalidation-tree.json"),
        Seq(
          "{",
          "  \"mill-jvm-version-changed:21.0.9->19.0.2\": {",
          "    \"javaVersion\": {}",
          "  }",
          "}"
        )
      )
      assert(javaVersion2.out.contains("19.0.2"))

    }
  }
}
