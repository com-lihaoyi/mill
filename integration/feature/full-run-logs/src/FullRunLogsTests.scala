package mill.integration

import mill.constants.OutFiles
import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsTests extends UtestIntegrationTestSuite {

  def normalize(s: String) = s.replace('\\', '/')
    .replaceAll("\\d+]", "<digits>]")
    .replaceAll("\\d+\\.\\d+", " ...")
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
          "<digits>] run 63/<digits>] ============================== run --text hello =============================="
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
          "<digits>] reached end of file while parsing",
          "<digits>] compile task failed",
          "54/54, 1 failed] ============================== jar ==============================",
          "1 tasks failed",
          "<digits>] compile javac returned non-zero exit code"
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
          "build.mill-<digits>] [error] build.mill:79:1",
          "build.mill-<digits>] [E<digits>] Illegal start of toplevel definition",
          "build.mill-<digits>] [error] one error found",
          "build.mill-<digits>] compile task failed",
          "65/65, 1 failed] ============================== jar ==============================",
          "1 tasks failed",
          "build.mill-<digits>] compile Compilation failed"
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

    test("colors") - integrationTest { tester =>
      import tester._
      // Make sure that running tests and tasks that print long multi-line colored output
      // doesn't accidentally truncate the output or mess up the colors. Assert both `out`
      // alone as well as `mergeErrIntoOut` to make sure things look correct

      val res = eval(("-i", "--ticker", "true", "test"))

      def normalize(s: String) = FullRunLogsTests.normalize(s)
        .map(
          _.replace(Console.RESET, "(X)")
          .replace(Console.RED, "(R)")
          .replace(Console.GREEN, "(G)")
          .replace(Console.BLUE, "(B)")
          .replace(Console.CYAN, "(C)")
          .replace(Console.MAGENTA, "(M)")
          .replace(Console.YELLOW, "(Y)")
        )

      assertGoldenLiteral(
        normalize(res.out0),
        List(
          "(B)Test run (X)foo.(Y)FooTest(X)(B) started(X)",
          "Test foo.(Y)FooTest(X).(C)testSimple(X) started",
          "(R)1(X)",
          "(R)2(X)",
          "(R)3(X)",
          "(R)4(X)",
          "(R)5(X)",
          "(R)6(X)",
          "(R)7(X)",
          "(R)8(X)",
          "(R)9(X)",
          "(R)10(X)",
          "(R)(G)11(X)",
          "(G)12(X)",
          "(G)13(X)",
          "(G)14(X)",
          "(G)15(X)",
          "(G)16(X)",
          "(G)17(X)",
          "(G)18(X)",
          "(G)19(X)",
          "(G)20(X)",
          "(G)(B)21(X)",
          "(B)22(X)",
          "(B)23(X)",
          "(B)24(X)",
          "(B)25(X)",
          "(B)26(X)",
          "(B)27(X)",
          "(B)28(X)",
          "(B)29(X)",
          "(B)30(X)",
          "(B)(C)31(X)",
          "(C)32(X)",
          "(C)33(X)",
          "(C)34(X)",
          "(C)35(X)",
          "(C)36(X)",
          "(C)37(X)",
          "(C)38(X)",
          "(C)39(X)",
          "(C)40(X)",
          "(C)(M)41(X)",
          "(M)42(X)",
          "(M)43(X)",
          "(M)44(X)",
          "(M)45(X)",
          "(M)46(X)",
          "(M)47(X)",
          "(M)48(X)",
          "(M)49(X)",
          "(M)50(X)",
          "(M)(Y)51(X)",
          "(Y)52(X)",
          "(Y)53(X)",
          "(Y)54(X)",
          "(Y)55(X)",
          "(Y)56(X)",
          "(Y)57(X)",
          "(Y)58(X)",
          "(Y)59(X)",
          "(Y)60(X)",
          "(Y)(X)",
          "Test foo.(Y)FooTest(X).(C)testSimple(X) finished, took  ... sec",
          "(B)Test run (X)foo.(Y)FooTest(X)(B) finished: (X)(B)0 failed(X)(B), (X)(B)0 ignored(X)(B), 1 total,  ...s(X)"
        )
      )
      // Sometimes order can be mixed up between stdout and stderr, even with mergeErrIntoOut
      retry(3) {
        val res2 = eval(("-i", "--ticker", "true", "test"), mergeErrIntoOut = true)
        assertGoldenLiteral(
          normalize(res2.out0),
          List(
            "============================== test ==============================",
            "<digits>] test.testForked Running Test Class foo.FooTest",
            "<digits>] (B)Test run (X)foo.(Y)FooTest(X)(B) started(X)",
            "<digits>] Test foo.(Y)FooTest(X).(C)testSimple(X) started",
            "<digits>] (R)1(X)",
            "<digits>] (R)2(X)",
            "<digits>] (R)3(X)",
            "<digits>] (R)4(X)",
            "<digits>] (R)5(X)",
            "<digits>] (R)6(X)",
            "<digits>] (R)7(X)",
            "<digits>] (R)8(X)",
            "<digits>] (R)9(X)",
            "<digits>] (R)10(X)",
            "<digits>] (R)(G)11(X)",
            "<digits>] (G)12(X)",
            "<digits>] (G)13(X)",
            "<digits>] (G)14(X)",
            "<digits>] (G)15(X)",
            "<digits>] (G)16(X)",
            "<digits>] (G)17(X)",
            "<digits>] (G)18(X)",
            "<digits>] (G)19(X)",
            "<digits>] (G)20(X)",
            "<digits>] (G)(B)21(X)",
            "<digits>] (B)22(X)",
            "<digits>] (B)23(X)",
            "<digits>] (B)24(X)",
            "<digits>] (B)25(X)",
            "<digits>] (B)26(X)",
            "<digits>] (B)27(X)",
            "<digits>] (B)28(X)",
            "<digits>] (B)29(X)",
            "<digits>] (B)30(X)",
            "<digits>] (B)(C)31(X)",
            "<digits>] (C)32(X)",
            "<digits>] (C)33(X)",
            "<digits>] (C)34(X)",
            "<digits>] (C)35(X)",
            "<digits>] (C)36(X)",
            "<digits>] (C)37(X)",
            "<digits>] (C)38(X)",
            "<digits>] (C)39(X)",
            "<digits>] (C)40(X)",
            "<digits>] (C)(M)41(X)",
            "<digits>] (M)42(X)",
            "<digits>] (M)43(X)",
            "<digits>] (M)44(X)",
            "<digits>] (M)45(X)",
            "<digits>] (M)46(X)",
            "<digits>] (M)47(X)",
            "<digits>] (M)48(X)",
            "<digits>] (M)49(X)",
            "<digits>] (M)50(X)",
            "<digits>] (M)(Y)51(X)",
            "<digits>] (Y)52(X)",
            "<digits>] (Y)53(X)",
            "<digits>] (Y)54(X)",
            "<digits>] (Y)55(X)",
            "<digits>] (Y)56(X)",
            "<digits>] (Y)57(X)",
            "<digits>] (Y)58(X)",
            "<digits>] (Y)59(X)",
            "<digits>] (Y)60(X)",
            "<digits>] (Y)(X)",
            "<digits>] Test foo.(Y)FooTest(X).(C)testSimple(X) finished, took  ... sec",
            "<digits>] (B)Test run (X)foo.(Y)FooTest(X)(B) finished: (X)(B)0 failed(X)(B), (X)(B)0 ignored(X)(B), 1 total,  ...s(X)",
            "101/<digits>] ============================== test =============================="
          )
        )
      }

      val res3 = eval(("-i", "--ticker", "true", "test.printColors"))

      assertGoldenLiteral(
        normalize(res3.out0),
        List(
          "(C)1(X)",
          "(C)2(X)",
          "(C)3(X)",
          "(C)4(X)",
          "(C)5(X)",
          "(C)6(X)",
          "(C)7(X)",
          "(C)8(X)",
          "(C)9(X)",
          "(C)10(X)",
          "(C)(M)11(X)",
          "(M)12(X)",
          "(M)13(X)",
          "(M)14(X)",
          "(M)15(X)",
          "(M)16(X)",
          "(M)17(X)",
          "(M)18(X)",
          "(M)19(X)",
          "(M)20(X)",
          "(M)(Y)21(X)",
          "(Y)22(X)",
          "(Y)23(X)",
          "(Y)24(X)",
          "(Y)25(X)",
          "(Y)26(X)",
          "(Y)27(X)",
          "(Y)28(X)",
          "(Y)29(X)",
          "(Y)30(X)",
          "(Y)(R)31(X)",
          "(R)32(X)",
          "(R)33(X)",
          "(R)34(X)",
          "(R)35(X)",
          "(R)36(X)",
          "(R)37(X)",
          "(R)38(X)",
          "(R)39(X)",
          "(R)40(X)",
          "(R)(G)41(X)",
          "(G)42(X)",
          "(G)43(X)",
          "(G)44(X)",
          "(G)45(X)",
          "(G)46(X)",
          "(G)47(X)",
          "(G)48(X)",
          "(G)49(X)",
          "(G)50(X)",
          "(G)(B)51(X)",
          "(B)52(X)",
          "(B)53(X)",
          "(B)54(X)",
          "(B)55(X)",
          "(B)56(X)",
          "(B)57(X)",
          "(B)58(X)",
          "(B)59(X)",
          "(B)60(X)",
          "(B)(X)"
        )
      )

      // Sometimes order can be mixed up between stdout and stderr, even with mergeErrIntoOut
      retry(3) {
        val res4 = eval(("-i", "--ticker", "true", "test.printColors"), mergeErrIntoOut = true)

        assertGoldenLiteral(
          normalize(res4.out0),
          List(
            "============================== test.printColors ==============================",
            "<digits>] test.printColors (C)1(X)",
            "<digits>] (C)2(X)",
            "<digits>] (C)3(X)",
            "<digits>] (C)4(X)",
            "<digits>] (C)5(X)",
            "<digits>] (C)6(X)",
            "<digits>] (C)7(X)",
            "<digits>] (C)8(X)",
            "<digits>] (C)9(X)",
            "<digits>] (C)10(X)",
            "<digits>] (C)(M)11(X)",
            "<digits>] (M)12(X)",
            "<digits>] (M)13(X)",
            "<digits>] (M)14(X)",
            "<digits>] (M)15(X)",
            "<digits>] (M)16(X)",
            "<digits>] (M)17(X)",
            "<digits>] (M)18(X)",
            "<digits>] (M)19(X)",
            "<digits>] (M)20(X)",
            "<digits>] (M)(Y)21(X)",
            "<digits>] (Y)22(X)",
            "<digits>] (Y)23(X)",
            "<digits>] (Y)24(X)",
            "<digits>] (Y)25(X)",
            "<digits>] (Y)26(X)",
            "<digits>] (Y)27(X)",
            "<digits>] (Y)28(X)",
            "<digits>] (Y)29(X)",
            "<digits>] (Y)30(X)",
            "<digits>] (Y)(R)31(X)",
            "<digits>] (R)32(X)",
            "<digits>] (R)33(X)",
            "<digits>] (R)34(X)",
            "<digits>] (R)35(X)",
            "<digits>] (R)36(X)",
            "<digits>] (R)37(X)",
            "<digits>] (R)38(X)",
            "<digits>] (R)39(X)",
            "<digits>] (R)40(X)",
            "<digits>] (R)(G)41(X)",
            "<digits>] (G)42(X)",
            "<digits>] (G)43(X)",
            "<digits>] (G)44(X)",
            "<digits>] (G)45(X)",
            "<digits>] (G)46(X)",
            "<digits>] (G)47(X)",
            "<digits>] (G)48(X)",
            "<digits>] (G)49(X)",
            "<digits>] (G)50(X)",
            "<digits>] (G)(B)51(X)",
            "<digits>] (B)52(X)",
            "<digits>] (B)53(X)",
            "<digits>] (B)54(X)",
            "<digits>] (B)55(X)",
            "<digits>] (B)56(X)",
            "<digits>] (B)57(X)",
            "<digits>] (B)58(X)",
            "<digits>] (B)59(X)",
            "<digits>] (B)60(X)",
            "<digits>] (B)(X)",
            "1/<digits>] ============================== test.printColors =============================="
          )
        )
      }
    }
  }
}
