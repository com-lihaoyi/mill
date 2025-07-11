package mill.androidlib

import mill.api.Logger
import mill.javalib.testrunner.TestResult

import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.IteratorHasAsScala

private[androidlib] sealed trait InstrumentationOutput

private[androidlib] object InstrumentationOutput {

  case class TestClassName(className: String) extends InstrumentationOutput

  case class TestMethodName(methodName: String) extends InstrumentationOutput

  case object StatusStarted extends InstrumentationOutput

  case object StatusOk extends InstrumentationOutput

  case object StatusFailure extends InstrumentationOutput

  case object StatusError extends InstrumentationOutput

  case class Ignored(line: String) extends InstrumentationOutput

  private case class TimeResultState(
      started: Long,
      currentTestResult: TestResult,
      testResults: Seq[TestResult]
  )
  private val testResultStarted = TestResult("", "", 0L, "")

  /* Inspiration from:
    https://android.googlesource.com/platform/development/+/52d4c30ca52320ec92d1d1ddc8db3f07f69c4f98/tools/ddms/libs/ddmlib/src/com/android/ddmlib/testrunner/InstrumentationResultParser.java
   */

  def parseTestOutputStream(outputReader: BufferedReader)(logger: Logger)
      : (String, Seq[TestResult]) = {
    val state = outputReader.lines().iterator().asScala.foldLeft(TimeResultState(
      0L,
      testResultStarted,
      Seq.empty
    )) {
      case (state, nextLine) =>
        logger.debug(nextLine)
        parseLine(nextLine) match {
          case InstrumentationOutput.TestClassName(className) =>
            logger.debug(s"TestClassName=$className")
            TimeResultState(
              state.started,
              state.currentTestResult.copy(fullyQualifiedName = className),
              state.testResults
            )
          case InstrumentationOutput.TestMethodName(methodName) =>
            logger.debug(s"TestMethodName=$methodName")
            val fullyQualifiedNAme = s"${state.currentTestResult.fullyQualifiedName}.${methodName}"
            state.copy(currentTestResult =
              state.currentTestResult.copy(
                fullyQualifiedName = fullyQualifiedNAme,
                selector = fullyQualifiedNAme
              )
            )
          case InstrumentationOutput.StatusStarted =>
            logger.debug(s"StatusStarted")
            state.copy(started = System.currentTimeMillis())
          case InstrumentationOutput.StatusOk =>
            logger.debug(s"StatusOk")
            val ended = System.currentTimeMillis()
            val duration = FiniteDuration.apply(ended - state.started, TimeUnit.MILLISECONDS)
            val testResult =
              state.currentTestResult.copy(duration = duration.toSeconds, status = "Success")
            TimeResultState(state.started, testResultStarted, state.testResults :+ testResult)
          case InstrumentationOutput.StatusFailure =>
            logger.debug(s"StatusFailure")
            val ended = System.currentTimeMillis()
            val duration = FiniteDuration.apply(ended - state.started, TimeUnit.MILLISECONDS)
            val testResult =
              state.currentTestResult.copy(duration = duration.toSeconds, status = "Failure")
            TimeResultState(state.started, testResultStarted, state.testResults :+ testResult)
          case InstrumentationOutput.StatusError =>
            logger.debug(s"StatusError")
            val ended = System.currentTimeMillis()
            val duration = FiniteDuration.apply(ended - state.started, TimeUnit.MILLISECONDS)
            val testResult =
              state.currentTestResult.copy(duration = duration.toSeconds, status = "Error")
            TimeResultState(state.started, testResultStarted, state.testResults :+ testResult)
          case InstrumentationOutput.Ignored(line) =>
            // todo handle stream and stack
            logger.debug(s"Message ${line}")
            state
        }
    }
    ("", state.testResults)
  }

  def parseLine(line: String): InstrumentationOutput = {
    if (line.contains("class=")) {
      val parts = line.split("class=")
      TestClassName(parts(1))
    } else if (line.contains("test=")) {
      val parts = line.split("test=")
      TestMethodName(parts(1))
    } else if (line.contains("INSTRUMENTATION_STATUS_CODE:")) {
      val parts = line.split(" ")
      parts(1).trim() match {
        case "1" => StatusStarted
        case "0" => StatusOk
        case "-1" => StatusError
        case "-2" => StatusFailure
        case _ => Ignored(line)
      }
    } else {
      Ignored(line)
    }
  }
}
