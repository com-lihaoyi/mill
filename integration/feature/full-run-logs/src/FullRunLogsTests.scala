package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

// Run simple commands on a simple build and check their entire output,
// ensuring we don't get spurious warnings or logging messages slipping in
object FullRunLogsTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("noticker") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "false", "run", "--text", "hello"))
      res.isSuccess ==> true
      assert(res.out == "<h1>hello</h1>")
      assert(
        res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n") ==
          s"""[build.mill] [info] compiling 1 Scala source to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
             |[build.mill] [info] done compiling
             |[info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
             |[info] done compiling""".stripMargin.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")
      )
    }
    test("ticker") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "true", "run", "--text", "hello"))
      res.isSuccess ==> true
      assert("\\[\\d+\\] <h1>hello</h1>".r.matches(res.out))

      val expectedErrorRegex =
        s"""==================================================== run --text hello ================================================
           |======================================================================================================================
           |[build.mill-<digits>/<digits>] compile
           |[build.mill-<digits>] [info] compiling 1 Scala source to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
           |[build.mill-<digits>] [info] done compiling
           |[<digits>/<digits>] compile
           |[<digits>] [info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
           |[<digits>] [info] done compiling
           |[<digits>/<digits>] run
           |[<digits>/<digits>] ============================================ run --text hello ============================================<digits>s
           |======================================================================================================================"""
          .stripMargin
          .replaceAll("(\r\n)|\r", "\n")
          .replace('\\', '/')
          .split("<digits>")
          .map(java.util.regex.Pattern.quote)
          .mkString("=? ?[\\d]+")

      assert(expectedErrorRegex.r.matches(res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")))
    }
  }
}
