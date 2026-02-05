package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Repro for https://github.com/com-lihaoyi/mill/issues/6790
object YamlExtendsInvalidation extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("nestedExtendsChange") - integrationTest { tester =>
      import tester.*

      val firstCompile = eval(("__.compile"))
      assert(firstCompile.isSuccess)

      modifyFile(
        workspacePath / "foo/bar/package.mill.yaml",
        _ =>
          """extends: ScalaModule
            |scalaVersion: 3.3.3
            |""".stripMargin
      )

      val secondCompile = eval(("__.compile"))
      assert(secondCompile.isSuccess)

      val showScalaVersion = eval(("show", "foo.bar.scalaVersion"))
      assert(showScalaVersion.isSuccess)
      assert(showScalaVersion.out.contains("3.3.3"))
    }
  }
}
