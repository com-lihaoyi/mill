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
        tester.modifyFile(os.Path(file, workspaceRoot), _.replace(oldStr, newStr))

      case Seq("curl", url) =>
        Thread.sleep(1500) // Need to give backgroundWrapper time to spin up
        val res = requests.get(url)
        validateEval(
          expectedSnippets,
          IntegrationTester.EvalResult(res.is2xx, res.text(), "")
        )

      case Seq("cat", path) =>
        val res = os.read(os.Path(path, workspaceRoot))
        validateEval(
          expectedSnippets,
          IntegrationTester.EvalResult(true, res, "")
        )

      case Seq("node", rest @ _*) =>
        val res = os
          .proc("node", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        validateEval(
          expectedSnippets,
          IntegrationTester.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        )

      case Seq("git", rest @ _*) =>
        val res = os
          .proc("git", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        validateEval(
          expectedSnippets,
          IntegrationTester.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        )

      case Seq("java", "-jar", rest @ _*) =>
        val res = os
          .proc("java", "-jar", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        validateEval(
          expectedSnippets,
          IntegrationTester.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        )

      case Seq("unzip", "-p", zip, path) =>
        val zipFile = new java.util.zip.ZipFile((workspaceRoot / os.SubPath(zip)).toIO)
        try {
          val boas = new java.io.ByteArrayOutputStream
          os.Internals.transfer(zipFile.getInputStream(zipFile.getEntry(path)), boas)
          validateEval(
            expectedSnippets,
            IntegrationTester.EvalResult(true, boas.toString("UTF-8"), "")
          )
        } finally {
          zipFile.close()
        }

      case Seq("printf", literal, ">>", path) =>
        tester.modifyFile(
          os.Path(path, tester.workspacePath),
          _ + ujson.read(s""""${literal}"""").str
        )

      case Seq(command, rest @ _*) =>
        val evalResult = command match {
          case "./mill" | "mill" => tester.eval(rest)
          case s"./$cmd" =>
            val tokens = cmd +: rest
            val executable = workspaceRoot / os.SubPath(tokens.head)
            if (!os.exists(executable)) {
              throw new Exception(
                s"Executable $executable not found.\n" +
                  s"Other files present include ${os.list(executable / os.up)}"
              )
            }
            val res = os.call(
              cmd = (executable, tokens.tail),
              stdout = os.Pipe,
              stderr = os.Pipe,
              cwd = workspaceRoot
            )

            IntegrationTester.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
        }

        validateEval(expectedSnippets, evalResult)
    }
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
