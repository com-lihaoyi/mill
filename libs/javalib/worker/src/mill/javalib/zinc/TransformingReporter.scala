package mill.javalib.zinc

private trait TransformingReporter(color: Boolean, workspaceRoot: os.Path) extends xsbti.Reporter {

  // Overriding this is necessary because for some reason the LoggedReporter doesn't transform positions
  // of Actions and DiagnosticRelatedInformation
  abstract override def log(problem0: xsbti.Problem): Unit = {
    super.log(TransformingReporter.transformProblem(color, problem0, workspaceRoot))
  }
}

private object TransformingReporter {

  import sbt.util.InterfaceUtil

  import scala.jdk.CollectionConverters.given

  /** implements a transformation that returns the same object if the mapper has no effect. */
  private def transformProblem(
      color: Boolean,
      problem0: xsbti.Problem,
      workspaceRoot: os.Path
  ): xsbti.Problem = {
    val pos = problem0.position()
    val rendered = dottyStyleMessage(color, problem0, pos, workspaceRoot)
    InterfaceUtil.problem(
      cat = problem0.category(),
      pos = pos,
      msg = problem0.message(),
      sev = problem0.severity(),
      rendered = Some(rendered),
      diagnosticCode = InterfaceUtil.jo2o(problem0.diagnosticCode()),
      diagnosticRelatedInformation = anyToList(problem0.diagnosticRelatedInformation()),
      actions = anyToList(problem0.actions())
    )
  }

  private type JOrSList[T] = java.util.List[T] | List[T]

  private def anyToList[T](ts: JOrSList[T]): List[T] = ts match {
    case ts: List[T] => ts
    case ts: java.util.List[T] => ts.asScala.toList
  }

  /** Render the message in the style of dotty */
  private def dottyStyleMessage(
      color: Boolean,
      problem0: xsbti.Problem,
      pos: xsbti.Position,
      workspaceRoot: os.Path
  ): String = {

    val severity = problem0.severity()

    val shade: java.util.function.Function[String, String] =
      if color then
        severity match {
          case xsbti.Severity.Error => msg => Console.RED + msg + Console.RESET
          case xsbti.Severity.Warn => msg => Console.YELLOW + msg + Console.RESET
          case xsbti.Severity.Info => msg => Console.BLUE + msg + Console.RESET
        }
      else msg => msg

    val message = problem0.message()

    InterfaceUtil.jo2o(pos.sourcePath()) match {
      case None => message
      case Some(path) =>
        val absPath = os.Path(path)
        // Render paths within the current workspaceRoot as relative paths to cut down on verbosity
        val displayPath =
          if absPath.startsWith(workspaceRoot) then absPath.subRelativeTo(workspaceRoot).toString
          else path

        val line = intValue(pos.line(), -1)
        val pointer0 = intValue(pos.pointer(), -1)
        val colNum = pointer0 + 1

        val space = pos.pointerSpace().orElse("")
        val endCol = intValue(pos.endColumn(), pointer0 + 1)

        // Dotty only renders the colored code snippet as part of `.rendered`, but it's mixed
        // in with the rest of the UI we don't really want. So we need to scrape it out ourselves
        val renderedLines = InterfaceUtil.jo2o(problem0.rendered())
          .iterator
          .flatMap(_.linesIterator)
          .toSeq

        // Just grab the first line from the dotty error code snippet, because dotty defaults to
        // rendering entire expressions which can be arbitrarily large and spammy in the terminal
        val lineContent = mill.api.internal.Util.scrapeColoredLineContent(
          renderedLines,
          pos.lineContent()
        ) match {
          case "" =>
            // Some errors like Java `unclosed string literal` errors don't provide any
            // message at all to `rendered` for us to scrape the line content, so instead
            // try to scrape it ourselves from the filesystem
            try os.read.lines(absPath).apply(line - 1)
            catch { case _: Exception => "" }
          case s => s
        }

        val pointerLength =
          if (space.nonEmpty && pointer0 >= 0 && endCol >= 0)
            math.max(1, math.min(endCol - pointer0, lineContent.length - space.length))
          else 1

        mill.constants.Util.formatError(
          displayPath,
          line,
          colNum,
          lineContent,
          message,
          pointerLength,
          shade
        )
    }
  }

}
