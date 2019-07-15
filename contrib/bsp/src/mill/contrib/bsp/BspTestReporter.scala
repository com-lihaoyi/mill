package mill.contrib.bsp

import ch.epfl.scala.bsp4j._
import mill.api.{BspContext, TestReporter}
import sbt.testing._


class BspTestReporter(
                       client: BuildClient,
                       targetId: BuildTargetIdentifier,
                       taskId: TaskId,
                       arguments: Seq[String]) extends BspContext {

  var passed = 0
  var failed = 0
  var cancelled = 0
  var ignored = 0
  var skipped = 0
  var totalTime: Long = 0

  override def args: Seq[String] = arguments

  override def logStart(event: Event): Unit = {
    val taskStartParams = new TaskStartParams(taskId)
    taskStartParams.setEventTime(System.currentTimeMillis())
    taskStartParams.setDataKind("test-started")
    taskStartParams.setData(new TestStart(getDisplayName(event)))
    taskStartParams.setMessage("Starting running: " + getDisplayName(event))
    client.onBuildTaskStart(taskStartParams)
    println("Logged start")
  }

  override def logFinish(event: Event): Unit = {
    totalTime += event.duration()
    val taskFinishParams = new TaskFinishParams(taskId,
      event.status()  match {
        case sbt.testing.Status.Canceled => StatusCode.CANCELLED
        case sbt.testing.Status.Error => StatusCode.ERROR
        case default => StatusCode.OK
      })
    taskFinishParams.setDataKind("test-finished")
    val testFinish = new TestFinish(
      getDisplayName(event),
      event.status match {
        case sbt.testing.Status.Success =>
          passed += 1
          TestStatus.PASSED
        case sbt.testing.Status.Canceled =>
          cancelled += 1
          TestStatus.CANCELLED
        case sbt.testing.Status.Error =>
          failed += 1
          TestStatus.FAILED
        case sbt.testing.Status.Failure =>
          failed += 1
          TestStatus.FAILED
        case sbt.testing.Status.Ignored =>
          ignored += 1
          TestStatus.IGNORED
        case sbt.testing.Status.Skipped =>
          skipped += 1
          TestStatus.SKIPPED
        case sbt.testing.Status.Pending =>
          skipped += 1
          TestStatus.SKIPPED //TODO: what to do here
      })
    taskFinishParams.setData(testFinish)
    taskFinishParams.setEventTime(System.currentTimeMillis())
    taskFinishParams.setMessage("Finished running: " + getDisplayName(event))

    if (event.throwable.isDefined) {
      val exception = event.throwable.get
      taskFinishParams.setData( // send data about any potential exceptions thrown during testing
        TestException(exception.getStackTrace.toString,
          exception.getMessage,
          exception.getClass.toString))
    }
    client.onBuildTaskFinish(taskFinishParams)
    println("Logged finish")
  }

  def getDisplayName(e: Event): String = {
    e.selector() match{
      case s: NestedSuiteSelector => s.suiteId()
      case s: NestedTestSelector => s.suiteId() + "." + s.testName()
      case s: SuiteSelector => s.toString
      case s: TestSelector => s.testName()
      case s: TestWildcardSelector => s.testWildcard()
    }
  }

  def getTestReport: TestReport = {
    val report = new TestReport(targetId, passed, failed, ignored, cancelled, skipped)
    report.setTime(totalTime)
    report
  }

}

case class TestException(stackTrace: String, message: String, exClass: String)
