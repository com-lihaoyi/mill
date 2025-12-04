package mill.bsp.worker

import ch.epfl.scala.bsp4j._
import ch.epfl.scala.{bsp4j => bsp}
import mill.api.daemon.internal.{CompileProblemReporter, Problem}

import scala.collection.mutable
import scala.util.chaining.scalaUtilChainingOps

/**
 * Specialized reporter that sends compilation diagnostics
 * for each problem it logs, either as information, warning or
 * error as well as task finish notifications of type `compile-report`.
 *
 * @param client              the client to send diagnostics to
 * @param targetId            the target id of the target whose compilation
 *                            the diagnostics are related to
 * @param taskId              a unique id of the compilation task of the target
 *                            specified by `targetId`
 * @param compilationOriginId optional origin id the client assigned to
 *                            the compilation request. Needs to be sent
 *                            back as part of the published diagnostics
 *                            as well as compile report
 */
private class BspCompileProblemReporter(
    client: bsp.BuildClient,
    targetId: BuildTargetIdentifier,
    targetDisplayName: String,
    taskId: TaskId,
    compilationOriginId: Option[String]
) extends CompileProblemReporter {
  private var errors = 0
  private var warnings = 0

  // no need of a limit here, there's no console not to flood in BSP mode
  override def maxErrors: Int = Int.MaxValue

  def hasErrors: Boolean = errors > 0

  object diagnostics {
    private class Details(
        val list: java.util.List[Diagnostic],
        val set: mutable.HashSet[Diagnostic],
        var hasNewDiagnostics: Boolean
    ) {
      def add(diagnostic: Diagnostic): Boolean =
        set.add(diagnostic) && {
          list.add(diagnostic)
          hasNewDiagnostics = true
          true
        }
    }
    private val map = mutable.Map.empty[TextDocumentIdentifier, Details]
    private def details(textDocument: TextDocumentIdentifier): Details =
      // setting hasNewDiagnostics to true when starting, so that diagnostics
      // are sent at least once, even when there are none
      map.getOrElseUpdate(
        textDocument,
        Details(new java.util.ArrayList, new mutable.HashSet, true)
      )
    def add(textDocument: TextDocumentIdentifier, diagnostic: Diagnostic): Boolean =
      details(textDocument).add(diagnostic)

    def getAll(textDocument: TextDocumentIdentifier): (java.util.List[Diagnostic], Boolean) = {
      val details0 = details(textDocument)
      val hasNewDiagnostics = details0.hasNewDiagnostics
      details0.hasNewDiagnostics = false
      (details0.list, hasNewDiagnostics)
    }
  }

  override def logError(problem: Problem): Unit = {
    reportProblem(problem)
    errors += 1
  }

  override def logInfo(problem: Problem): Unit = {
    reportProblem(problem)
  }

  // TODO: document that if the problem is a general information without a text document
  // associated to it, then the document field of the diagnostic is set to the uri of the target
  private def reportProblem(problem: Problem): Unit = {
    val sourceFile = problem.position.sourceFile
    sourceFile match {
      case None =>
        // It seems, this isn't an actionable compile problem,
        // instead of sending a `build/publishDiagnostics` we send a `build/logMessage`.
        // see https://github.com/com-lihaoyi/mill/issues/2926
        val messagesType = problem.severity match {
          case mill.api.daemon.internal.Error => MessageType.ERROR
          case mill.api.daemon.internal.Warn => MessageType.WARNING
          case mill.api.daemon.internal.Info => MessageType.INFO
        }
        val msgParam = LogMessageParams(messagesType, problem.message).tap { it =>
          it.setTask(taskId)
        }
        client.onBuildLogMessage(msgParam)

      case Some(f) =>
        val diagnostic = toDiagnostic(problem)
        val textDocument = TextDocumentIdentifier(
          // The extra step invoking `toPath` results in a nicer URI starting with `file:///`
          f.toPath.toUri.toString
        )
        if (diagnostics.add(textDocument, diagnostic)) {
          val diagnosticList = new java.util.LinkedList[Diagnostic]()
          diagnosticList.add(diagnostic)
          sendBuildPublishDiagnostics(textDocument, diagnosticList, reset = false)
        }
    }
  }

  // Computes the diagnostic related to the given Problem
  private def toDiagnostic(problem: Problem): Diagnostic = {
    // Zinc's range starts at 1 whereas BSP at 0
    def correctLine = (_: Int) - 1

    val pos = problem.position
    val line = pos.line.map(correctLine)
    val start = new bsp.Position(
      pos.startLine.map(correctLine).orElse(line).getOrElse[Int](0),
      pos.startColumn.orElse(pos.pointer).getOrElse[Int](0)
    )
    val end = new bsp.Position(
      pos.endLine.map(correctLine).orElse(line).getOrElse[Int](start.getLine.intValue()),
      pos.endColumn.orElse(pos.pointer).getOrElse[Int](start.getCharacter.intValue())
    )
    new bsp.Diagnostic(new bsp.Range(start, end), problem.message).tap { d =>
      // TODO: review whether this is a proper source or if it should better
      // something like "scala compiler" or "foo.bar.compile"
      d.setSource("mill")
      d.setSeverity(
        problem.severity match {
          case mill.api.daemon.internal.Info => bsp.DiagnosticSeverity.INFORMATION
          case mill.api.daemon.internal.Error => bsp.DiagnosticSeverity.ERROR
          case mill.api.daemon.internal.Warn => bsp.DiagnosticSeverity.WARNING
        }
      )
      problem.diagnosticCode.foreach { existingCode =>
        d.setCode(existingCode.code)
      }
    }
  }

  private def sendBuildPublishDiagnostics(
      textDocument: TextDocumentIdentifier,
      diagnosticList: java.util.List[Diagnostic],
      reset: Boolean
  ): Unit = {
    val params = new bsp.PublishDiagnosticsParams(
      textDocument,
      targetId,
      diagnosticList,
      reset
    )
    compilationOriginId.foreach(params.setOriginId(_))
    client.onBuildPublishDiagnostics(params)
  }

  override def logWarning(problem: Problem): Unit = {
    reportProblem(problem)
    warnings += 1
  }

  override def fileVisited(file: java.nio.file.Path): Unit = {
    val uri = file.toUri.toString
    val textDocument = TextDocumentIdentifier(uri)
    val (diagnostics0, hasNewDiagnostics) = diagnostics.getAll(textDocument)
    if (hasNewDiagnostics)
      sendBuildPublishDiagnostics(textDocument, diagnostics0, reset = true)
  }

  override def printSummary(): Unit = {
    finish()
  }

  override def start(): Unit = {
    val taskStartParams = TaskStartParams(taskId).tap { it =>
      it.setEventTime(System.currentTimeMillis())
      it.setData(CompileTask(targetId))
      it.setDataKind(TaskStartDataKind.COMPILE_TASK)
      it.setMessage(s"Compiling target ${targetDisplayName}")
    }
    client.onBuildTaskStart(taskStartParams)
  }

  override def notifyProgress(progress: Long, total: Long): Unit = {
    val params = TaskProgressParams(taskId).tap { it =>
      it.setEventTime(System.currentTimeMillis())
      it.setData(CompileTask(targetId))
      it.setDataKind("compile-progress")
      it.setMessage(s"Compiling target ${targetDisplayName} (${progress * 100 / total}%)")
      // Not a percentage, but the # of units done,
      // see https://github.com/build-server-protocol/build-server-protocol/blob/bc6835d240b0810bcebe1738e7b71caa49b24f29/spec/src/main/resources/META-INF/smithy/bsp/bsp.smithy#L1150
      it.setProgress(progress)
      it.setTotal(total)
    }
    client.onBuildTaskProgress(params)
  }

  override def finish(): Unit = {
    val taskFinishParams =
      TaskFinishParams(taskId, if (errors > 0) StatusCode.ERROR else StatusCode.OK).tap { it =>
        it.setEventTime(System.currentTimeMillis())
        it.setMessage(s"Compiled ${targetDisplayName}")
        it.setDataKind(TaskFinishDataKind.COMPILE_REPORT)
        val compileReport = CompileReport(targetId, errors, warnings).tap { it =>
          compilationOriginId.foreach(id => it.setOriginId(id))
        }
        it.setData(compileReport)
      }
    client.onBuildTaskFinish(taskFinishParams)
  }

}
