package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Repro for https://github.com/com-lihaoyi/mill/issues/6790
object YamlExtendsInvalidation extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("rootExtendsChange") - integrationTest { tester =>
      import tester.*

      val firstCompile = eval(("compile"))
      assert(firstCompile.isSuccess)

      modifyFile(
        workspacePath / "build.mill.yaml",
        _ =>
          """extends:
            |- ScalaModule
            |- SbtModule
            |scalaVersion: 3.3.3
            |""".stripMargin
      )

      val showScalaVersion = eval(("show", "scalaVersion"))
      assert(showScalaVersion.isSuccess)
      assert(showScalaVersion.out.contains("3.3.3"))
    }
  }
}
