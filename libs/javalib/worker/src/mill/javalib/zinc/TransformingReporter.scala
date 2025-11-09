package mill.javalib.zinc

private trait TransformingReporter(
    color: Boolean,
    optPositionMapper: (xsbti.Position => xsbti.Position) | Null
) extends xsbti.Reporter {

  // Overriding this is necessary because for some reason the LoggedReporter doesn't transform positions
  // of Actions and DiagnosticRelatedInformation
  abstract override def log(problem0: xsbti.Problem): Unit = {
    val localMapper = optPositionMapper
    val problem = {
      if localMapper == null then problem0
      else TransformingReporter.transformProblem(color, problem0, localMapper)
    }
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
      mapper: xsbti.Position => xsbti.Position
  ): xsbti.Problem = {
    val pos0 = problem0.position()
    val related0 = problem0.diagnosticRelatedInformation()
    val actions0 = problem0.actions()
    val pos = mapper(pos0)
    val related = transformRelateds(related0, mapper)
    val actions = transformActions(actions0, mapper)
    val posIsNew = pos ne pos0
    if posIsNew || (related ne related0) || (actions ne actions0) then
      val rendered = {
        // if we transformed the position, then we must re-render the message
        if posIsNew then Some(dottyStyleMessage(color, problem0, pos))
        else InterfaceUtil.jo2o(problem0.rendered())
      }
      InterfaceUtil.problem(
        cat = problem0.category(),
        pos = pos,
        msg = problem0.message(),
        sev = problem0.severity(),
        rendered = rendered,
        diagnosticCode = InterfaceUtil.jo2o(problem0.diagnosticCode()),
        diagnosticRelatedInformation = anyToList(related),
        actions = anyToList(actions)
      )
    else
      problem0
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
      pos: xsbti.Position
  ): String = {
    val base = problem0.message()
    val severity = problem0.severity()

    def shade(msg: String) =
      if color then
        severity match {
          case xsbti.Severity.Error => Console.RED + msg + Console.RESET
          case xsbti.Severity.Warn => Console.YELLOW + msg + Console.RESET
          case xsbti.Severity.Info => Console.BLUE + msg + Console.RESET
        }
      else msg

    val normCode = {
      problem0.diagnosticCode().filter(_.code() != "-1").map({ inner =>
        val prefix = s"[E${inner.code()}] "
        inner.explanation().map(e =>
          s"$prefix$e: "
        ).orElse(prefix)
      }).orElse("")
    }

    val optPath = InterfaceUtil.jo2o(pos.sourcePath()).map { path =>
      val line0 = intValue(pos.line(), -1)
      val pointer0 = intValue(pos.pointer(), -1)
      if line0 >= 0 && pointer0 >= 0 then
        s"$path:$line0:${pointer0 + 1}"
      else
        path
    }

    val normHeader = optPath.map(path =>
      s"${shade(s"-- $normCode$path")}\n"
    ).getOrElse("")

    val optSnippet = {
      val snip = pos.lineContent()
      val space = pos.pointerSpace().orElse("")
      val pointer = intValue(pos.pointer(), -99)
      val endCol = intValue(pos.endColumn(), pointer + 1)
      if snip.nonEmpty && space.nonEmpty && pointer >= 0 && endCol >= 0 then
        val arrowCount = math.max(1, math.min(endCol - pointer, snip.length - space.length))
        Some(
          s"""$snip
             |$space${"^" * arrowCount}""".stripMargin
        )
      else
        None
    }

    val content = optSnippet.match {
      case Some(snippet) =>
        val initial = {
          s"""$snippet
             |$base
             |""".stripMargin
        }
        val snippetLine = intValue(pos.line(), -1)
        if snippetLine >= 0 then {
          // add margin with line number
          val lines = initial.linesWithSeparators.toVector
          val pre = snippetLine.toString
          val rest0 = " " * pre.length
          val rest = pre +: Vector.fill(lines.size - 1)(rest0)
          rest.lazyZip(lines).map((pre, line) => shade(s"$pre |") + line).mkString
        } else {
          initial
        }
      case None =>
        base
    }

    normHeader + content
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
