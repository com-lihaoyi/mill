package mill.contrib.junit

import mill.define.{Segment, Segments}
import mill.testrunner.TestResult
import os.Path

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneId}

case class TestData(path: Path, result: Seq[TestResult], segments: Segments)

object TestData {
  private val skippedStatus = Set("Skipped", "Ignored", "Pending")
  private val errorStatus = Set("Error", "Canceled")

  def asTestSuites(testData: TestData, executionTimestamp: Instant): Iterable[TestSuite] = {
    val timestamp = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
      LocalDateTime.ofInstant(
        executionTimestamp.truncatedTo(ChronoUnit.SECONDS),
        ZoneId.systemDefault()
      )
    )
    val maybeCrossVersions = testData.segments.value.filter(_.isInstanceOf[Segment.Cross])
    val maybeCrossVersion: Option[String] =
      if (maybeCrossVersions.isEmpty) None else Some(maybeCrossVersions.head.pathSegments.head)
    testData.result.groupBy(_.fullyQualifiedName).map { case (fqn, testResults) =>
      def durationAsString(value: Long) = (value / 1000d).toString
      val testCases = testResults.map { testResult =>
        val detail = testResult.status match {
          case "Failure" => Some(FailureDetail)
          case value if skippedStatus.contains(value) => Some(SkippedDetail)
          case value if errorStatus.contains(value) =>
            Some(ErrorDetail)
          case _ => None
        }
        TestCase(
          testResult.fullyQualifiedName,
          testResult.selector,
          durationAsString(testResult.duration),
          detail
        )
      }
      val nbErrors = testResults.count(r => errorStatus.contains(r.status))
      val nbFailures = testResults.count(r => r.status == "Failure")

      val nbSkipped = testResults.count(r => skippedStatus.contains(r.status))
      // approximation as some test are potentially run in parallel
      val totalDuration = testResults.map(_.duration).sum
      TestSuite(
        "localhost",
        maybeCrossVersion.map(v => s"$fqn-$v").getOrElse(fqn),
        testResults.size,
        nbErrors,
        nbFailures,
        nbSkipped,
        durationAsString(totalDuration),
        timestamp,
        testCases
      )
    }
  }
}
