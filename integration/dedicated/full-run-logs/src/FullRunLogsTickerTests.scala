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
        List()
      )
    }

    test("ticker") - integrationTest { tester =>
      import tester.*

      val res =
        eval(("--ticker", "true", "--color=false", "run", "--text", "hello"), propagateEnv = false)
      pprint.log(res)
      assert(res.isSuccess == true)

      assertGoldenLiteral(
        normalize(res.out),
        List("<h1>hello</h1>")
      )

      assertGoldenLiteral(
        normalize(res.result.err.text()),
        // Should have no colors because we called it programmatically
        List(
          "mill run --text hello",
          "<digits>] run",
          ".../..., SUCCESS] mill run --text hello"
        )
      )
    }
    test("exclusive") - integrationTest { tester =>
      import tester.*

      val res = eval(
        ("--ticker", "true", "--color=false", "exclusives.printingC"),
        mergeErrIntoOut = true,
        propagateEnv = false
      )
      assert(res.isSuccess)

      // Make sure when running `exclusive` tasks, we always print the name of the task
      // before it starts, we turn off the ticker and otherwise there's no way to know what
      // task each section of logs belongs to
      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "mill exclusives.printingC",
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
          ".../..., SUCCESS] mill exclusives.printingC"
        )
      )
    }
    test("logging") - integrationTest { tester =>
      import tester.*

      val res = eval(
        ("--ticker", "true", "--color=true", "logging"),
        mergeErrIntoOut = true,
        propagateEnv = false
      )
      assert(res.isSuccess)

      // Make sure various kinds of logs are properly rendered
      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "(Y)mill logging(X)",
          "(B)<digits>] logging(X) MY PRINTLN",
          "(B)<digits>](X) MY INFO LOGS",
          "(B)<digits>](X) [(Y)warn(X)] MY WARN LOGS",
          "(B)<digits>](X) [(R)error(X)] MY ERROR LOGS",
          ".../..., (G)SUCCESS(X)] mill logging"
        )
      )
      // Make sure the `.log` files on disk contain what we expect
      assertGoldenLiteral(
        normalize(os.read(workspacePath / "out/mill-build/compile.log")),
        List("Compiling 1 Scala source")
      )
      assertGoldenLiteral(
        normalize(os.read(workspacePath / "out/logging.log")),
        List("MY PRINTLN", "MY INFO LOGS", "MY WARN LOGS", "MY ERROR LOGS")
      )
    }
  }
}
