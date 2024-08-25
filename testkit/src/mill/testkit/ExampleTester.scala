package mill.testkit
import mill.util.Util
import utest._

import java.util.concurrent.{Executors, TimeoutException}
import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

/**
 * A variant of [[IntegrationTester]], [[ExampleTester]] works the same way
 * except the commands used to test the project come from a `/** Usage ... */`
 * comment inside the project's `build.sc` file. This is intended to make the
 * `build.sc` file usable as documentation, such that a reader can skim the `build.sc`
 * and see both the build configuration as well as the commands they themselves can
 * enter at the command line to exercise it.
 *
 * Implements a bash-like test DSL for educational purposes, parsed out from a
 * `Example Usage` comment in the example's `build.sc` file. Someone should be
 * able to read the `Example Usage` comment and know roughly how to execute the
 * example themselves.
 *
 * Each empty-line-separated block consists of one command (prefixed with `>`)
 * and zero or more output lines we expect to get from the comman (either stdout
 * or stderr):
 *
 * 1. If there are no expected output lines, we do not perform any assertions
 *    on the output of the command
 *
 * 2. Output lines can be prefixed by `error: ` to indicate we expect that
 *    command to fail.
 *
 * 3. `..` can be used to indicate wildcards, which match anything. These can
 *    be used alone as the entire line, or in the middle of another line
 *
 * 4. Every line of stdout/stderr output by the command must match at least
 *    one line of the expected output, and every line of expected output must
 *    match at least one line of stdout/stderr. We ignore ordering of output
 *    lines.
 *
 * For teaching purposes, the output lines do not show the entire output of
 * every command, which can be verbose and confusing. They instead contain
 * sub-strings of the command output, enough to convey the important points to
 * a learner. This is not as strict as asserting the entire command output, but
 * should be enough to catch most likely failure modes
 *
 * Because our CI needs to run on Windows, we cannot rely on just executing
 * commands in the `bash` shell, and instead we implement a janky little
 * interpreter that reads the command lines and does things in-JVM in response
 * to each one.
 */
object ExampleTester {
  def run(clientServerMode: Boolean, workspaceSourcePath: os.Path, millExecutable: os.Path): Unit =
    new ExampleTester(
      new IntegrationTester(
        clientServerMode,
        workspaceSourcePath,
        millExecutable
      )
    ).run()
}

class ExampleTester(tester: IntegrationTester.Impl) {
  tester.initWorkspace()

  os.copy.over(tester.millExecutable, tester.workspacePath / "mill")

  val testTimeout: FiniteDuration = 5.minutes

  // Integration tests sometime hang on CI
  // The idea is to just abort and retry them after a reasonable amount of time
  @tailrec final def retryOnTimeout[T](n: Int)(body: => T): T = {

    // We use Java Future here, as it supports cancellation
    val executor = Executors.newFixedThreadPool(1)
    val fut = executor.submit { () => body }

    try fut.get(testTimeout.length, testTimeout.unit)
    catch {
      case e: TimeoutException =>
        fut.cancel(true)
        if (n > 0) {
          Console.err.println(s"Timeout occurred (${testTimeout}). Retrying..")
          retryOnTimeout(n - 1)(body)
        } else throw e
    }

  }

  def processCommandBlock(workspaceRoot: os.Path, commandBlock: String): Unit = {
    val commandBlockLines = commandBlock.linesIterator.toVector

    val expectedSnippets = commandBlockLines.tail
    val (commandHead, comment) = commandBlockLines.head match {
      case s"$before#$after" => (before.trim, Some(after.trim))
      case string => (string, None)
    }

    val incorrectPlatform =
      (comment.exists(_.startsWith("windows")) && !Util.windowsPlatform) ||
        (comment.exists(_.startsWith("mac/linux")) && Util.windowsPlatform) ||
        (comment.exists(_.startsWith("--no-server")) && tester.clientServerMode) ||
        (comment.exists(_.startsWith("not --no-server")) && !tester.clientServerMode)

    if (!incorrectPlatform) {
      processCommand(workspaceRoot, expectedSnippets, commandHead.trim)
    }
  }

  def processCommand(
      workspaceRoot: os.Path,
      expectedSnippets: Vector[String],
      commandStr0: String
  ): Unit = {
    val commandStr = commandStr0 match{
      case s"mill $rest" => s"./mill $rest"
      case s => s
    }
    Console.err.println(
      s"""$workspaceRoot> $commandStr}
         |--- Expected output --------
         |${expectedSnippets.mkString("\n")}
         |----------------------------""".stripMargin
    )

    val res = os.call(
      ("bash", "-c", commandStr),
      stdout = os.Pipe,
      stderr = os.Pipe,
      cwd = workspaceRoot,
      mergeErrIntoOut = true,
      env = Map("MILL_TEST_SUITE" -> this.getClass().toString()),
      check = false
    )

    validateEval(
      expectedSnippets,
      IntegrationTester.EvalResult(
        res.exitCode == 0,
        fansi.Str(res.out.text(), errorMode = fansi.ErrorMode.Strip).plainText,
        fansi.Str(res.err.text(), errorMode = fansi.ErrorMode.Strip).plainText
      )
    )
  }

  def validateEval(
      expectedSnippets: Vector[String],
      evalResult: IntegrationTester.EvalResult
  ): Unit = {
    if (expectedSnippets.exists(_.startsWith("error: "))) assert(!evalResult.isSuccess)
    else assert(evalResult.isSuccess)

    val unwrappedExpected = expectedSnippets
      .map {
        case s"error: $msg" => msg
        case msg => msg
      }
      .mkString("\n")

    def plainTextLines(s: String) =
      s
        .replace("\\\\", "/") // Convert windows paths in JSON strings to Unix
        .linesIterator
        // Don't bother checking empty lines
        .filter(_.trim.nonEmpty)
        // Strip log4j noisy prefixes that differ on windows and mac/linux
        .map(ln =>
          ln.stripPrefix("[info] ").stripPrefix("info: ")
            .stripPrefix("[error] ").stripPrefix("error: ")
        )
        .toVector

    val filteredOut = plainTextLines(evalResult.out)

    if (expectedSnippets.nonEmpty) {
      for (outLine <- filteredOut) {
        globMatchesAny(unwrappedExpected, outLine)
      }
    }

    for (expectedLine <- unwrappedExpected.linesIterator) {
      assert(filteredOut.exists(globMatches(expectedLine, _)))
    }
  }

  def globMatches(expected: String, line: String): Boolean = {
    StringContext
      .glob(expected.split("\\.\\.\\.", -1).toIndexedSeq, line)
      .nonEmpty
  }

  def globMatchesAny(expected: String, filtered: String): Boolean = {
    expected.linesIterator.exists(globMatches(_, filtered))
  }

  def run(): Any = {
    val parsed = ExampleParser(tester.workspaceSourcePath)
    val usageComment = parsed.collect { case ("example", txt) => txt }.mkString("\n\n")
    val commandBlocks = ("\n" + usageComment.trim).split("\n> ").filter(_.nonEmpty)

    retryOnTimeout(3) {
      try os.remove.all(tester.workspacePath / "out")
      catch { case e: Throwable => /*do nothing*/ }

      for (commandBlock <- commandBlocks) processCommandBlock(tester.workspacePath, commandBlock)
      if (tester.clientServerMode) tester.eval("shutdown")
    }
  }
}
