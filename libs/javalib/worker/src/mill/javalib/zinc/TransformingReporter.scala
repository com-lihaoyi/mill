package mill.javalib.zinc

private trait TransformingReporter(
    color: Boolean,
    optPositionMapper: (xsbti.Position => xsbti.Position) | Null,
    workspaceRoot: os.Path
) extends xsbti.Reporter {

  // Overriding this is necessary because for some reason the LoggedReporter doesn't transform positions
  // of Actions and DiagnosticRelatedInformation
  abstract override def log(problem0: xsbti.Problem): Unit = {
    val localMapper = optPositionMapper
    // Always transform to apply path relativization, even if there's no position mapper for build files
    val mapper = if localMapper == null then (pos: xsbti.Position) => pos else localMapper
    val problem = TransformingReporter.transformProblem(color, problem0, mapper, workspaceRoot)
    super.log(problem)
  }
}

private object TransformingReporter {

  import sbt.util.InterfaceUtil

  import scala.jdk.CollectionConverters.given

  /** implements a transformation that returns the same object if the mapper has no effect. */
  private def transformProblem(
      color: Boolean,
      problem0: xsbti.Problem,
      mapper: xsbti.Position => xsbti.Position,
      workspaceRoot: os.Path
  ): xsbti.Problem = {
    val pos0 = problem0.position()
    val related0 = problem0.diagnosticRelatedInformation()
    val actions0 = problem0.actions()
    val pos = mapper(pos0)
    val related = transformRelateds(related0, mapper)
    val actions = transformActions(actions0, mapper)
    val rendered = dottyStyleMessage(color, problem0, pos, workspaceRoot)
    InterfaceUtil.problem(
      cat = problem0.category(),
      pos = pos,
      msg = problem0.message(),
      sev = problem0.severity(),
      rendered = Some(rendered),
      diagnosticCode = InterfaceUtil.jo2o(problem0.diagnosticCode()),
      diagnosticRelatedInformation = anyToList(related),
      actions = anyToList(actions)
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

    def shade(msg: String) =
      if color then
        severity match {
          case xsbti.Severity.Error => Console.RED + msg + Console.RESET
          case xsbti.Severity.Warn => Console.YELLOW + msg + Console.RESET
          case xsbti.Severity.Info => Console.BLUE + msg + Console.RESET
        }
      else msg

    val message = problem0.message()

    InterfaceUtil.jo2o(pos.sourcePath()) match {
      case None => message
      case Some(path) =>
        val absPath = os.Path(path)
        // Render paths within the current workspaceRoot as relative paths to cut down on verbosity
        val displayPath =
          if absPath.startsWith(workspaceRoot) then absPath.subRelativeTo(workspaceRoot).toString
          else path

        val line0 = intValue(pos.line(), -1)
        val pointer0 = intValue(pos.pointer(), -1)

        val space = pos.pointerSpace().orElse("")
        val endCol = intValue(pos.endColumn(), pointer0 + 1)

        // Dotty only renders the colored code snippet as part of `.rendered`, but it's mixed
        // in with the rest of the UI we don't really want. So we need to scrape it out ourselves
        val renderedLines = InterfaceUtil.jo2o(problem0.rendered())
          .iterator
          .flatMap(_.linesIterator)
          .toList

        // Just grab the first line from the dotty error code snippet, because dotty defaults to
        // rendering entire expressions which can be arbitrarily large and spammy in the terminal
        val lineContent = renderedLines
          .collectFirst {
            case s"$pre |$rest" if pre.nonEmpty && fansi.Str(pre).plainText.forall(_.isDigit) =>
              rest
          }
          .getOrElse(pos.lineContent()) // fall back to plaintext line if no colored line found

        val pointerLength =
          if (space.nonEmpty && pointer0 >= 0 && endCol >= 0)
            math.max(1, math.min(endCol - pointer0, lineContent.length - space.length))
          else 1

        mill.api.internal.Util.formatError(
          displayPath,
          line0,
          pointer0 + 1,
          lineContent,
          message,
          pointerLength,
          shade
        )
    }
  }

  /** Implements a transformation that returns the same list if the mapper has no effect */
  private def transformActions(
      actions0: java.util.List[xsbti.Action],
      mapper: xsbti.Position => xsbti.Position
  ): JOrSList[xsbti.Action] = {
    if actions0.iterator().asScala.exists(a =>
        a.edit().changes().iterator().asScala.exists(e =>
          mapper(e.position()) ne e.position()
        )
      )
    then {
      actions0.iterator().asScala.map(transformAction(_, mapper)).toList
    } else {
      actions0
    }
  }

  /** Implements a transformation that returns the same list if the mapper has no effect */
  private def transformRelateds(
      related0: java.util.List[xsbti.DiagnosticRelatedInformation],
      mapper: xsbti.Position => xsbti.Position
  ): JOrSList[xsbti.DiagnosticRelatedInformation] = {

    if related0.iterator().asScala.exists(r => mapper(r.position()) ne r.position()) then
      related0.iterator().asScala.map(transformRelated(_, mapper)).toList
    else
      related0
  }

  private def transformRelated(
      related0: xsbti.DiagnosticRelatedInformation,
      mapper: xsbti.Position => xsbti.Position
  ): xsbti.DiagnosticRelatedInformation = {
    InterfaceUtil.diagnosticRelatedInformation(mapper(related0.position()), related0.message())
  }

  private def transformAction(
      action0: xsbti.Action,
      mapper: xsbti.Position => xsbti.Position
  ): xsbti.Action = {
    InterfaceUtil.action(
      title = action0.title(),
      description = InterfaceUtil.jo2o(action0.description()),
      edit = transformEdit(action0.edit(), mapper)
    )
  }

  private def transformEdit(
      edit0: xsbti.WorkspaceEdit,
      mapper: xsbti.Position => xsbti.Position
  ): xsbti.WorkspaceEdit = {
    InterfaceUtil.workspaceEdit(
      edit0.changes().iterator().asScala.map(transformTEdit(_, mapper)).toList
    )
  }

  private def transformTEdit(
      edit0: xsbti.TextEdit,
      mapper: xsbti.Position => xsbti.Position
  ): xsbti.TextEdit = {
    InterfaceUtil.textEdit(
      position = mapper(edit0.position()),
      newText = edit0.newText()
    )
  }
}
