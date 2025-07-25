package mill.integration

import mill.constants.OutFiles
import mill.testkit.UtestIntegrationTestSuite
import utest.*

// Run simple commands on a simple build and check their entire output and some
// metadata files, ensuring we don't get spurious warnings or logging messages
// slipping in and the important parts of the logs and output files are present
object FullRunLogsTests extends UtestIntegrationTestSuite {

  def tests: Tests = Tests {
    test("noticker") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "false", "run", "--text", "hello"))

      res.isSuccess ==> true
      assert(res.out == "<h1>hello</h1>")
      assert(
        res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n") ==
          s"""|[info] compiling 3 Scala sources to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
              |[info] done compiling
              |[info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
              |[info] done compiling""".stripMargin.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")
      )
    }
    test("ticker") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "true", "run", "--text", "hello"))
      res.isSuccess ==> true
      assert("\\[\\d+\\] <h1>hello</h1>".r.matches(res.out))

      val expectedErrorRegex = java.util.regex.Pattern
        .quote(
          s"""|<dashes> run --text hello <dashes>
              |[build.mill-<digits>/<digits>] compile
              |[build.mill-<digits>] [info] compiling 3 Scala sources to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
              |[build.mill-<digits>] [info] done compiling
              |[<digits>/<digits>] compile
              |[<digits>] [info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
              |[<digits>] [info] done compiling
              |[<digits>/<digits>] run
              |[<digits>/<digits>] <dashes> run --text hello <dashes> <digits>s"""
            .stripMargin
            .replaceAll("(\r\n)|\r", "\n")
            .replace('\\', '/')
        )
        .replace("<digits>", "\\E\\d+\\Q")
        .replace("<dashes>", "\\E=+\\Q")

      val normErr = res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")
      assert(expectedErrorRegex.r.matches(normErr))
    }
    test("keepGoingFailure") - integrationTest { tester =>
      import tester._

      modifyFile(workspacePath / "src/foo/Foo.java", _ + "class Bar")
      val res = eval(("--ticker", "true", "--keep-going", "jar"))
      res.isSuccess ==> false

      val expectedErrorRegex = java.util.regex.Pattern
        .quote(
          s"""|<dashes> jar <dashes>
              |[build.mill-<digits>/<digits>] compile
              |[build.mill-<digits>] [info] compiling 3 Scala sources to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
              |[build.mill-<digits>] [info] done compiling
              |[<digits>/<digits>] compile
              |[<digits>] [info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
              |[<digits>] [error] ${tester.workspacePath}/src/foo/Foo.java:36:10: reached end of file while parsing
              |[<digits>] compile failed
              |[<digits>/<digits>, 1 failed] <dashes> jar <dashes> <digits>s
              |1 tasks failed
              |compile javac returned non-zero exit code"""
            .stripMargin
            .replaceAll("(\r\n)|\r", "\n")
            .replace('\\', '/')
        )
        .replace("<digits>", "\\E\\d+\\Q")
        .replace("<dashes>", "\\E=+\\Q")

      val normErr = res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")
      assert(expectedErrorRegex.r.matches(normErr))
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
      assert(millChromeProfile.exists(_.obj.get("name") == ujson.Str("compile")))
      assert(millChromeProfile.exists(_.obj.get("name") == ujson.Str("compileClasspath")))
      assert(millChromeProfile.exists(_.obj.get("name") == ujson.Str("mvnDeps")))
      assert(millChromeProfile.exists(_.obj.get("name") == ujson.Str("javacOptions")))
      // Profile logs for show itself
      assert(millProfile.exists(_.obj("label").str == "show"))
      assert(millChromeProfile.exists(_.obj.get("name") == ujson.Str("show")))
    }
  }
}
