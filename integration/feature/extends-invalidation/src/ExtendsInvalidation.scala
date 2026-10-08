package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Repro for https://github.com/com-lihaoyi/mill/issues/6790
//
// Contains two cases:
// - nested `package.mill.yaml` extends change (YAML header keys become valid)
// - nested programmable `package.mill` change (command discovery via `Discover` updates)
object ExtendsInvalidation extends UtestIntegrationTestSuite {
  private def loadCase(workspacePath: os.Path, name: String): Unit = {
    val casesDir = workspacePath / "cases"
    val caseRoot = casesDir / name

    os.list(workspacePath).foreach { p =>
      if (p != casesDir) os.remove.all(p)
    }

    os.list(caseRoot).foreach { p =>
      os.copy.over(p, workspacePath / p.last, createFolders = true)
    }
  }

  val tests: Tests = Tests {
    test("yamlNestedExtendsChange") - integrationTest { tester =>
      import tester.*
      loadCase(workspacePath, "yaml")

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

    test("packageMillNestedChange") - integrationTest { tester =>
      import tester.*
      loadCase(workspacePath, "pkgmill")

      val firstCompile = eval(("__.compile"))
      assert(firstCompile.isSuccess)

      val missingCmd = eval(("foo.bar.hello"))
      assert(!missingCmd.isSuccess)
      assert(
        missingCmd.err.contains("Cannot resolve") || missingCmd.err.contains("Unable to resolve")
      )

      modifyFile(
        workspacePath / "foo/bar/package.mill",
        _ =>
          """package build.foo.bar
            |import mill.*, javalib.*
            |
            |object `package` extends JavaModule {
            |  def hello() = Task.Command { println("Hello") }
            |}
            |""".stripMargin
      )

      val secondCompile = eval(("__.compile"))
      assert(secondCompile.isSuccess)

      val foundCmd = eval(("foo.bar.hello"))
      assert(foundCmd.isSuccess)
      assert(foundCmd.out.contains("Hello"))
    }
  }
}
