package mill.integration
import mill.constants.OutFiles.OutFiles
import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsFailureTests extends UtestIntegrationTestSuite {

  import FullRunLogsUtils.normalize

  def tests: Tests = Tests {
    test("keepGoingFailure") - integrationTest { tester =>
      import tester.*

      // Scala and Java parsing errors
      modifyFile(
        workspacePath / "src/foo/Foo.scala",
        _ + "class Bar { /*comment*/ def bar = { val x: String =  \"omg"
      )
      modifyFile(
        workspacePath / "src/foo/Foo.java",
        _ + "class Bar { /*comment*/ void bar(){ final String x = \"omg"
      )
      val res = eval(
        ("--ticker", "true", "--color=true", "--keep-going", "jar"),
        propagateEnv = false
      )
      res.isSuccess ==> false
      assertGoldenLiteral(
        normalize(res.result.err.text()),
        // We passed in `--color=true` so we should expect colored output
        List(
          "<dashes> jar <dashes>",
          "(B)build.mill-<digits>] compile(X) compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "(B)build.mill-<digits>](X) done compiling",
          "(B)<digits>] compile(X) compiling 1 Scala source and 1 Java source to out/compile.dest/classes ...",
          "(B)<digits>](X) [(R)error(X)] (R)src/foo/Foo.java(Z):(R)36(Z):(R)54(Z)",
          "(B)<digits>](X) (Y)class(X) Bar { (B)/*comment*/(X) (Y)void(X) bar(){ (Y)final(X) String x = \"omg",
          "(B)<digits>](X)                                                      (R)^(Z)",
          "(B)<digits>](X) unclosed string literal",
          "(B)<digits>](X) ",
          "(B)<digits>](X) [(R)error(X)] (R)src/foo/Foo.java(Z):(R)36(Z):(R)58(Z)",
          "(B)<digits>](X) (Y)class(X) Bar { (B)/*comment*/(X) (Y)void(X) bar(){ (Y)final(X) String x = \"omg",
          "(B)<digits>](X)                                                          (R)^(Z)",
          "(B)<digits>](X) '}' expected but eof found.",
          "(B)<digits>](X) ",
          "(B)<digits>](X) [(R)error(X)] (R)src/foo/Foo.scala(Z):(R)2(Z):(R)54(Z)",
          "(B)<digits>](X) (Z)(Y)class(Z) (M)Bar(Z) { (B)/*comment*/(Z) (Y)def(Z) (C)bar(Z) = { (Y)val(Z) (C)x(Z): (M)String(Z) =  \"omg",
          "(B)<digits>](X)                                                      (R)^(Z)",
          "(B)<digits>](X) unclosed string literal",
          "(B)<digits>](X) ",
          "(B)<digits>](X) [(R)error(X)] three errors found",
          "(B)<digits>](X) [(R)error(X)] compile task failed",
          ".../..., (R)1 failed(X)] <dashes> jar <dashes>",
          "(R)<digits>] (X)[(R)error(X)] compile Compilation failed"
        )
      )

      // Java name resolution error
      modifyFile(workspacePath / "src/foo/Foo.scala", _ + "\"}}")
      modifyFile(
        workspacePath / "src/foo/Foo.java",
        _.replace("final String x", "final Strin x") + "\";}}"
      )
      val res2 = eval(
        ("--ticker", "true", "--color=true", "--keep-going", "jar"),
        propagateEnv = false
      )
      res2.isSuccess ==> false
      assertGoldenLiteral(
        normalize(res2.result.err.text()),
        // We passed in `--color=true` so we should expect colored output
        List(
          "<dashes> jar <dashes>",
          "(B)<digits>] compile(X) compiling 1 Scala source and 1 Java source to out/compile.dest/classes ...",
          "(B)<digits>](X) [(R)error(X)] (R)src/foo/Foo.java(Z):(R)36(Z):(R)43(Z)",
          "(B)<digits>](X) (Y)class(X) Bar { (B)/*comment*/(X) (Y)void(X) bar(){ (Y)final(X) Strin x = (G)\"omg\"(X);}}",
          "(B)<digits>](X)                                           (R)^^^^^(Z)",
          "(B)<digits>](X) cannot find symbol",
          "(B)<digits>](X)   symbol:   class Strin",
          "(B)<digits>](X)   location: class foo.Bar",
          "(B)<digits>](X) ",
          "(B)<digits>](X) [(R)error(X)] compile task failed",
          ".../..., (R)1 failed(X)] <dashes> jar <dashes>",
          "(R)<digits>] (X)[(R)error(X)] compile javac returned non-zero exit code"
        )
      )

      // Scala name resolution error
      modifyFile(workspacePath / "src/foo/Foo.scala", _.replace("x: String", "x: Strig"))
      val res3 = eval(
        ("--ticker", "true", "--color=true", "--keep-going", "jar"),
        propagateEnv = false
      )
      res3.isSuccess ==> false
      assertGoldenLiteral(
        normalize(res3.result.err.text()),
        // We passed in `--color=true` so we should expect colored output
        List(
          "<dashes> jar <dashes>",
          "(B)<digits>] compile(X) compiling 1 Scala source and 1 Java source to out/compile.dest/classes ...",
          "(B)<digits>](X) [(R)error(X)] (R)src/foo/Foo.scala(Z):(R)2(Z):(R)44(Z)",
          "(B)<digits>](X) (Z)(Y)class(Z) (M)Bar(Z) { (B)/*comment*/(Z) (Y)def(Z) (C)bar(Z) = { (Y)val(Z) (C)x(Z): (M)Strig(Z) =  (G)\"omg\"(Z)}}",
          "(B)<digits>](X)                                            (R)^^^^^(Z)",
          "(B)<digits>](X) Not found: type Strig - did you mean String?",
          "(B)<digits>](X) ",
          "(B)<digits>](X) [(R)error(X)] one error found",
          "(B)<digits>](X) [(R)error(X)] compile task failed",
          ".../..., (R)1 failed(X)] <dashes> jar <dashes>",
          "(R)<digits>] (X)[(R)error(X)] compile Compilation failed"
        )
      )
    }
    test("keepGoingMetaFailure") - integrationTest { tester =>
      import tester.*
      modifyFile(workspacePath / "build.mill", _ + "?")

      val res2 = eval(("--ticker", "true", "--keep-going", "jar"), propagateEnv = false)
      res2.isSuccess ==> false

      assertGoldenLiteral(
        normalize(res2.result.err.text()),
        List(
          "<dashes> jar <dashes>",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] [error] build.mill:77:1",
          "build.mill-<digits>] ?",
          "build.mill-<digits>] ^",
          "build.mill-<digits>] Illegal start of toplevel definition",
          "build.mill-<digits>] ",
          "build.mill-<digits>] [error] one error found",
          "build.mill-<digits>] [error] compile task failed",
          ".../..., 1 failed] <dashes> jar <dashes>",
          "build.mill-<digits>] [error] compile Compilation failed"
        )
      )
    }
    test("show") - integrationTest { tester =>
      import tester.*
      // Make sure when we have nested evaluations, e.g. due to usage of evaluator commands
      // like `show`, both outer and inner evaluations hae their metadata end up in the
      // same profile files so a user can see what's going on in either
      eval(("show", "compile"), propagateEnv = false)
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
      import tester.*

      val res = eval(
        ("--ticker", "true", "--color=true", "exception"),
        mergeErrIntoOut = true,
        propagateEnv = false
      )
      res.isSuccess ==> false

      assertGoldenLiteral(
        normalize(res.result.out.text()),
        List(
          "<dashes> exception <dashes>",
          "(B)build.mill-<digits>] compile(X) compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "(B)build.mill-<digits>](X) done compiling",
          ".../..., (R)1 failed(X)] <dashes> exception <dashes>",
          "(R)<digits>] (X)[(R)error(X)] exception",
          "(R)java.lang.Exception(X): boom",
          "  (R)build_.package_.exceptionHelper(X)((R)build.mill(X):(R)6(X))",
          "  (R)build_.package_.exception$$anonfun$1(X)((R)build.mill(X):(R)8(X))",
          "  (R)mill.api.Task$Named.evaluate(X)((R)Task.scala(X):(R)370(X))",
          "  (R)mill.api.Task$Named.evaluate$(X)((R)Task.scala(X):(R)355(X))",
          "  (R)mill.api.Task$Command.evaluate(X)((R)Task.scala(X):(R)442(X))",
          "(R)java.lang.RuntimeException(X): bang",
          "  (R)build_.package_.exceptionHelper(X)((R)build.mill(X):(R)6(X))",
          "  (R)build_.package_.exception$$anonfun$1(X)((R)build.mill(X):(R)8(X))",
          "  (R)mill.api.Task$Named.evaluate(X)((R)Task.scala(X):(R)370(X))",
          "  (R)mill.api.Task$Named.evaluate$(X)((R)Task.scala(X):(R)355(X))",
          "  (R)mill.api.Task$Command.evaluate(X)((R)Task.scala(X):(R)442(X))"
        )
      )
    }
    test("interleaved-compile-errors") - integrationTest { tester =>
      import tester.*

      val res = eval(
        ("--ticker", "true", "{broken1,broken2}.compile"),
        mergeErrIntoOut = true,
        propagateEnv = false
      )
      assert(!res.isSuccess)

      // Make sure that when facing multiple compile errors in quick succession, the
      // errors themselves are printed whole and not interleaved with
      assertGoldenLiteral(
        normalize(
          // Normalize the `brokenN` module names since those may occur in different orders
          res.result.out.text().replace("broken1", "brokenN").replace("broken2", "brokenN")
        ),
        List(
          "<dashes> {brokenN,brokenN}.compile <dashes>",
          "build.mill-<digits>] compile compiling 3 Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>] brokenN.compile compiling 1 Java source to out/brokenN/compile.dest/classes ...",
          "<digits>] brokenN.compile compiling 1 Java source to out/brokenN/compile.dest/classes ...",
          "<digits>] [error] broken/src/Foo.java:1:0",
          "<digits>] ?",
          "<digits>] ",
          "<digits>] class, interface, enum, or record expected",
          "<digits>] ",
          "<digits>] [error] broken/src/Foo.java:1:0",
          "<digits>] ?",
          "<digits>] ",
          "<digits>] class, interface, enum, or record expected",
          "<digits>] ",
          ".../..., 2 failed] <dashes> {brokenN,brokenN}.compile <dashes>",
          "<digits>] [error] brokenN.compile javac returned non-zero exit code",
          "<digits>] [error] brokenN.compile javac returned non-zero exit code"
        )
      )
      // Make sure the `.log` files on disk contain what we expect
      assertGoldenLiteral(
        normalize(os.read(workspacePath / "out/broken1/compile.log")),
        List(
          "compiling 1 Java source to out/broken1/compile.dest/classes ...",
          "[error] broken/src/Foo.java:1:0",
          "?",
          "",
          "class, interface, enum, or record expected",
          ""
        )
      )
      assertGoldenLiteral(
        normalize(os.read(workspacePath / "out/broken2/compile.log")),
        List(
          "compiling 1 Java source to out/broken2/compile.dest/classes ...",
          "[error] broken/src/Foo.java:1:0",
          "?",
          "",
          "class, interface, enum, or record expected",
          ""
        )
      )
    }

  }
}
