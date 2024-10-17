package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest._

// Run simple commands on a simple build and check their entire output,
// ensuring we don't get spurious warnings or logging messages slipping in
object FullRunLogsTests extends UtestIntegrationTestSuite {
  def globMatches(expected: String, line: String): Boolean = {
    StringContext
      .glob(expected.split("\\.\\.\\.", -1).toIndexedSeq, line)
      .nonEmpty
  }

  def tests: Tests = Tests {
    test("milltest") - integrationTest { tester =>
      import tester._

      val res = eval(("run", "--text", "hello"))
      res.isSuccess ==> true
      assert(res.out == "<h1>hello</h1>")
      assert(
        res.err ==
        s"""[build.mill] [info] compiling 1 Scala source to /Users/lihaoyi/Github/mill/out/integration/feature/full-run/local/test.dest/sandbox/run-1/out/mill-build/compile.dest/classes ...
           |[build.mill] [info] done compiling
           |[info] compiling 1 Java source to /Users/lihaoyi/Github/mill/out/integration/feature/full-run/local/test.dest/sandbox/run-1/out/compile.dest/classes ...
           |[info] done compiling""".stripMargin
      )
    }
  }
}
