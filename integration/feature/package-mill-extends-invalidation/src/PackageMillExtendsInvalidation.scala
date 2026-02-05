package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Repro for https://github.com/com-lihaoyi/mill/issues/6790, but using a programmable
// `package.mill` build file (not `package.mill.yaml`). Since `package.mill` cannot
// define YAML headers, we use command discovery (which relies on `Discover`) as the
// observable behavior.
object PackageMillExtendsInvalidation extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("nestedExtendsChange") - integrationTest { tester =>
      import tester.*

      val firstCompile = eval(("__.compile"))
      assert(firstCompile.isSuccess)

      val missingCmd = eval(("foo.bar.hello"))
      assert(!missingCmd.isSuccess)
      assert(missingCmd.err.contains("Cannot resolve") || missingCmd.err.contains("Unable to resolve"))

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
