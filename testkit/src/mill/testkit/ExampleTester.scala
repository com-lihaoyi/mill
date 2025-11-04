package mill.testkit

import mill.constants.Util.isWindows
import utest.*

/**
 * A variant of [[IntegrationTester]], [[ExampleTester]] works the same way
 * except the commands used to test the project come from a `/** Usage ... */`
 * comment inside the project's `build.mill` file. This is intended to make the
 * `build.mill` file usable as documentation, such that a reader can skim the `build.mill`
 * and see both the build configuration and the commands they themselves can
 * enter at the command line to exercise it.
 *
 * Implements a bash-like test DSL for educational purposes, parsed out from an
 * `Example Usage` comment in the example's `build.mill` file. Someone should be
 * able to read the `Example Usage` comment and know roughly how to execute the
 * example themselves.
 *
 * Each empty-line-separated block consists of one command (prefixed with `>`)
 * and zero or more output lines we expect to get from the command (either stdout
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
 *
 * You can suffix command with a comment to restrict its applicability, e.g. `> echo "Windows" # windows`.
 * The following comments are recognized (see [[incorrectPlatform]]):
 * - 'mac/linux'        - Only run on Linux/Mac but not on Windows
 * - 'windows'          - Only run on Windows but not on Linux/Mac
 * - '--no-daemon'      - Only run in no-daemon test mode
 * - 'not --no-daemon'  - Only run in daemon test mode
 */
object ExampleTester {
  def run(
      daemonMode: Boolean,
      workspaceSourcePath: os.Path,
      millExecutable: os.Path,
      bashExecutable: String = defaultBashExecutable(),
      workspacePath: os.Path = os.pwd
  ): os.Path = {
    val tester = new ExampleTester(
      daemonMode,
      workspaceSourcePath,
      millExecutable,
      bashExecutable,
      workspacePath
    )
    tester.run()
    tester.workspacePath
  }

  def defaultBashExecutable(): String = {
    if (!mill.constants.Util.isWindows) "bash"
    else "C:\\Program Files\\Git\\usr\\bin\\bash.exe"
  }
}

class ExampleTester(
    val daemonMode: Boolean,
    val workspaceSourcePath: os.Path,
    millExecutable: os.Path,
    bashExecutable: String = ExampleTester.defaultBashExecutable(),
    val baseWorkspacePath: os.Path,
    val propagateJavaHome: Boolean = true,
    val cleanupProcessIdFile: Boolean = true
) extends IntegrationTesterBase {

  def incorrectPlatform(commandComment: String): Boolean = {
    (commandComment.exists(_.startsWith("windows")) && !isWindows) ||
      (commandComment.exists(_.startsWith("mac/linux")) && isWindows) ||
      (commandComment.exists(_.startsWith("--no-daemon")) && daemonMode) ||
      (commandComment.exists(_.startsWith("not --no-daemon")) && !daemonMode)
  }

  def processCommandBlock(commandBlock: String): Unit = {
    val commandBlockLines = commandBlock.linesIterator.toVector

    val expectedSnippets = commandBlockLines.tail
    val (commandHead, comment) = commandBlockLines.head match {
      case s"$before#$after" => (before.trim, Some(after.trim))
      case string => (string, None)
    }

    if (!incorrectPlatform(comment)) {
      processCommand(expectedSnippets, commandHead.trim)
    }
  }
  private val millExt = if (isWindows) ".bat" else ""
  private val daemonFlag = if (daemonMode) "" else "--no-daemon"

  def processCommand(
      expectedSnippets: Vector[String],
      commandStr0: String,
      check: Boolean = true
  ): Unit = {
    val commandStr = commandStr0 match {
      case s"mill $rest" => s"./mill$millExt $daemonFlag --ticker false $rest"
      case s"./mill $rest" => s"./mill$millExt $daemonFlag --ticker false $rest"
      case s"curl $rest" => s"curl --retry 7 --retry-all-errors $rest"
      case s => s
    }

    /** The command we're about to execute */
    val debugCommandStr = s"$workspacePath> $commandStr"
    Console.err.println("\nRunning:")
    Console.err.println(debugCommandStr)
    Console.err.println(
      s"""--- Expected output ----------
         |${expectedSnippets.mkString("\n")}
         |------------------------------""".stripMargin
    )

    val windowsPathEnv =
      if (!isWindows) Map()
      else Map(
        "BASH_ENV" -> os.temp("export PATH=\"/c/Program Files/Git/usr/bin:$PATH\"").toString()
      )

    try {
      val res = os.call(
        (bashExecutable, "-c", commandStr),
        stdout = os.Pipe,
        stderr = os.Inherit,
        cwd = workspacePath,
        mergeErrIntoOut = true,
        env = millTestSuiteEnv ++ windowsPathEnv,
        check = false
      )

      validateEval(
        expectedSnippets,
        IntegrationTester.EvalResult(
          res.exitCode,
          fansi.Str(res.out.text(), errorMode = fansi.ErrorMode.Strip).plainText,
          fansi.Str(res.err.text(), errorMode = fansi.ErrorMode.Strip).plainText
        ),
        check,
        debugCommandStr
      )

    } catch {
      case NonFatal(e) =>
        Console.err.println("Failure:")
        Console.err.println(debugCommandStr + "\n")
        throw e
    }

    Console.err.println("Success:")
    Console.err.println(debugCommandStr + "\n")
  }

  def validateEval(
      expectedSnippets: Vector[String],
      evalResult: IntegrationTester.EvalResult,
      check: Boolean = true,
      command: String = ""
  ): Unit = {
    if (check) {
      if (expectedSnippets.exists(_.startsWith("error: "))) assert(!evalResult.isSuccess)
      else assert(evalResult.isSuccess)
    }

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

    val filteredOut = plainTextLines(evalResult.out).mkString("\n")

    for (expectedLine <- unwrappedExpected.linesIterator) {
      Predef.assert(
        filteredOut.linesIterator.exists(globMatches(expectedLine, _)),
        (if (command == "") "" else s"==== command:\n$command\n") +
          s"""==== filteredOut:
             |$filteredOut
             |==== Missing expectedLine:
             |$expectedLine""".stripMargin
      )
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

  def run(): Unit = {
    os.makeDir.all(workspacePath)
    val parsed = ExampleParser(workspaceSourcePath)
    val ignoreErrors = System.getenv("CI") != null &&
      os.exists(workspaceSourcePath / "ignoreErrorsOnCI")
    val usageComment = parsed.collect { case ("example", txt) => txt }.mkString("\n\n")
    val commandBlocks = ("\n" + usageComment.trim).split("\n> ").filter(_.nonEmpty)

    try {
      initWorkspace()
      os.copy.over(millExecutable, workspacePath / s"mill$millExt")
      try for (commandBlock <- commandBlocks) processCommandBlock(commandBlock)
      catch {
        case ex: Throwable if ignoreErrors =>
          System.err.println(
            s"Warning: ignoring exception while running example test under $workspaceSourcePath"
          )
          ex.printStackTrace(System.err)
      }
    } finally {
      if (daemonMode) {
        if (!sys.env.contains("MILL_TEST_SHARED_OUTPUT_DIR")) {
          processCommand(Vector(), "./mill shutdown", check = false)
        } else {
          processCommand(Vector(), "./mill clean", check = false)
        }
      }

      removeProcessIdFile()
    }
  }
}
