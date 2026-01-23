package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object RootModuleCompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
      val res = eval("foo.scalaVersion")

      assert(!res.isSuccess)

      // For now these error messages still show generated/mangled code; not ideal, but it'll do
      res.assertContainsLines(
        "[error] build.mill:7:67",
        "abstract class package_  extends _root_.mill.util.MainRootModule, UnknownRootModule {",
        "                                                                  ^^^^^^^^^^^^^^^^^",
        "Not found: type UnknownRootModule"
      )

      // For now these error messages still show generated/mangled code; not ideal, but it'll do
      res.assertContainsLines(
        "[error] foo/package.mill:6:96",
        "abstract class package_  extends _root_.mill.api.internal.SubfolderModule(_root_.build_.package_.millDiscover), UnknownFooModule {",
        "                                                                                                                ^^^^^^^^^^^^^^^^",
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
        "[error] build.mill:12:22",
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
        "[error] foo/package.mill:11:22",
        "object after extends UnknownAfterFooModule",
        "                     ^^^^^^^^^^^^^^^^^^^^^",
        "Not found: type UnknownAfterFooModule"
      )
    }
  }
}
