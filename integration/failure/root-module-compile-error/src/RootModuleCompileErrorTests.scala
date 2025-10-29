package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object RootModuleCompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval("foo.scalaVersion")

      assert(!res.isSuccess)

      val normalizedError = res.err
        .replace(workspacePath.toString, "<workspace-path>")
        .linesIterator
        .filter(_.startsWith("[error] "))
        .toVector

      // For now some error messages still show generated/mangled code; not ideal, but it'll do
      assertGoldenLiteral(
        normalizedError,
        Vector(
          "[error] -- [E006] Not Found Error: <workspace-path>/out/mill-build/generatedScriptSources.dest/wrapped/build_/build.mill:17:65 ",
          "[error] 17 |class _MillRootModulePa extends _root_.mill.util.MainRootModule, UnknownRootModule",
          "[error]    |                                                                 ^^^^^^^^^^^^^^^^^",
          "[error]    |                                       Not found: type UnknownRootModule",
          "[error]    |",
          "[error]    | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/out/mill-build/generatedScriptSources.dest/wrapped/build_/foo/package.mill:17:93 ",
          "[error] 17 |class _MillRootModuleP extends _root_.mill.api.internal.SubfolderModule(build.millDiscover), UnknownFooModule",
          "[error]    |                                                                                             ^^^^^^^^^^^^^^^^",
          "[error]    |                                        Not found: type UnknownFooModule",
          "[error]    |",
          "[error]    | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/build.mill:5:22 ",
          "[error] 5 |object before extends UnknownBeforeModule",
          "[error]   |                      ^^^^^^^^^^^^^^^^^^^",
          "[error]   |                      Not found: type UnknownBeforeModule",
          "[error]   |",
          "[error]   | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/build.mill:8:21 ",
          "[error] 8 |  def scalaVersion = unknownRootInternalDef",
          "[error]   |                     ^^^^^^^^^^^^^^^^^^^^^^",
          "[error]   |                     Not found: unknownRootInternalDef",
          "[error]   |",
          "[error]   | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/build.mill:11:21 ",
          "[error] 11 |object after extends UnknownAfterModule",
          "[error]    |                     ^^^^^^^^^^^^^^^^^^",
          "[error]    |                     Not found: type UnknownAfterModule",
          "[error]    |",
          "[error]    | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/foo/package.mill:4:22 ",
          "[error] 4 |object before extends UnknownBeforeFooModule",
          "[error]   |                      ^^^^^^^^^^^^^^^^^^^^^^",
          "[error]   |                      Not found: type UnknownBeforeFooModule",
          "[error]   |",
          "[error]   | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/foo/package.mill:7:21 ",
          "[error] 7 |  def scalaVersion = unknownFooInternalDef",
          "[error]   |                     ^^^^^^^^^^^^^^^^^^^^^",
          "[error]   |                     Not found: unknownFooInternalDef",
          "[error]   |",
          "[error]   | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/foo/package.mill:10:21 ",
          "[error] 10 |object after extends UnknownAfterFooModule",
          "[error]    |                     ^^^^^^^^^^^^^^^^^^^^^",
          "[error]    |                     Not found: type UnknownAfterFooModule",
          "[error]    |",
          "[error]    | longer explanation available when compiling with `-explain`",
          "[error] 8 errors found"
        )
      )
    }
  }
}
