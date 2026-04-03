package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object RootModuleCompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("foo.scalaVersion")

      assert(!res.isSuccess)

      // Errors for the extends clause are remapped to lines beyond the original
      // file (the generated stub class), so line content is empty
      res.assertContainsLines(
        "[error] build.mill:12:130",
        "",
        "^",
        "Not found: type UnknownRootModule"
      )

      res.assertContainsLines(
        "[error] foo/package.mill:11:175",
        "",
        "^",
        "Not found: type UnknownFooModule"
      )

      res.assertContainsLines(
        "[error] build.mill:8:22",
        "  def scalaVersion = unknownRootInternalDef",
        "                     ^^^^^^^^^^^^^^^^^^^^^^",
        "Not found: unknownRootInternalDef"
      )

      res.assertContainsLines(
        "[error] build.mill:5:23",
        "object before extends UnknownBeforeModule",
        "                      ^^^^^^^^^^^^^^^^^^^",
        "Not found: type UnknownBeforeModule"
      )

      res.assertContainsLines(
        "[error] build.mill:11:22",
        "object after extends UnknownAfterModule",
        "                     ^^^^^^^^^^^^^^^^^^",
        "Not found: type UnknownAfterModule"
      )

      res.assertContainsLines(
        "[error] foo/package.mill:7:22",
        "  def scalaVersion = unknownFooInternalDef",
        "                     ^^^^^^^^^^^^^^^^^^^^^",
        "Not found: unknownFooInternalDef"
      )

      res.assertContainsLines(
        "[error] foo/package.mill:4:23",
        "object before extends UnknownBeforeFooModule",
        "                      ^^^^^^^^^^^^^^^^^^^^^^",
        "Not found: type UnknownBeforeFooModule"
      )

      res.assertContainsLines(
        "[error] foo/package.mill:10:22",
        "object after extends UnknownAfterFooModule",
        "                     ^^^^^^^^^^^^^^^^^^^^^",
        "Not found: type UnknownAfterFooModule"
      )
    }
  }
}
