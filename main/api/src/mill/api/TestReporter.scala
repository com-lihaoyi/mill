package mill.api

import java.io.File

import sbt.testing._

/**
  * Test reporter class that can be
  * injected into the test task and
  * report information upon the start
  * and the finish of testing events
  */
trait TestReporter {
  def logStart(event: Event): Unit

  def logFinish(event: Event): Unit


}

/**
  * Dummy Test Reporter that doesn't report
  * anything for any testing event.
  */
object  DummyTestReporter extends TestReporter {
  override def logStart(event:  Event): Unit = {

  }
  override def logFinish(event:  Event): Unit = {

  }
}

trait BuildProblemReporter {
  def logError(problem: Problem): Unit

  def logWarning(problem: Problem): Unit

  def logInfo(problem: Problem): Unit

  def printSummary(): Unit
}

trait ProblemPosition {
  def line: Option[Int]

  def lineContent: String

  def offset: Option[Int]

  def pointer: Option[Int]

  def pointerSpace: Option[String]

  def sourcePath: Option[String]

  def sourceFile: Option[File]

  def startOffset: Option[Int] = Option.empty

  def endOffset: Option[Int] = Option.empty

  def startLine: Option[Int] = Option.empty

  def startColumn: Option[Int] = Option.empty

  def endLine: Option[Int] = Option.empty

  def endColumn: Option[Int] = Option.empty
}

sealed trait Severity
case object Info extends Severity
case object Error extends Severity
case object Warn extends Severity

trait Problem {
  def category: String

  def severity: Severity

  def message: String

  def position: ProblemPosition
}


