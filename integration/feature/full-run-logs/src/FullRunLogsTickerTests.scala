package mill.integration
import mill.constants.OutFiles.OutFiles
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
        mergeErrIntoOut = true
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
        mergeErrIntoOut = true
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
    test("interleaved-compile-errors") - integrationTest { tester =>
      import tester.*

      val res = eval(
        ("--ticker", "true", "{broken1,broken2,broken3}.compile"),
        mergeErrIntoOut = true
      )
      assert(!res.isSuccess)

      // Make sure when running `exclusive` tasks, we always print the name of the task
      // before it starts, we turn off the ticker and otherwise there's no way to know what
      // task each section of logs belongs to
      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "============================== {broken1,broken2,broken3}.compile ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] broken3.compile compiling 1 Java source to out/broken3/compile.dest/classes ...",
          "<digits>] broken2.compile compiling 1 Java source to out/broken2/compile.dest/classes ...",
          "<digits>] broken1.compile compiling 1 Java source to out/broken1/compile.dest/classes ...",
          "<digits>] [error] broken/src/Foo.java:1:0",
          "<digits>] ?",
          "<digits>] ",
          "<digits>] class, interface, enum, or record expected",
          "<digits>] [error] broken/src/Foo.java:1:0",
          "<digits>] ?",
          "<digits>] ",
          "<digits>] class, interface, enum, or record expected",
          "<digits>] [error] broken/src/Foo.java:1:0",
          "<digits>] ?",
          "<digits>] ",
          "<digits>] class, interface, enum, or record expected",
          ".../..., 2 failed] ===================== {broken1,broken2,broken3}.compile ====================",
          "<digits>] [error] broken3.compile javac returned non-zero exit code",
          "<digits>] [error] broken1.compile javac returned non-zero exit code",
          "<digits>] [error] broken2.compile javac returned non-zero exit code"
        )
      )
    }
  }
}
