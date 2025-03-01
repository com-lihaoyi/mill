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
          s"""[build.mill] [info] compiling 1 Scala source to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
             |[build.mill] [info] done compiling
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
          s"""<dashes> run --text hello <dashes>
             |[build.mill-<digits>/<digits>] compile
             |[build.mill-<digits>] [info] compiling 1 Scala source to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
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
      assert(millProfile.exists(_.obj("label").str == "ivyDeps"))
      assert(millProfile.exists(_.obj("label").str == "javacOptions"))
      assert(millChromeProfile.exists(_.obj("name").str == "compile"))
      assert(millChromeProfile.exists(_.obj("name").str == "compileClasspath"))
      assert(millChromeProfile.exists(_.obj("name").str == "ivyDeps"))
      assert(millChromeProfile.exists(_.obj("name").str == "javacOptions"))
      // Profile logs for show itself
      assert(millProfile.exists(_.obj("label").str == "show"))
      assert(millChromeProfile.exists(_.obj("name").str == "show"))
    }
    test("failedTasksCounter") - integrationTest { tester =>
      import tester._

      val javaSource = os.Path(workspacePath, os.pwd)
      val fooJava = javaSource / "src" / "foo" / "Foo.java"
      val originalContent = os.read(fooJava)

      // Introduce a compilation error by adding an unclosed brace
      modifyFile(fooJava, _.replace("class Foo", "class Foo {"))

      // Run with ticker to see the failed tasks count for compile
      val compileRes = eval(("--ticker", "true", "compile"))
      compileRes.isSuccess ==> false

      // Verify the output shows failed tasks count in the progress indicator
      val failedPattern = "\\[(\\d+)/(\\d+),\\s*(\\d+)\\s*failed\\]".r // Matches [X/Y, N failed]
      val failedMatch = failedPattern.findFirstIn(compileRes.err)
      failedMatch.isDefined ==> true

      // Extract and verify the number of failed tasks
      val failedCount =
        failedPattern.findFirstMatchIn(compileRes.err).map(_.group(3).toInt).getOrElse(0)
      failedCount ==> 1 // Expecting 1 failed task for compile

      // Run jar task with --keep-going to see upstream failures
      val jarKeepGoingRes = eval(("--ticker", "true", "--keep-going", "jar"))
      jarKeepGoingRes.isSuccess ==> false

      // Verify the output shows failed tasks count in the progress indicator
      val jarKeepGoingFailedMatch = failedPattern.findFirstIn(jarKeepGoingRes.err)
      jarKeepGoingFailedMatch.isDefined ==> true

      // Extract and verify the number of failed tasks
      val jarKeepGoingFailedCount =
        failedPattern.findFirstMatchIn(jarKeepGoingRes.err).map(_.group(3).toInt).getOrElse(0)
      jarKeepGoingFailedCount ==> 1 // Expecting 1 failed task for jar with --keep-going

      // Run jar task without --keep-going to see it fail immediately
      val jarRes = eval(("--ticker", "true", "jar"))
      jarRes.isSuccess ==> false

      // Verify the output shows failed tasks count in the progress indicator
      val jarFailedMatch = failedPattern.findFirstIn(jarRes.err)
      jarFailedMatch.isDefined ==> true

      // Extract and verify the number of failed tasks
      val jarFailedCount =
        failedPattern.findFirstMatchIn(jarRes.err).map(_.group(3).toInt).getOrElse(0)
      jarFailedCount ==> 1 // Expecting 1 failed task for jar without --keep-going

      // Restore the original content
      modifyFile(fooJava, _ => originalContent)
    }
  }
}
