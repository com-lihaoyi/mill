package mill.runner.api
import java.io.File

/**
 * A listener trait for getting notified about
 * compilation output like compiler warnings and errors
 */
trait CompileProblemReporter {
  def start(): Unit
  def logError(problem: Problem): Unit
  def logWarning(problem: Problem): Unit
  def logInfo(problem: Problem): Unit
  def fileVisited(file: java.nio.file.Path): Unit
  def printSummary(): Unit
  def finish(): Unit
  def notifyProgress(percentage: Long, total: Long): Unit
}

/**
 * Contains general information about the build problem
 */
trait Problem {
  def category: String

  def severity: Severity

  def message: String

  def position: ProblemPosition

  def diagnosticCode: Option[DiagnosticCode]
}

/**
 * Unique diagnostic code given from the compiler with an optional further explanation.
 */
trait DiagnosticCode {
  def code: String

  def explanation: Option[String]
}

/**
 * Indicates the exact location (source file, line, column) of the build problem
 */
trait ProblemPosition {
  def line: Option[Int]

  def lineContent: String

  def offset: Option[Int]

  def pointer: Option[Int]

  def pointerSpace: Option[String]

  def sourcePath: Option[String]

  def sourceFile: Option[File]

  def startOffset: Option[Int]

  def endOffset: Option[Int]

  def startLine: Option[Int]

  def startColumn: Option[Int]

  def endLine: Option[Int]

  def endColumn: Option[Int]
}

sealed trait Severity
case object Info extends Severity
case object Error extends Severity
case object Warn extends Severity
