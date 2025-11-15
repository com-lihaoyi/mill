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
        List("<digits>] <h<digits>>hello</h<digits>>")
      )

      assertGoldenLiteral(
        normalize(res.err),
        List(
          "============================== run --text hello ==============================",
          "build.mill-<digits>/<digits>] compile",
          "build.mill-<digits>] compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>/<digits>] compile",
          "<digits>] compiling <digits> Java source to out/compile.dest/classes ...",
          "<digits>] done compiling",
          "<digits>/<digits>] run",
          "<digits>/<digits>] ============================== run --text hello ============================== <digits>s"
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
          "build.mill-<digits>/<digits>] compile",
          "build.mill-<digits>] compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] done compiling",
          "<digits>/<digits>] compile",
          "<digits>] compiling <digits> Java source to out/compile.dest/classes ...",
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
          "build.mill-<digits>/<digits>] compile",
          "build.mill-<digits>] compiling <digits> Scala sources to out/mill-build/compile.dest/classes ...",
          "build.mill-<digits>] [error] build.mill:<digits>:<digits>",
          "build.mill-<digits>] E<digits> Illegal start of toplevel definition",
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
  }
}
