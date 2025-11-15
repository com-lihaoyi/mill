package mill.integration

import mill.constants.OutFiles
import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsTests extends UtestIntegrationTestSuite {

  def normalize(s: String) = s.replace('\\', '/')
    .replaceAll("\\d+", "<digits>")
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
          "compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "done compiling",
          "compiling <digits> Java source to out/compile.dest/classes ...",
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
        List("<h<digits>>hello</h<digits>>")
      )

      assertGoldenLiteral(
        normalize(res.err),
        List(
          "============================== run --text hello ==============================",
          "build.mill-<digits>] compile compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] compile compiling <digits> Java source to out/compile.dest/classes ...",
          "<digits>] done compiling",
          "<digits>] run <digits>/<digits>] ============================== run --text hello ============================== <digits>s"
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
          "build.mill-<digits>] compile compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] compile compiling <digits> Java source to out/compile.dest/classes ...",
          "<digits>] [error] src/foo/Foo.java:<digits>:<digits>",
          "<digits>] reached end of file while parsing",
          "<digits>] compile task failed",
          "<digits>/<digits>, <digits> failed] ============================== jar ============================== <digits>s",
          "<digits> tasks failed",
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
          "build.mill-<digits>] compile compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] [error] build.mill:<digits>:<digits>",
          "build.mill-<digits>] [E<digits>] Illegal start of toplevel definition",
          "build.mill-<digits>] [error] one error found",
          "build.mill-<digits>] compile task failed",
          "<digits>/<digits>, <digits> failed] ============================== jar ============================== <digits>s",
          "<digits> tasks failed",
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

      def normalize(s: String) = s
        .replace(Console.RESET, "(X)")
        .replace(Console.RED, "(R)")
        .replace(Console.GREEN, "(G)")
        .replace(Console.BLUE, "(B)")
        .replace(Console.CYAN, "(C)")
        .replace(Console.MAGENTA, "(M)")
        .replace(Console.YELLOW, "(Y)")
        .replaceAll(" \\d+\\\\.\\d+", " ...")
        .linesIterator
        .toList

      assertGoldenLiteral(
        normalize(res.out0),
        List(
          "(B)Test run (X)foo.(Y)FooTest(X)(B) started(X)(X)",
          "Test foo.(Y)FooTest(X).(C)testSimple(X) started(X)",
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
          "(Y)(X)(X)",
          "Test foo.(Y)FooTest(X).(C)testSimple(X) finished, took ...05 sec(X)",
          "(B)Test run (X)foo.(Y)FooTest(X)(B) finished: (X)(B)0 failed(X)(B), (X)(B)0 ignored(X)(B), 1 total, ...06s(X)(X)"
        )
      )
      val res2 = eval(("-i", "--ticker", "true", "test"), mergeErrIntoOut = true)
      assertGoldenLiteral(
        normalize(res2.out0),
        List(
          "============================== test ==============================",
          "101] test.testForked Running Test Class foo.FooTest(X)",
          "101] (B)Test run (X)foo.(Y)FooTest(X)(B) started(X)(X)",
          "101] Test foo.(Y)FooTest(X).(C)testSimple(X) started(X)",
          "101] (R)1(X)",
          "101] (R)2(X)",
          "101] (R)3(X)",
          "101] (R)4(X)",
          "101] (R)5(X)",
          "101] (R)6(X)",
          "101] (R)7(X)",
          "101] (R)8(X)",
          "101] (R)9(X)",
          "101] (R)10(X)",
          "101] (R)(G)11(X)",
          "101] (G)12(X)",
          "101] (G)13(X)",
          "101] (G)14(X)",
          "101] (G)15(X)",
          "101] (G)16(X)",
          "101] (G)17(X)",
          "101] (G)18(X)",
          "101] (G)19(X)",
          "101] (G)20(X)",
          "101] (G)(B)21(X)",
          "101] (B)22(X)",
          "101] (B)23(X)",
          "101] (B)24(X)",
          "101] (B)25(X)",
          "101] (B)26(X)",
          "101] (B)27(X)",
          "101] (B)28(X)",
          "101] (B)29(X)",
          "101] (B)30(X)",
          "101] (B)(C)31(X)",
          "101] (C)32(X)",
          "101] (C)33(X)",
          "101] (C)34(X)",
          "101] (C)35(X)",
          "101] (C)36(X)",
          "101] (C)37(X)",
          "101] (C)38(X)",
          "101] (C)39(X)",
          "101] (C)40(X)",
          "101] (C)(M)41(X)",
          "101] (M)42(X)",
          "101] (M)43(X)",
          "101] (M)44(X)",
          "101] (M)45(X)",
          "101] (M)46(X)",
          "101] (M)47(X)",
          "101] (M)48(X)",
          "101] (M)49(X)",
          "101] (M)50(X)",
          "101] (M)(Y)51(X)",
          "101] (Y)52(X)",
          "101] (Y)53(X)",
          "101] (Y)54(X)",
          "101] (Y)55(X)",
          "101] (Y)56(X)",
          "101] (Y)57(X)",
          "101] (Y)58(X)",
          "101] (Y)59(X)",
          "101] (Y)60(X)",
          "101] (Y)(X)(X)",
          "101] Test foo.(Y)FooTest(X).(C)testSimple(X) finished, took ...05 sec(X)",
          "101] (B)Test run (X)foo.(Y)FooTest(X)(B) finished: (X)(B)0 failed(X)(B), (X)(B)0 ignored(X)(B), 1 total, ...06s(X)(X)",
          "101/101] ============================== test ============================== 1s"
        )
      )

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
          "(B)(X)(X)"
        )
      )

      val res4 = eval(("-i", "--ticker", "true", "test.printColors"), mergeErrIntoOut = true)

      assertGoldenLiteral(
        normalize(res4.out0),
        List(
          "============================== test.printColors ==============================",
          "1] test.printColors (C)1(X)",
          "1] (C)2(X)",
          "1] (C)3(X)",
          "1] (C)4(X)",
          "1] (C)5(X)",
          "1] (C)6(X)",
          "1] (C)7(X)",
          "1] (C)8(X)",
          "1] (C)9(X)",
          "1] (C)10(X)",
          "1] (C)(M)11(X)",
          "1] (M)12(X)",
          "1] (M)13(X)",
          "1] (M)14(X)",
          "1] (M)15(X)",
          "1] (M)16(X)",
          "1] (M)17(X)",
          "1] (M)18(X)",
          "1] (M)19(X)",
          "1] (M)20(X)",
          "1] (M)(Y)21(X)",
          "1] (Y)22(X)",
          "1] (Y)23(X)",
          "1] (Y)24(X)",
          "1] (Y)25(X)",
          "1] (Y)26(X)",
          "1] (Y)27(X)",
          "1] (Y)28(X)",
          "1] (Y)29(X)",
          "1] (Y)30(X)",
          "1] (Y)(R)31(X)",
          "1] (R)32(X)",
          "1] (R)33(X)",
          "1] (R)34(X)",
          "1] (R)35(X)",
          "1] (R)36(X)",
          "1] (R)37(X)",
          "1] (R)38(X)",
          "1] (R)39(X)",
          "1] (R)40(X)",
          "1] (R)(G)41(X)",
          "1] (G)42(X)",
          "1] (G)43(X)",
          "1] (G)44(X)",
          "1] (G)45(X)",
          "1] (G)46(X)",
          "1] (G)47(X)",
          "1] (G)48(X)",
          "1] (G)49(X)",
          "1] (G)50(X)",
          "1] (G)(B)51(X)",
          "1] (B)52(X)",
          "1] (B)53(X)",
          "1] (B)54(X)",
          "1] (B)55(X)",
          "1] (B)56(X)",
          "1] (B)57(X)",
          "1] (B)58(X)",
          "1] (B)59(X)",
          "1] (B)60(X)",
          "1] (B)(X)(X)",
          "1/1] ============================== test.printColors =============================="
        )
      )
    }
  }
}
