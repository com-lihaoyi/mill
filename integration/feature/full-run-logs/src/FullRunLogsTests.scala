package mill.integration

import mill.constants.OutFiles.OutFiles
import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsTests extends UtestIntegrationTestSuite {

  def normalize(s: String) = s.replace('\\', '/')
    .replaceAll("\\(([a-zA-Z.]+):\\d+\\)", "($1:<digits>)")
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+/\\d+", ".../...")
    .replaceAll("\\d+\\.\\d+", ".../...")
    .replaceAll(" \\d+s", "")
    .linesIterator
    .toList

  def tests: Tests = Tests {
    test("noticker") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "false", "run", "--text", "hello"))

      res.isSuccess ==> true
      assert(res.out == "<h1>hello</h1>")
      val normalized = normalize(res.err)

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
      import tester._

      val res = eval(("--ticker", "true", "run", "--text", "hello"))
      res.isSuccess ==> true

      assertGoldenLiteral(
        normalize(res.out),
        List("<h1>hello</h1>")
      )

      assertGoldenLiteral(
        normalize(res.err),
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
      import tester._

      val res = eval(
        ("--ticker", "true", "exclusives.printingC"),
        mergeErrIntoOut = true
      )
      assert(res.isSuccess)

      // Make sure when running `exclusive` tasks, we always print the name of the task
      // before it starts, we turn off the ticker and otherwise there's no way to know what
      // task each section of logs belongs to
      assertGoldenLiteral(
        normalize(res.out),
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
    test("keepGoingFailure") - integrationTest { tester =>
      import tester._

      modifyFile(workspacePath / "src/foo/Foo.java", _ + "class Bar")
      val res = eval(("--ticker", "true", "--keep-going", "jar"))
      res.isSuccess ==> false

      assertGoldenLiteral(
        normalize(res.err),
        List(
          "============================== jar ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] compile compiling 1 Java source to out/compile.dest/classes ...",
          "<digits>] [error] src/foo/Foo.java:36:10",
          "<digits>] class Bar",
          "<digits>]          ^",
          "<digits>] reached end of file while parsing",
          "<digits>] compile task failed",
          ".../..., 1 failed] ============================== jar ==============================",
          "<digits>] [error] compile javac returned non-zero exit code"
        )
      )

    }
    test("keepGoingMetaFailure") - integrationTest { tester =>
      import tester._
      modifyFile(workspacePath / "build.mill", _ + "?")

      val res2 = eval(("--ticker", "true", "--keep-going", "jar"))
      res2.isSuccess ==> false

      assertGoldenLiteral(
        normalize(res2.err),
        List(
          "============================== jar ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] [error] build.mill:63:1",
          "build.mill-<digits>] ?",
          "build.mill-<digits>] ^",
          "build.mill-<digits>] Illegal start of toplevel definition",
          "build.mill-<digits>] [error] one error found",
          "build.mill-<digits>] compile task failed",
          ".../..., 1 failed] ============================== jar ==============================",
          "build.mill-<digits>] [error] compile Compilation failed"
        )
      )
    }
    test("show") - integrationTest { tester =>
      import tester._
      // Make sure when we have nested evaluations, e.g. due to usage of evaluator commands
      // like `show`, both outer and inner evaluations hae their metadata end up in the
      // same profile files so a user can see what's going on in either
      eval(("show", "compile"))
      val millProfile = ujson.read(os.read(workspacePath / OutFiles.out / "mill-profile.json")).arr
      val millChromeProfile =
        ujson.read(os.read(workspacePath / OutFiles.out / "mill-chrome-profile.json")).arr
      // Profile logs for the thing called by show
      assert(millProfile.exists(_.obj("label").str == "compile"))
      assert(millProfile.exists(_.obj("label").str == "compileClasspath"))
      assert(millProfile.exists(_.obj("label").str == "mvnDeps"))
      assert(millProfile.exists(_.obj("label").str == "javacOptions"))
      assert(millChromeProfile.exists(_.obj.get("name") == Some(ujson.Str("compile"))))
      assert(millChromeProfile.exists(_.obj.get("name") == Some(ujson.Str("compileClasspath"))))
      assert(millChromeProfile.exists(_.obj.get("name") == Some(ujson.Str("mvnDeps"))))
      assert(millChromeProfile.exists(_.obj.get("name") == Some(ujson.Str("javacOptions"))))
      // Profile logs for show itself
      assert(millProfile.exists(_.obj("label").str == "show"))
      assert(millChromeProfile.exists(_.obj.get("name") == Some(ujson.Str("show"))))
    }

    test("exception") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "true", "exception"), mergeErrIntoOut = true)
      res.isSuccess ==> false

      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "============================== exception ==============================",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          ".../..., 1 failed] ============================== exception ==============================",
          "<digits>] [error] exception",
          "java.lang.Exception: boom",
          "  build_.package_.exceptionHelper(build.mill:<digits>)",
          "  build_.package_.exception$$anonfun$1(build.mill:<digits>)",
          "  mill.api.Task$Named.evaluate(Task.scala:<digits>)",
          "  mill.api.Task$Named.evaluate$(Task.scala:<digits>)",
          "  mill.api.Task$Command.evaluate(Task.scala:<digits>)",
          "java.lang.RuntimeException: bang",
          "  build_.package_.exceptionHelper(build.mill:<digits>)",
          "  build_.package_.exception$$anonfun$1(build.mill:<digits>)",
          "  mill.api.Task$Named.evaluate(Task.scala:<digits>)",
          "  mill.api.Task$Named.evaluate$(Task.scala:<digits>)",
          "  mill.api.Task$Command.evaluate(Task.scala:<digits>)"
        )
      )
    }

    test("colors") - integrationTest { tester =>
      import tester._
      // Make sure that running tests and tasks that print long multi-line colored output
      // doesn't accidentally truncate the output, mess up the colors, or be affected by
      // the chunking/buffering/flushing common when working with streams. Assert both `out`
      // alone as well as `mergeErrIntoOut` to make sure things look correct

      def normalize(s: String) = {
        // Parse string to normalize ansi codes and remove redundant ones before asserting
        FullRunLogsTests.normalize(
          fansi.Str(s).toString
            .replace(fansi.Color.Reset.escape, "(X)")
            .replace(fansi.Color.Red.escape, "(R)")
            .replace(fansi.Color.Green.escape, "(G)")
            .replace(fansi.Color.Blue.escape, "(B)")
            .replace(fansi.Color.Cyan.escape, "(C)")
            .replace(fansi.Color.Magenta.escape, "(M)")
            .replace(fansi.Color.Yellow.escape, "(Y)")
        )
      }

      val res = eval(("-i", "--ticker", "true", "test.run"))

      assert(res.isSuccess)
      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "(R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_1",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_2",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_3",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_4",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_5",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_6",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_7",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_8",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_9",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_10",
          "(G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_11",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_12",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_13",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_14",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_15",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_16",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_17",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_18",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_19",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_20",
          "(B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_21",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_22",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_23",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_24",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_25",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_26",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_27",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_28",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_29",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_30",
          "(C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_31",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_32",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_33",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_34",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_35",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_36",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_37",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_38",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_39",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_40",
          "(M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_41",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_42",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_43",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_44",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_45",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_46",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_47",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_48",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_49",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_50",
          "(Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_51",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_52",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_53",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_54",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_55",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_56",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_57",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_58",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_59",
          "ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_60",
          "(X)"
        )
      )
      // Sometimes order can be mixed up between stdout and stderr, even with mergeErrIntoOut
      retry(3) {
        val res2 = eval(("-i", "--ticker=true", "--color=true", "test"), mergeErrIntoOut = true)
        assertGoldenLiteral(
          normalize(res2.result.out.text()),
          List(
            "============================== test ==============================",
            "(B)<digits>] test.testForked(X) Running Test Class foo.FooTest",
            "(B)<digits>](X) (B)Test run (X)foo.(Y)FooTest(B) started(X)",
            "(B)<digits>](X) Test foo.(Y)FooTest(X).(C)testSimple(X) started",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_1(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_2(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_3(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_4(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_5(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_6(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_7(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_8(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_9(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_10(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_11(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_12(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_13(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_14(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_15(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_16(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_17(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_18(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_19(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_20(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_21(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_22(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_23(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_24(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_25(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_26(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_27(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_28(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_29(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_30(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_31(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_32(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_33(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_34(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_35(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_36(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_37(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_38(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_39(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_40(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_41(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_42(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_43(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_44(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_45(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_46(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_47(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_48(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_49(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_50(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_51(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_52(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_53(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_54(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_55(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_56(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_57(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_58(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_59(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_60(X)",
            "(B)<digits>](X) ",
            "(B)<digits>](X) Test foo.(Y)FooTest(X).(C)testSimple(X) finished, took .../... sec",
            "(B)<digits>](X) (B)Test run (X)foo.(Y)FooTest(B) finished: 0 failed, 0 ignored, 1 total, .../...s(X)",
            "102/<digits>] ============================== test =============================="
          )
        )
      }

      val res3 = eval(("-i", "--ticker", "true", "test.printColors"))

      assertGoldenLiteral(
        normalize(res3.result.out.text()),
        List(
          "<digits>] test.printColors (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_1(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_2(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_3(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_4(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_5(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_6(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_7(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_8(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_9(X)",
          "<digits>] (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_10(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_11(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_12(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_13(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_14(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_15(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_16(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_17(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_18(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_19(X)",
          "<digits>] (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_20(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_21(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_22(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_23(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_24(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_25(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_26(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_27(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_28(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_29(X)",
          "<digits>] (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_30(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_31(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_32(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_33(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_34(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_35(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_36(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_37(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_38(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_39(X)",
          "<digits>] (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_40(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_41(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_42(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_43(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_44(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_45(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_46(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_47(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_48(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_49(X)",
          "<digits>] (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_50(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_51(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_52(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_53(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_54(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_55(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_56(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_57(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_58(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_59(X)",
          "<digits>] (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_60(X)",
          "<digits>] "
        )
      )

      // Sometimes order can be mixed up between stdout and stderr, even with mergeErrIntoOut
      retry(3) {
        val res4 =
          eval(("-i", "--ticker=true", "--color=true", "test.printColors"), mergeErrIntoOut = true)

        assertGoldenLiteral(
          normalize(res4.result.out.text()),
          List(
            "============================== test.printColors ==============================",
            "(B)<digits>] test.printColors(X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_1(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_2(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_3(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_4(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_5(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_6(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_7(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_8(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_9(X)",
            "(B)<digits>](X) (R)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_10(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_11(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_12(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_13(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_14(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_15(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_16(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_17(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_18(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_19(X)",
            "(B)<digits>](X) (G)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_20(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_21(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_22(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_23(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_24(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_25(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_26(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_27(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_28(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_29(X)",
            "(B)<digits>](X) (B)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_30(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_31(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_32(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_33(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_34(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_35(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_36(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_37(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_38(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_39(X)",
            "(B)<digits>](X) (C)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_40(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_41(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_42(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_43(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_44(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_45(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_46(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_47(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_48(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_49(X)",
            "(B)<digits>](X) (M)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_50(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_51(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_52(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_53(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_54(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_55(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_56(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_57(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_58(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_59(X)",
            "(B)<digits>](X) (Y)ABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJABCDEFGHIJ_60(X)",
            "(B)<digits>](X) ",
            "1/<digits>] ============================== test.printColors =============================="
          )
        )
      }
    }
  }
}
