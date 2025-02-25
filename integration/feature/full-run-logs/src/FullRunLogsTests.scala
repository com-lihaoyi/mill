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

      // Test 1: Run without -k flag (default behavior)
      val resWithoutK = eval(("--ticker", "true", "compile"))
      println(
        s"[DEBUG] failedTasksCounter - failed build without -k isSuccess: ${resWithoutK.isSuccess}"
      )
      println(s"[DEBUG] failedTasksCounter - failed build without -k err: '${resWithoutK.err}'")
      resWithoutK.isSuccess ==> false

      // Verify the output shows failed tasks count in the progress indicator
      // and that only actual failures are counted (not skipped tasks)
      val expectedPattern =
        "\\[\\d+/\\d+,\\s*1\\s*failed\\]".r // Matches [X/Y, 1 failed] - should be exactly 1 failure
      println(
        s"[DEBUG] failedTasksCounter - pattern matches without -k: ${expectedPattern.findFirstIn(resWithoutK.err).isDefined}"
      )
      println(
        s"[DEBUG] failedTasksCounter - matches found without -k: ${expectedPattern.findAllIn(resWithoutK.err).toList}"
      )
      expectedPattern.findFirstIn(resWithoutK.err).isDefined ==> true

      // Test 2: Run with -k flag (keepGoing mode)
      val resWithK = eval(("--ticker", "true", "-k", "compile"))
      println(s"[DEBUG] failedTasksCounter - failed build with -k isSuccess: ${resWithK.isSuccess}")
      println(s"[DEBUG] failedTasksCounter - failed build with -k err: '${resWithK.err}'")
      resWithK.isSuccess ==> false

      // Verify the output shows failed tasks count in the progress indicator
      // and that only actual failures are counted (not skipped tasks)
      println(
        s"[DEBUG] failedTasksCounter - pattern matches with -k: ${expectedPattern.findFirstIn(resWithK.err).isDefined}"
      )
      println(
        s"[DEBUG] failedTasksCounter - matches found with -k: ${expectedPattern.findAllIn(resWithK.err).toList}"
      )
      expectedPattern.findFirstIn(resWithK.err).isDefined ==> true

      // Test 3: Create a dependency structure where one failure causes multiple skipped tasks
      // First, restore the original content to make the build clean
      os.write.over(
        fooJava,
        data = originalContent,
        createFolders = true
      )

      // Create a dependency structure with multiple Java files
      // File 1: Base.java - This will have the error
      val baseJava = javaSource / "src" / "foo" / "Base.java"
      os.write.over(
        baseJava,
        """package foo;

public class Base {
    public String getMessage() {
        return "Base message";
    }
}
""",
        createFolders = true
      )

      // File 2: Dependent1.java - Depends on Base.java
      val dependent1Java = javaSource / "src" / "foo" / "Dependent1.java"
      os.write.over(
        dependent1Java,
        """package foo;

public class Dependent1 {
    private Base base = new Base();
    
    public String getMessage() {
        return base.getMessage() + " -> Dependent1";
    }
}
""",
        createFolders = true
      )

      // File 3: Dependent2.java - Also depends on Base.java
      val dependent2Java = javaSource / "src" / "foo" / "Dependent2.java"
      os.write.over(
        dependent2Java,
        """package foo;

public class Dependent2 {
    private Base base = new Base();
    
    public String getMessage() {
        return base.getMessage() + " -> Dependent2";
    }
}
""",
        createFolders = true
      )

      // Compile once to make sure everything is working
      val cleanMultiCompile = eval(("compile"))
      cleanMultiCompile.isSuccess ==> true

      // Now introduce an error in Base.java
      os.write.over(
        baseJava,
        """package foo;

public class Base {
    public String getMessage() {
        return "Base message" // Missing semicolon - will cause compilation error
    }
}
""",
        createFolders = true
      )

      // Run with -k flag to see if skipped tasks are counted as failures
      val resWithDeps = eval(("--ticker", "true", "-k", "compile"))
      println(
        s"[DEBUG] failedTasksCounter - deps build with -k isSuccess: ${resWithDeps.isSuccess}"
      )
      println(s"[DEBUG] failedTasksCounter - deps build with -k err: '${resWithDeps.err}'")
      resWithDeps.isSuccess ==> false

      // Print the full error output to debug the actual format
      println(s"[DEBUG] FULL ERROR OUTPUT:\n${resWithDeps.err}")

      // Check for the progress indicator pattern with exactly 1 failure
      println(
        s"[DEBUG] Progress indicator pattern matches: ${expectedPattern.findFirstIn(resWithDeps.err).isDefined}"
      )
      println(
        s"[DEBUG] Progress indicator matches found: ${expectedPattern.findAllIn(resWithDeps.err).toList}"
      )

      // Verify that only 1 task is counted as failed in the progress indicator
      // even though multiple files are affected (Base.java fails, and Dependent1.java and Dependent2.java are skipped)
      expectedPattern.findFirstIn(resWithDeps.err).isDefined ==> true

      // Also check the final summary line
      val taskFailedPattern = "1\\s+tasks?\\s+failed".r
      println(
        s"[DEBUG] Task failed pattern matches: ${taskFailedPattern.findFirstIn(resWithDeps.err).isDefined}"
      )
      println(
        s"[DEBUG] Task failed matches found: ${taskFailedPattern.findAllIn(resWithDeps.err).toList}"
      )
      taskFailedPattern.findFirstIn(resWithDeps.err).isDefined ==> true

      // Clean up by deleting the additional files
      try {
        os.remove(baseJava)
        os.remove(dependent1Java)
        os.remove(dependent2Java)
      } catch {
        case _: Throwable => // Ignore errors during cleanup
      }

      // Restore original content for cleanup
      os.write.over(
        fooJava,
        data = originalContent,
        createFolders = true
      )
    }
  }
}
