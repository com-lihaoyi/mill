package mill.contrib.bsp

import java.io.File

import ch.epfl.scala.{bsp4j => bsp}
import sbt.internal.inc.ManagedLoggedReporter
import sbt.internal.inc.schema.Position
import sbt.internal.util.ManagedLogger
import xsbti.{Problem, Severity}

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.io.Source

class BspLoggedReporter(client: bsp.BuildClient,
                        targetId: bsp.BuildTargetIdentifier,
                        compilationOriginId: Option[String],
                        maxErrors: Int,
                        logger: ManagedLogger) extends ManagedLoggedReporter(maxErrors, logger) {

  override def logError(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    super.logError(problem)
  }

  override def logInfo(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    super.logInfo(problem)
  }

  override def logWarning(problem: Problem): Unit = {
    client.onBuildPublishDiagnostics(getDiagnostics(problem, targetId, compilationOriginId))
    super.logWarning(problem)
  }

  def getDiagnostics(problem: Problem, targetId: bsp.BuildTargetIdentifier, originId: Option[String]):
                                                                                bsp.PublishDiagnosticsParams = {
      val sourceFile = problem.position().sourceFile().asScala
      val start = new bsp.Position(
        problem.position.startLine.asScala.getOrElse(0),
        problem.position.startOffset.asScala.getOrElse(0))
      val end = new bsp.Position(
        problem.position.endLine.asScala.getOrElse(0),
        problem.position.endOffset.asScala.getOrElse(0))
      val diagnostic = new bsp.Diagnostic(new bsp.Range(start, end), problem.message)
      diagnostic.setCode(problem.position.lineContent)
      diagnostic.setSource("compiler from mill")
      diagnostic.setSeverity( problem.severity match  {
        case Severity.Info => bsp.DiagnosticSeverity.INFORMATION
        case Severity.Error => bsp.DiagnosticSeverity.ERROR
        case Severity.Warn => bsp.DiagnosticSeverity.WARNING
      }
      )

      val params = new bsp.PublishDiagnosticsParams(
          new bsp.TextDocumentIdentifier(sourceFile.getOrElse(new File(targetId.getUri)).toPath.toAbsolutePath.toUri.toString),
                                          targetId, List(diagnostic).asJava, true)

      if (originId.nonEmpty) { params.setOriginId(originId.get) }
      params
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
