package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object CompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo.scalaVersion")

      assert(!res.isSuccess)
      val normalizedError = res.err
        .replace(tester.workspacePath.toString, "<workspace-path>")
        .linesIterator
        .filter(s => s.startsWith("[error] "))
        .toVector
        .map(_.replace('\\', '/'))

      // For now some error messages still show generated/mangled code; not ideal, but it'll do
      assertGoldenLiteral(
        normalizedError,
        Vector(
          "[error] -- [E007] Type Mismatch Error: <workspace-path>/qux.mill:4:33 ",
          "[error] 4 |def myOtherMsg = myMsg.substring(\"0\")",
          "[error]   |                                 ^^^",
          "[error]   |                                 Found:    (\"0\" : String)",
          "[error]   |                                 Required: Int",
          "[error]   |",
          "[error]   | longer explanation available when compiling with `-explain`",
          "[error] -- [E006] Not Found Error: <workspace-path>/bar.mill:15:8 ",
          "[error] 15 |println(doesntExist)",
          "[error]    |        ^^^^^^^^^^^",
          "[error]    |        Not found: doesntExist",
          "[error]    |",
          "[error]    | longer explanation available when compiling with `-explain`",
          "[error] -- [E008] Not Found Error: <workspace-path>/build.mill:11:4 ",
          "[error] 11 |foo.noSuchMethod",
          "[error]    |^^^^^^^^^^^^^^^^",
          "[error]    |value noSuchMethod is not a member of object package_.this.foo",
          "[error] three errors found"
        )
      )
    }
  }
}
