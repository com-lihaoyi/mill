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
      assert(res.out.contains("<h1>hello</h1>"))
      assert(
        res.err.toLowerCase.replace('\\', '/').replaceAll("(\r\n)|\r", "\n").contains(
          "compiling"
        )
      )
    }
    test("ticker") - integrationTest { tester =>
      import tester._

      val res = eval(("--ticker", "true", "run", "--text", "hello"))
      res.isSuccess ==> true
      assert(res.out.contains("<h1>hello</h1>"))

      val expectedErrorRegex = "(?s).*compile.*".r
      val normErr = res.err.replace('\\', '/').replaceAll("(\r\n)|\r", "\n")
      assert(expectedErrorRegex.matches(normErr))
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

      // First ensure clean compilation works
      val cleanBuild = eval(("compile"))
      println(s"[DEBUG] failedTasksCounter - clean build isSuccess: ${cleanBuild.isSuccess}")
      println(s"[DEBUG] failedTasksCounter - clean build err: '${cleanBuild.err}'")
      cleanBuild.isSuccess ==> true

      // Modify the Java source to introduce a compilation error
      val javaSource = os.Path(workspacePath, os.pwd)
      val fooJava = javaSource / "src" / "foo" / "Foo.java"
      val originalContent = os.read(fooJava)
      println(s"[DEBUG] failedTasksCounter - original content: '${originalContent}'")

      // Write file without explicit permissions on Windows
      os.write.over(
        fooJava,
        data = originalContent.replace("class Foo", "class Foo {"),
        createFolders = true
      )
      println(s"[DEBUG] failedTasksCounter - modified content: '${os.read(fooJava)}'")

      // Run with ticker to see the failed tasks count
      val res = eval(("--ticker", "true", "compile"))
      println(s"[DEBUG] failedTasksCounter - failed build isSuccess: ${res.isSuccess}")
      println(s"[DEBUG] failedTasksCounter - failed build out: '${res.out}'")
      println(s"[DEBUG] failedTasksCounter - failed build err: '${res.err}'")
      res.isSuccess ==> false

      // Verify the output shows failed tasks count in the progress indicator
      val expectedPattern = "\\[\\d+/\\d+,\\s*\\d+\\s*failed\\]".r // Matches [X/Y, N failed]
      println(
        s"[DEBUG] failedTasksCounter - pattern matches: ${expectedPattern.findFirstIn(res.err).isDefined}"
      )
      println(
        s"[DEBUG] failedTasksCounter - matches found: ${expectedPattern.findAllIn(res.err).toList}"
      )
      expectedPattern.findFirstIn(res.err).isDefined ==> true

      // Restore original content for cleanup
      os.write.over(
        fooJava,
        data = originalContent,
        createFolders = true
      )
    }
    test("failure-messages") - integrationTest { tester =>
      import tester._

      // Create a compilation error first
      val javaSource = os.Path(workspacePath, os.pwd)
      val fooJava = javaSource / "src" / "foo" / "Foo.java"
      val originalContent = os.read(fooJava)

      // Introduce a compilation error
      os.write.over(
        fooJava,
        data = originalContent.replace("class Foo", "class Foo {"),
        createFolders = true
      )

      // Test without -k flag: should stop at first failure and show proper failure count
      val res1 = eval(("--ticker", "true", "run"))  // run depends on compile, so should have downstream tasks
      println(s"[DEBUG] res1.err: '${fansi.Str(res1.err).plainText}'")
      assert(res1.isSuccess == false)
      // Should show exactly one failure, not counting skipped downstream tasks
      assert(fansi.Str(res1.err).plainText.contains("1 tasks failed"))
      // Should not contain inflated failure counts
      assert(!fansi.Str(res1.err).plainText.contains("2 tasks failed"))
      assert(!fansi.Str(res1.err).plainText.contains("3 tasks failed"))

      // Test with -k flag: should continue after failures but still count failures correctly
      val res2 = eval(("--ticker", "true", "-k", "run"))
      println(s"[DEBUG] res2.err: '${fansi.Str(res2.err).plainText}'")
      assert(res2.isSuccess == false)
      // Should still show exactly one failure, even though downstream tasks were attempted
      assert(fansi.Str(res2.err).plainText.contains("1 tasks failed"))
      // Should not contain inflated failure counts that would indicate counting skipped tasks
      assert(!fansi.Str(res2.err).plainText.contains("2 tasks failed"))
      assert(!fansi.Str(res2.err).plainText.contains("3 tasks failed"))

      // Test non-interactive mode without -k
      val res3 = eval(("--ticker", "false", "run"))
      println(s"[DEBUG] res3.err: '${fansi.Str(res3.err).plainText}'")
      assert(res3.isSuccess == false)
      assert(fansi.Str(res3.err).plainText.contains("1 tasks failed"))
      assert(!fansi.Str(res3.err).plainText.contains("2 tasks failed"))

      // Test non-interactive mode with -k
      val res4 = eval(("--ticker", "false", "-k", "run"))
      println(s"[DEBUG] res4.err: '${fansi.Str(res4.err).plainText}'")
      assert(res4.isSuccess == false)
      assert(fansi.Str(res4.err).plainText.contains("1 tasks failed"))
      assert(!fansi.Str(res4.err).plainText.contains("2 tasks failed"))

      // Restore original content for cleanup
      os.write.over(
        fooJava,
        data = originalContent,
        createFolders = true
      )
    }
  }
}
