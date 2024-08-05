package mill.example
import mill.integration.{BashTokenizer, IntegrationTestSuite}
import mill.util.Util
import utest._

import java.util.concurrent.{Executors, TimeoutException}
import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt

/**
 * Shared implementation for the tests in `example/`.
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
 * 3. `...` can be used to indicate wildcards, which match anything. These can
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
object ExampleTestSuite extends IntegrationTestSuite {
  val tests: Tests = Tests {
    val workspaceRoot = initWorkspace()

    val testTimeout = 5.minutes

    // Integration tests sometime hang on CI
    // The idea is to just abort and retry them after a reasonable amount of time
    @tailrec def retryOnTimeout[T](n: Int)(body: => T): T = {

      // We use Java Future here, as it supports cancellation
      val executor = Executors.newFixedThreadPool(1)
      val fut = executor.submit { () => body }

      try fut.get(testTimeout.length, testTimeout.unit)
      catch {
        case e: TimeoutException =>
          fut.cancel(true)
          if (n > 0) {
            Console.err.println(s"Timeout occurred (${testTimeout}). Retrying...")
            retryOnTimeout(n - 1)(body)
          } else throw e
      }

    }

    test("exampleUsage") {

      val parsed = upickle.default.read[Seq[(String, String)]](sys.env("MILL_EXAMPLE_PARSED"))
      val usageComment = parsed.collect { case ("example", txt) => txt }.mkString("\n\n")
      val commandBlocks = ("\n" + usageComment.trim).split("\n> ").filter(_.nonEmpty)

      retryOnTimeout(3) {
        try os.remove.all(workspaceRoot / "out")
        catch { case e: Throwable => /*do nothing*/ }

        for (commandBlock <- commandBlocks) processCommandBlock(workspaceRoot, commandBlock)
        if (integrationTestMode != "fork") evalStdout("shutdown")
      }
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
        (comment.exists(_.startsWith("--no-server")) && integrationTestMode != "fork") ||
        (comment.exists(_.startsWith("not --no-server")) && integrationTestMode == "fork")

    if (!incorrectPlatform) {
      processCommand(workspaceRoot, expectedSnippets, commandHead.trim)
    }
  }

  def processCommand(
      workspaceRoot: os.Path,
      expectedSnippets: Vector[String],
      commandStr: String
  ): Unit = {
    val cmd = BashTokenizer.tokenize(commandStr)
    Console.err.println(
      s"""$workspaceRoot> ${cmd.mkString("'", "' '", "'")}
         |--- Expected output --------
         |${expectedSnippets.mkString("\n")}
         |----------------------------""".stripMargin
    )
    cmd match {
      case Seq("cp", "-r", from, to) =>
        os.copy(os.Path(from, workspaceRoot), os.Path(to, workspaceRoot))

      case Seq("sed", "-i", s"s/$oldStr/$newStr/g", file) =>
        mangleFile(os.Path(file, workspaceRoot), _.replace(oldStr, newStr))

      case Seq("curl", url) =>
        Thread.sleep(1500) // Need to give backgroundWrapper time to spin up
        val res = requests.get(url)
        validateEval(
          expectedSnippets,
          IntegrationTestSuite.EvalResult(res.is2xx, res.text(), "")
        )

      case Seq("cat", path) =>
        val res = os.read(os.Path(path, workspaceRoot))
        validateEval(
          expectedSnippets,
          IntegrationTestSuite.EvalResult(true, res, "")
        )

      case Seq("node", rest @ _*) =>
        val res = os
          .proc("node", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        validateEval(
          expectedSnippets,
          IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        )

      case Seq("git", rest @ _*) =>
        val res = os
          .proc("git", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        validateEval(
          expectedSnippets,
          IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        )

      case Seq("java", "-jar", rest @ _*) =>
        val res = os
          .proc("java", "-jar", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        validateEval(
          expectedSnippets,
          IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        )

      case Seq("unzip", "-p", zip, path) =>
        val zipFile = new java.util.zip.ZipFile((workspaceRoot / os.SubPath(zip)).toIO)
        try {
          val boas = new java.io.ByteArrayOutputStream
          os.Internals.transfer(zipFile.getInputStream(zipFile.getEntry(path)), boas)
          validateEval(
            expectedSnippets,
            IntegrationTestSuite.EvalResult(true, boas.toString("UTF-8"), "")
          )
        } finally {
          zipFile.close()
        }

      case Seq("printf", literal, ">>", path) =>
        mangleFile(os.Path(path, workspacePath), _ + ujson.read(s""""${literal}"""").str)

      case Seq(command, rest @ _*) =>
        val evalResult = command match {
          case "./mill" | "mill" => evalStdout(rest)
          case s"./$cmd" =>
            val tokens = cmd +: rest
            val executable = workspaceRoot / os.SubPath(tokens.head)
            if (!os.exists(executable)) {
              throw new Exception(
                s"Executable $executable not found.\n" +
                  s"Other files present include ${os.list(executable / os.up)}"
              )
            }
            val res = os
              .proc(executable, tokens.tail)
              .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)

            IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        }

        validateEval(expectedSnippets, evalResult)
    }
  }

  def validateEval(
      expectedSnippets: Vector[String],
      evalResult: IntegrationTestSuite.EvalResult
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
      fansi.Str(s, errorMode = fansi.ErrorMode.Strip).plainText
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

    val filteredErr = plainTextLines(evalResult.err)
    val filteredOut = plainTextLines(evalResult.out)

    if (expectedSnippets.nonEmpty) {
      for (outLine <- filteredOut) {
        globMatchesAny(unwrappedExpected, outLine)
      }
      for (errLine <- filteredErr) {
        globMatchesAny(unwrappedExpected, errLine)
      }
    }

    for (expectedLine <- unwrappedExpected.linesIterator) {
      val combinedOutErr = (filteredOut ++ filteredErr).mkString("\n")
      assert(globMatches(expectedLine, combinedOutErr))
    }
  }

  def globMatches(expected: String, filtered: String): Boolean = {
    filtered
      .linesIterator
      .exists(
        StringContext
          .glob(expected.split("\\.\\.\\.", -1).toIndexedSeq, _)
          .nonEmpty
      )
  }

  def globMatchesAny(expected: String, filtered: String): Boolean = {
    expected.linesIterator.exists(globMatches(_, filtered))
  }
}
