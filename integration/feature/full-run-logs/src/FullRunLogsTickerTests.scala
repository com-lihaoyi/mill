package mill.integration
import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsTickerTests extends UtestIntegrationTestSuite {

  import FullRunLogsUtils.normalize

  def tests: Tests = Tests {
    test("noticker") - integrationTest { tester =>
      import tester.*

      val res = eval(("--ticker", "false", "run", "--text", "hello"), propagateEnv = false)

      res.isSuccess ==> true
      assert(res.out == "<h1>hello</h1>")
      val normalized = normalize(res.result.err.text())

      assertGoldenLiteral(
        normalized,
        List(
          "compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "done compiling",
          "compiling 1 Java source to out/compile.dest/classes ...",
          "done compiling"
        )
      )
    }

    test("ticker") - integrationTest { tester =>
      import tester.*

      val res = eval(("--ticker", "true", "run", "--text", "hello"), propagateEnv = false)
      res.isSuccess ==> true

      assertGoldenLiteral(
        normalize(res.out),
        List("<h1>hello</h1>")
      )

      assertGoldenLiteral(
        normalize(res.result.err.text()),
        // Should have no colors because we called it programmatically
        List(
          "============================== run --text hello ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] compile compiling 1 Java source to out/compile.dest/classes ...",
          "<digits>] done compiling",
          "<digits>] run",
          "63/<digits>] ============================== run --text hello =============================="
        )
      )
    }
    test("exclusive") - integrationTest { tester =>
      import tester.*

      val res = eval(
        ("--ticker", "true", "exclusives.printingC"),
        mergeErrIntoOut = true, propagateEnv = false
      )
      assert(res.isSuccess)

      // Make sure when running `exclusive` tasks, we always print the name of the task
      // before it starts, we turn off the ticker and otherwise there's no way to know what
      // task each section of logs belongs to
      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "============================== exclusives.printingC ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] exclusives.printingA",
          "Hello A",
          "<digits>] exclusives.empty",
          "<digits>] exclusives.printingB",
          "Hello B",
          "World B",
          "<digits>] exclusives.printingC",
          "Hello C",
          "World C",
          "Exclusive C",
          "4/<digits>] ============================== exclusives.printingC =============================="
        )
      )
    }
    test("logging") - integrationTest { tester =>
      import tester.*

      val res = eval(
        ("--ticker", "true", "logging"),
        mergeErrIntoOut = true, propagateEnv = false
      )
      assert(res.isSuccess)

      // Make sure when running `exclusive` tasks, we always print the name of the task
      // before it starts, we turn off the ticker and otherwise there's no way to know what
      // task each section of logs belongs to
      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "============================== logging ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] logging MY PRINTLN",
          "<digits>] MY INFO LOGS",
          "<digits>] [warn] MY WARN LOGS",
          "<digits>] [error] MY ERROR LOGS",
          "1/<digits>] ============================== logging =============================="
        )
      )
    }
  }
}
