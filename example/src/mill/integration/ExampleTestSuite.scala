package mill.example
import mill.integration.{BashTokenizer, IntegrationTestSuite}
import utest._
import mill.util.Util

/**
 * Shared implementation for the tests in `example/`.
 *
 * Implements a bash-like test DSL for educational purposes, parsed out from a
 * `Example Usage` comment in the example's `build.sc` file. Someone should be
 * able to read the `Example Usage` comment and know roughly how to execute the
 * example themselves. Each empty-line-separated block consists of one comment
 * line (prefixed with `>`) and one or more output lines we expect to get from
 * the comman (either stdout or stderr). Output lines can be prefixed by
 * `error: ` to indicate we expect that command to fail.
 *
 * Because our CI needs to run on Windows, we cannot rely on just executing
 * commands in the `bash` shell, and instead we implement a janky little
 * interpreter that reads the command lines and does things in-JVM in response
 * to each one.
 *
 * For teaching purposes, the output lines do not show the entire output of
 * every command, which can be verbose and confusing. They instead contain
 * sub-strings of the command output, enough to convey the important points to
 * a learner. This is not as strict as asserting the entire command output, but
 * should be enough to catch most likely failure modes
 */
object ExampleTestSuite extends IntegrationTestSuite {
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("exampleUsage") {
      try {
        val parsed = upickle.default.read[Seq[(String, String)]](sys.env("MILL_EXAMPLE_PARSED"))
        val usageComment = parsed.collect{case ("example", txt) => txt}.mkString("\n\n")
        val commandBlocks = usageComment.trim.split("> ").filter(_.nonEmpty)

        "\n("
        for (commandBlock <- commandBlocks) processCommandBlock(workspaceRoot, commandBlock)
      } finally {
        os.remove.all(workspaceRoot / "out")
      }
    }
  }

  def processCommandBlock(workspaceRoot: os.Path, commandBlock: String) = {
    val commandBlockLines = commandBlock.linesIterator.toVector

    val expectedSnippets = commandBlockLines.tail.filter(_ != "...")
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
      println("ExampleTestSuite: " + commandBlockLines.head)

      processCommand(workspaceRoot, expectedSnippets, commandHead.trim)
    }
  }

  def processCommand(
      workspaceRoot: os.Path,
      expectedSnippets: Vector[String],
      commandStr: String
  ) = {
    BashTokenizer.tokenize(commandStr) match {
      case Seq(s"./$command", rest@_*) =>
        val evalResult = command match {
          case "mill" => evalStdout(rest: _*)
          case cmd =>
            val tokens = cmd +: rest
            val executable = workspaceRoot / os.RelPath(tokens.head)
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

      case Seq("node", rest@_*) =>
        val res = os
          .proc("node", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())

      case Seq("java", "-jar", rest@_*) =>
        val res = os
          .proc("java", "-jar", rest)
          .call(stdout = os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
        IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())

      case Seq("unzip", "-p", zip, path) =>
        val zipFile = new java.util.zip.ZipFile((workspaceRoot / os.SubPath(zip)).toIO)
        try {
          val boas = new java.io.ByteArrayOutputStream
          os.Internals.transfer(zipFile.getInputStream(zipFile.getEntry(path)), boas)
          validateEval(
            expectedSnippets,
            IntegrationTestSuite.EvalResult(true, boas.toString("UTF-8"), "")
          )
        }finally{zipFile.close()}

    }
  }

  def validateEval(
      expectedSnippets: Vector[String],
      evalResult: IntegrationTestSuite.EvalResult
  ): Unit = {
    if (expectedSnippets.exists(_.startsWith("error: "))) assert(!evalResult.isSuccess)
    else assert(evalResult.isSuccess)

    val unwrappedExpected = expectedSnippets.map {
      case s"error: $msg" => msg
      case msg => msg
    }

    for (expected <- unwrappedExpected) {
      println("ExampleTestSuite expected: " + expected)

      def plainText(s: String) =
        fansi.Str(s, errorMode = fansi.ErrorMode.Strip).plainText
          .replace("\\\\", "/") // Convert windows paths in JSON strings to Unix

      val filteredErr = plainText(evalResult.err)
      val filteredOut = plainText(evalResult.out)

      assert(globMatches(expected, filteredErr + "\n" + filteredOut))
    }
  }

  def globMatches(expected: String, filtered: String) =
    filtered
      .linesIterator
      .exists(
        StringContext
          .glob(s"...$expected...".split("\\.\\.\\.", -1), _)
          .nonEmpty
      )
}
