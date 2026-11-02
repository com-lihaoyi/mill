package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

// The traits of mill-build/src override javacOptions without the `override` keyword, that the
// compiler plugin of mill-moduledefs adds for us. When a change in build.mill only re-compiles
// it, those traits are loaded from TASTy, where the plugin used not to save that flag:
// `foo`, that mixes them both, was then rejected with "inherits conflicting members".
object MetaBuildIncrementalOverridesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("buildFileChange") - integrationTest { tester =>
      import tester.*

      val first = eval(("show", "foo.javacOptions"))
      assert(first.isSuccess)
      assert(first.out.contains("-Xlint:deprecation"))
      assert(first.out.contains("-Xlint:unchecked"))

      modifyFile(
        workspacePath / "build.mill",
        _.replace(
          "object foo extends JavaModule with LintDeprecation with LintUnchecked",
          """object foo extends JavaModule with LintDeprecation with LintUnchecked {
            |  def hello = Task { "hello" }
            |}""".stripMargin
        )
      )

      val second = eval(("show", "foo.hello"))
      assert(!second.err.contains("inherits conflicting members"))
      assert(second.isSuccess)
      assert(second.out.contains("hello"))
    }
  }
}
