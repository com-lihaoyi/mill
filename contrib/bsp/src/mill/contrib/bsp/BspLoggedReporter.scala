package mill.contrib.bsp

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}

import ch.epfl.scala.bsp4j.{BuildServer, BuildTargetIdentifier, CompileReport, Diagnostic, InverseSourcesParams, ScalaBuildServer, StatusCode, TaskFinishParams, TaskId, TextDocumentIdentifier}
import ch.epfl.scala.{bsp4j => bsp}
import mill.api.BspContext
import org.eclipse.lsp4j.PublishDiagnosticsParams
import sbt.internal.inc.ManagedLoggedReporter
import sbt.internal.inc.schema.Position
import sbt.internal.util.ManagedLogger
import xsbti.{Problem, Severity}

import scala.collection.JavaConverters._
import scala.collection.concurrent
import scala.compat.java8.OptionConverters._
import scala.io.Source

class BspLoggedReporter(client: bsp.BuildClient,
                        targetId: BuildTargetIdentifier,
                        taskId: TaskId,
                        compilationOriginId: Option[String],
                        maxErrors: Int,
                        logger: ManagedLogger) extends ManagedLoggedReporter(maxErrors, logger) {

  var errors = new AtomicInteger(0)
  var warnings = new AtomicInteger(0)
  var infos = new AtomicInteger(0)
  var diagnosticMap: concurrent.Map[TextDocumentIdentifier, bsp.PublishDiagnosticsParams] =
    new ConcurrentHashMap[TextDocumentIdentifier, bsp.PublishDiagnosticsParams]().asScala

  override def logError(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    errors.incrementAndGet()
    super.logError(problem)
  }

  override def logInfo(problem: Problem): Unit = {
   client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    infos.incrementAndGet()
    super.logInfo(problem)
  }

  override def logWarning(problem: Problem): Unit = {
   client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    warnings.incrementAndGet()
    super.logWarning(problem)
  }

  override def printSummary(): Unit = {
    val taskFinishParams = new TaskFinishParams(taskId, getStatusCode)
    taskFinishParams.setEventTime(System.currentTimeMillis())
    taskFinishParams.setMessage("Finished compiling target: " + targetId.getUri)
    taskFinishParams.setDataKind("compile-report")
    val compileReport = new CompileReport(targetId, errors.get, warnings.get)
    compilationOriginId match {
      case Some(id) => compileReport.setOriginId(id)
      case None =>
    }
    taskFinishParams.setData(compileReport)
    client.onBuildTaskFinish(taskFinishParams)
  }

  //TODO: document that if the problem is a general information without a text document
  // associated to it, then the document field of the diagnostic is set to the uri of the target
  def getDiagnostics(problem: Problem, targetId: bsp.BuildTargetIdentifier, originId: Option[String]):
                                                                                bsp.PublishDiagnosticsParams = {
      val diagnostic = getSingleDiagnostic(problem)
      val sourceFile = problem.position().sourceFile().asScala
      val textDocument = new TextDocumentIdentifier(
        sourceFile.getOrElse(None) match {
        case None => targetId.getUri
        case f: File => f.toPath.toUri.toString
      })
      val params = new bsp.PublishDiagnosticsParams(textDocument,
                                                    targetId,
                                                    appendDiagnostics(textDocument, diagnostic).asJava
                                          , true)

      if (originId.nonEmpty) { params.setOriginId(originId.get) }
      diagnosticMap.put(textDocument, params)
      params
    }

  private[this] def getStatusCode: StatusCode = {
    if (errors.get > 0) StatusCode.ERROR else StatusCode.OK
  }

  private[this] def appendDiagnostics(textDocument: TextDocumentIdentifier,
                                      currentDiagnostic: Diagnostic): List[Diagnostic] = {
    diagnosticMap.putIfAbsent(textDocument, new bsp.PublishDiagnosticsParams(
          textDocument,
          targetId,
          List.empty[Diagnostic].asJava, true))
    diagnosticMap(textDocument).getDiagnostics.asScala.toList ++ List(currentDiagnostic)
  }

  private[this] def getSingleDiagnostic(problem: Problem): Diagnostic ={

    val start = new bsp.Position(
      problem.position.startLine.asScala.getOrElse(problem.position.line.asScala.getOrElse(0)),
      problem.position.startOffset.asScala.getOrElse(problem.position.offset.asScala.getOrElse(0)))
    val end = new bsp.Position(
      problem.position.endLine.asScala.getOrElse(problem.position.line.asScala.getOrElse(0)),
      problem.position.endOffset.asScala.getOrElse(problem.position.offset.asScala.getOrElse(0)))
    val diagnostic = new bsp.Diagnostic(new bsp.Range(start, end), problem.message)
    diagnostic.setCode(problem.position.lineContent)
    diagnostic.setSource("compiler from mill")
    diagnostic.setSeverity( problem.severity match  {
      case Severity.Info => bsp.DiagnosticSeverity.INFORMATION
      case Severity.Error => bsp.DiagnosticSeverity.ERROR
      case Severity.Warn => bsp.DiagnosticSeverity.WARNING
    }
    )
    diagnostic
  }

  private[this] def getErrorCode(file: Option[File], start: bsp.Position, end: bsp.Position, position: xsbti.Position): String = {
    file match {
      case None => position.lineContent
      case f: Option[File] =>
        val source = Source.fromFile(f.get)
        source.close()
        val lines = source.getLines.toSeq
        val code = lines(start.getLine).substring(start.getCharacter) +
          lines.take(start.getLine - 1).takeRight(lines.length - end.getLine - 1).mkString("\n") +
          lines(end.getLine).substring(0, end.getCharacter + 1)
        code
    }

  }

}
