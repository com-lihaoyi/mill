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
      assert(res.out == "[46] <h1>hello</h1>")

      val expectedErrorRegex =
        s"""==================================================== run --text hello ================================================
           |======================================================================================================================
           |[build.mill-56/60] compile
           |[build.mill-56] [info] compiling 1 Scala source to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
           |[build.mill-56] [info] done compiling
           |[40/46] compile
           |[40] [info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
           |[40] [info] done compiling
           |[46/46] run
           |[46/46] ============================================ run --text hello ============================================<seconds-digits>s
           |======================================================================================================================"""
          .stripMargin
          .replaceAll("(\r\n)|\r", "\n")
          .replace('\\', '/')
          .split("<seconds-digits>")
          .map(java.util.regex.Pattern.quote)
          .mkString("=? [\\d]+")

      assert(expectedErrorRegex.r.matches(res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")))
    }
  }
}
