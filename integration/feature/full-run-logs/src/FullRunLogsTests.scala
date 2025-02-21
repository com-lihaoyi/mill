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
          s"""============================== run --text hello ==============================
             |[build.mill-<digits>/<digits>] compile
             |[build.mill-<digits>] [info] compiling 1 Scala source to ${tester.workspacePath}/out/mill-build/compile.dest/classes ...
             |[build.mill-<digits>] [info] done compiling
             |[<digits>/<digits>] compile
             |[<digits>] [info] compiling 1 Java source to ${tester.workspacePath}/out/compile.dest/classes ...
             |[<digits>] [info] done compiling
             |[<digits>/<digits>] run
             |[<digits>/<digits>] ============================== run --text hello ============================== <digits>s"""
            .stripMargin
            .replaceAll("(\r\n)|\r", "\n")
            .replace('\\', '/')
        )
        .replace("<digits>", "\\E\\d+\\Q")

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
    test("compilation-error") - integrationTest { tester =>
      import tester._

      // Create a buffer to capture output in real-time with timestamps
      var outputWithTimestamps = Vector.empty[(Long, String)]
      val startTime = System.currentTimeMillis()

      // Break the Java source file by introducing a syntax error
      os.write.over(
        workspacePath / "src" / "foo" / "Foo.java",
        os.read(workspacePath / "src" / "foo" / "Foo.java")
          .replace(
            "public class Foo{",
            "public class Foo extends NonExistentClass {"
          ) // Invalid inheritance
      )

      // Run the build with output captured line by line with timestamps
      val res = eval(
        (
          "--ticker",
          "true",
          "--color",
          "false",
          "--jobs",
          "1",
          "compile"
        ), // Force single thread to make timing predictable
        env = Map(),
        stdin = os.Inherit,
        stdout = os.ProcessOutput.Readlines(line =>
          outputWithTimestamps =
            outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
        ),
        stderr = os.ProcessOutput.Readlines(line =>
          outputWithTimestamps =
            outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
        ),
        mergeErrIntoOut = true,
        check = false
      )
      res.isSuccess ==> false

      // Get just the lines for easier matching
      val outputLines = outputWithTimestamps.map(_._2)

      // Find the first compilation error
      val firstErrorIdx = outputLines.indexWhere(_.contains("error: cannot find symbol"))
      firstErrorIdx >= 0 ==> true

      // Find progress indicators before the error that don't show failures
      val hasProgressBeforeError = outputLines.take(firstErrorIdx).exists(l =>
        l.matches(".*\\[\\d+/\\d+\\].*") && !l.contains("failed")
      )
      hasProgressBeforeError ==> true

      // Find progress indicators after the error that show failures
      val hasFailuresAfterError = outputLines.slice(firstErrorIdx, outputLines.length).exists(l =>
        l.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*")
      )
      hasFailuresAfterError ==> true

      // Extract all progress indicators with timestamps
      val progressIndicators = outputWithTimestamps
        .filter(_._2.matches(".*\\[\\d+/\\d+.*\\].*"))
        .map { case (time, line) =>
          val total = "\\[(\\d+)/(\\d+).*\\]".r
            .findFirstMatchIn(line)
            .map(m => (m.group(1).toInt, m.group(2).toInt))
            .get
          val failures = "\\[\\d+/\\d+, (\\d+) failed\\]".r
            .findFirstMatchIn(line)
            .map(_.group(1).toInt)
          (time, total._1, total._2, failures)
        }

      // Verify scale - should see large number of total tasks
      progressIndicators.exists(_._3 >= 1000) ==> true

      // Verify failures appear early in the build
      val firstFailureTime = progressIndicators
        .find(_._4.isDefined)
        .map(_._1)
        .getOrElse(Long.MaxValue)

      val totalBuildTime = outputWithTimestamps.last._1

      // First failure should appear in first half of build time
      (firstFailureTime < totalBuildTime) ==> true

      // Verify failure count increases over time and never decreases
      val failureCounts = progressIndicators
        .flatMap { case (_, _, _, failures) => failures }
        .filter(_ > 0) // Only consider non-zero failure counts

      failureCounts.size >= 1 ==> true // At least one failure count should be present
      failureCounts.sliding(2).forall { case Seq(a, b) => b >= a } ==> true

      // Verify final state shows all expected failures
      assert(res.err.contains("dist.native.compile failed"))
      assert(res.err.contains("main.init.test.compile failed"))
      assert(res.err.contains("bsp.worker.test.compile failed"))
      assert(res.err.contains("main.compile Compilation failed"))
    }
    test("compilation-error-interactive") - integrationTest { tester =>
      import tester._

      // Create a buffer to capture output in real-time with timestamps
      var outputWithTimestamps = Vector.empty[(Long, String)]
      val startTime = System.currentTimeMillis()

      // Break the Java source file by introducing a syntax error that will trigger cascading failures
      os.write.over(
        workspacePath / "src" / "foo" / "Foo.java",
        os.read(workspacePath / "src" / "foo" / "Foo.java")
          .replace(
            "public class Foo{",
            "public class Foo extends NonExistentClass {"
          ) // Invalid inheritance
      )

      // Run the build with output captured line by line with timestamps
      val res = eval(
        (
          "--ticker",
          "true",
          "--color",
          "false",
          "--jobs",
          "1",
          "compile"
        ), // Force single thread to make timing predictable
        env = Map(),
        stdin = os.Inherit,
        stdout = os.ProcessOutput.Readlines(line =>
          outputWithTimestamps =
            outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
        ),
        stderr = os.ProcessOutput.Readlines(line =>
          outputWithTimestamps =
            outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
        ),
        mergeErrIntoOut = true,
        check = false
      )
      res.isSuccess ==> false

      // Get just the lines for easier matching
      val outputLines = outputWithTimestamps.map(_._2)

      // Verify interactive mode specific behavior:
      // 1. Progress updates on same line (contains \r)
      outputLines.exists(_.contains("\r")) ==> true

      // 2. Ticker format with live updates
      outputLines.exists(l => l.matches(".*\\[\\d+/\\d+\\].*") && l.contains("\r")) ==> true

      // Verify the progression of the build:
      // 1. Initially shows progress without failures
      val firstErrorIdx = outputLines.indexWhere(_.contains("error: cannot find symbol"))
      firstErrorIdx >= 0 ==> true
      val hasProgressBeforeError = outputLines.take(firstErrorIdx).exists(l =>
        l.matches(".*\\[\\d+/\\d+\\].*") && !l.contains("failed")
      )
      hasProgressBeforeError ==> true

      // 2. After error, shows failure counts in progress
      val hasFailuresAfterError = outputLines.slice(firstErrorIdx, outputLines.length).exists(l =>
        l.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*")
      )
      hasFailuresAfterError ==> true

      // Extract and analyze all progress indicators
      val progressIndicators = outputWithTimestamps
        .filter(_._2.matches(".*\\[\\d+/\\d+.*\\].*"))
        .map { case (time, line) =>
          val total = "\\[(\\d+)/(\\d+).*\\]".r
            .findFirstMatchIn(line)
            .map(m => (m.group(1).toInt, m.group(2).toInt))
            .get
          val failures = "\\[\\d+/\\d+, (\\d+) failed\\]".r
            .findFirstMatchIn(line)
            .map(_.group(1).toInt)
          (time, total._1, total._2, failures)
        }

      // 3. Verify we're testing at a representative scale
      // Note: We test with >1000 tasks to match real-world scale
      progressIndicators.exists(_._3 >= 1000) ==> true

      // 4. Verify failures are reported early enough to be useful
      val firstFailureTime = progressIndicators
        .find(_._4.isDefined)
        .map(_._1)
        .getOrElse(Long.MaxValue)

      val totalBuildTime = outputWithTimestamps.last._1

      // First failure should appear in first half of build time
      (firstFailureTime < totalBuildTime) ==> true

      // 5. Verify failure count increases over time and never decreases
      val failureCounts = progressIndicators
        .flatMap { case (_, _, _, failures) => failures }
        .filter(_ > 0) // Only consider non-zero failure counts

      failureCounts.size >= 1 ==> true // At least one failure count should be present
      failureCounts.sliding(2).forall { case Seq(a, b) => b >= a } ==> true

      // 6. Verify final state shows all expected failures
      assert(res.err.contains("dist.native.compile failed"))
      assert(res.err.contains("main.init.test.compile failed"))
      assert(res.err.contains("bsp.worker.test.compile failed"))
      assert(res.err.contains("main.compile Compilation failed"))
    }

    test("compilation-error-ci") - integrationTest { tester =>
      import tester._

      var outputLines = Vector.empty[String]

      // Break the Java source file
      os.write.over(
        workspacePath / "src" / "foo" / "Foo.java",
        os.read(workspacePath / "src" / "foo" / "Foo.java")
          .replace("public class Foo{", "public class Foo extends NonExistentClass {")
      )

      // Run in CI mode: no ticker, separate stdout/stderr
      val res = eval(
        ("--no-ticker", "--color", "false", "--jobs", "1", "compile"),
        env = Map(),
        stdin = os.Inherit,
        stdout = os.ProcessOutput.Readlines(line => outputLines = outputLines :+ line),
        stderr = os.ProcessOutput.Readlines(line => outputLines = outputLines :+ line),
        mergeErrIntoOut = false, // Keep stderr separate as most CI systems do
        check = false
      )
      res.isSuccess ==> false

      // Verify CI mode specific behavior:
      // 1. No progress updates on same line (no \r characters)
      outputLines.exists(_.contains("\r")) ==> false

      // 2. Each progress update on new line
      val progressLines = outputLines.filter(_.matches(".*\\[\\d+/\\d+.*\\].*"))
      progressLines.size >= 2 ==> true

      // 3. Error messages preserved in output
      val errorLines = outputLines.filter(_.contains("error:"))
      errorLines.nonEmpty ==> true

      // 4. Failure counts shown on new lines and properly formatted for CI
      val failureLines = outputLines.filter(_.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*"))
      failureLines.size >= 2 ==> true
      failureLines.forall(!_.contains("\r")) ==> true
      failureLines.forall(!_.contains("\u001b")) ==> true // No ANSI escapes

      // 5. Verify failure counts appear before final summary
      val firstFailureLineIdx =
        outputLines.indexWhere(_.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*"))
      val finalSummaryIdx = outputLines.indexWhere(_.contains("Compilation failed"))
      (firstFailureLineIdx >= 0 && firstFailureLineIdx < finalSummaryIdx) ==> true

      // 6. Final error summary preserved at end
      assert(res.err.contains("dist.native.compile failed"))
      assert(res.err.contains("main.init.test.compile failed"))
      assert(res.err.contains("bsp.worker.test.compile failed"))
      assert(res.err.contains("main.compile Compilation failed"))
    }

    test("failure count preservation") - integrationTest { tester =>
      import tester._

      var outputWithTimestamps = Vector.empty[(Long, String)]
      val startTime = System.currentTimeMillis()

      // Break the Java source file
      os.write.over(
        workspacePath / "src" / "foo" / "Foo.java",
        os.read(workspacePath / "src" / "foo" / "Foo.java")
          .replace("public class Foo{", "public class Foo extends NonExistentClass {")
      )

      // Run with ticker enabled to test header prefix preservation
      val res = eval(
        ("--ticker", "true", "--color", "false", "--jobs", "1", "compile"),
        env = Map(),
        stdin = os.Inherit,
        stdout = os.ProcessOutput.Readlines(line =>
          outputWithTimestamps =
            outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
        ),
        stderr = os.ProcessOutput.Readlines(line =>
          outputWithTimestamps =
            outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
        ),
        mergeErrIntoOut = true,
        check = false
      )
      res.isSuccess ==> false

      // Get just the lines for easier matching
      val outputLines = outputWithTimestamps.map(_._2)

      // Verify that progress indicators show failure counts and preserve the x/y format
      val progressLines = outputLines.filter(_.matches(".*\\[\\d+/\\d+(, \\d+ failed)?\\].*"))

      // Should have some progress lines
      progressLines.nonEmpty ==> true

      // Should find at least one line with failure count
      val hasFailureCount = progressLines.exists(_.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*"))
      hasFailureCount ==> true

      // Verify format is preserved - should always be [x/y] or [x/y, z failed]
      progressLines.forall(line =>
        line.matches(".*\\[\\d+/\\d+\\].*") || line.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*")
      ) ==> true

      // Verify that failure count increases but format stays consistent
      val failureCounts = progressLines
        .flatMap(line =>
          "\\[(\\d+)/(\\d+), (\\d+) failed\\]".r
            .findFirstMatchIn(line)
            .map(m => (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt))
        )

      // Should have multiple progress updates with failure counts
      failureCounts.size >= 2 ==> true

      // Progress numbers (x/y) should be preserved while failure count increases
      failureCounts.sliding(2).forall { case Seq((x1, y1, f1), (x2, y2, f2)) =>
        // x/y format should be preserved
        x1 == x2 && y1 == y2 &&
        // Failure count should never decrease
        f2 >= f1
      } ==> true
    }

    test("comprehensive-failure-display") - integrationTest { tester =>
      import tester._

      var outputWithTimestamps = Vector.empty[(Long, String)]
      val startTime = System.currentTimeMillis()

      // Create multiple failure points to verify cascading failures
      os.write.over(
        workspacePath / "src" / "foo" / "Foo.java",
        """
          |public class Foo extends NonExistentClass {
          |  public void method1() { throw new RuntimeException(); }
          |  public void method2() { NonExistentClass.call(); }
          |  public void method3() { int x = "not an int"; }
          |}
        """.stripMargin
      )

      // Run with different configurations to ensure it works in all modes
      val configs = Seq(
        Seq("--ticker", "true", "--color", "false", "--jobs", "1"), // Interactive single thread
        Seq("--ticker", "true", "--color", "false", "--jobs", "4"), // Interactive multi thread
        Seq("--no-ticker", "--color", "false", "--jobs", "1"), // CI mode single thread
        Seq("--no-ticker", "--color", "false", "--jobs", "4") // CI mode multi thread
      )

      for (config <- configs) {
        outputWithTimestamps = Vector.empty
        val res = eval(
          config ++ Seq("compile"),
          env = Map(),
          cwd = workspacePath,
          stdin = os.Inherit,
          stdout = os.ProcessOutput.Readlines(line =>
            outputWithTimestamps =
              outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
          ),
          stderr = os.ProcessOutput.Readlines(line =>
            outputWithTimestamps =
              outputWithTimestamps :+ (System.currentTimeMillis() - startTime, line)
          ),
          mergeErrIntoOut = true,
          check = false
        )
        res.isSuccess ==> false

        val outputLines = outputWithTimestamps.map(_._2)

        // 1. Verify failures appear in progress indicators
        val progressLines = outputLines.filter(_.matches(".*\\[\\d+/\\d+.*\\].*"))
        progressLines.nonEmpty ==> true

        // 2. Verify scale matches production environment
        val maxTasks = progressLines.flatMap(l =>
          "\\[(\\d+)/(\\d+).*\\]".r.findFirstMatchIn(l).map(m => m.group(2).toInt)
        ).max
        (maxTasks >= 10000) ==> true // Ensure we test at production scale

        // 3. Find first error and verify failure count appears quickly
        val firstErrorIdx = outputLines.indexWhere(_.contains("error:"))
        firstErrorIdx >= 0 ==> true

        // 4. Verify no failure counts before first error
        val preErrorProgress =
          outputLines.take(firstErrorIdx).filter(_.matches(".*\\[\\d+/\\d+.*\\].*"))
        preErrorProgress.forall(!_.contains("failed")) ==> true

        // 5. Verify failure counts appear immediately after first error
        val postFirstErrorLines = outputLines.drop(firstErrorIdx).take(10) // Check next 10 lines
        postFirstErrorLines.exists(_.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*")) ==> true

        // 6. Verify failure count format consistency
        val failureCountMatches = outputLines
          .filter(_.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*"))
          .map { line =>
            val pattern = "\\[(\\d+)/(\\d+), (\\d+) failed\\]".r
            pattern.findFirstMatchIn(line).map { m =>
              (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt)
            }.get
          }

        // 7. Verify failure counts never decrease
        failureCountMatches.sliding(2).forall { case Seq((_, _, f1), (_, _, f2)) =>
          f2 >= f1
        } ==> true

        // 8. Verify final state matches actual failures
        val finalFailureCount = failureCountMatches.last._3
        val actualFailures = outputLines.count(_.contains("failed"))
        (finalFailureCount > 0) ==> true
        (finalFailureCount == actualFailures) ==> true

        // 9. Verify timing of failure reporting
        val firstFailureTime = outputWithTimestamps
          .find(_._2.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*"))
          .map(_._1)
          .getOrElse(Long.MaxValue)

        val totalBuildTime = outputWithTimestamps.last._1

        // Must appear in first 25% of build time
        (firstFailureTime < totalBuildTime / 4) ==> true

        // 10. Verify format consistency throughout
        progressLines.forall { line =>
          line.matches(".*\\[\\d+/\\d+\\].*") || // Either normal progress
          line.matches(".*\\[\\d+/\\d+, \\d+ failed\\].*") // Or with failure count
        } ==> true

        // 11. Verify no mixed formats or malformed indicators
        progressLines.forall { line =>
          val normalFormat = ".*\\[\\d+/\\d+\\].*"
          val failureFormat = ".*\\[\\d+/\\d+, \\d+ failed\\].*"
          line.matches(normalFormat) || line.matches(failureFormat)
        } ==> true

        // 12. Verify all failure messages are preserved
        val errorMessages = outputLines.filter(_.contains("error:"))
        errorMessages.nonEmpty ==> true
        errorMessages.forall(_.contains("NonExistentClass")) ==> true
      }
    }
  }
}
