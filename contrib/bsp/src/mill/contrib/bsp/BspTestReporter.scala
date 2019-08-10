package mill.contrib.bsp

import ch.epfl.scala.bsp4j._
import mill.api.BspContext
import sbt.testing._


/**
  * Context class for BSP, specialized for sending `task-start` and
  * `task-finish` notifications for every test being ran.
  * @param client The client to send notifications to
  * @param targetId The targetId of the BSP target for which
  *                 the test request is being processed
  * @param taskId   The unique taskId associated with the
  *                 test task that will trigger this reporter
  *                 to log testing events.
  * @param arguments compilation arguments as part of the BSP context,
  *                  in case special arguments need to be passed to
  *                  the compiler before running the test task.
  */
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
    taskStartParams.setDataKind(TaskDataKind.TEST_START)
    taskStartParams.setData(new TestStart(getDisplayName(event)))
    taskStartParams.setMessage("Starting running: " + getDisplayName(event))
    client.onBuildTaskStart(taskStartParams)
  }

  override def logFinish(event: Event): Unit = {
    totalTime += event.duration()
    val taskFinishParams = new TaskFinishParams(taskId,
      event.status()  match {
        case sbt.testing.Status.Canceled => StatusCode.CANCELLED
        case sbt.testing.Status.Error => StatusCode.ERROR
        case default => StatusCode.OK
      })
    taskFinishParams.setDataKind(TaskDataKind.TEST_FINISH)
    val status = event.status match {
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
    }
    val testFinish = new TestFinish(getDisplayName(event), status)
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
  }

  // Compute the display name of the test / test suite
  // to which the given event relates
  private[this] def getDisplayName(e: Event): String = {
    e.selector() match{
      case s: NestedSuiteSelector => s.suiteId()
      case s: NestedTestSelector => s.suiteId() + "." + s.testName()
      case s: SuiteSelector => s.toString
      case s: TestSelector => s.testName()
      case s: TestWildcardSelector => s.testWildcard()
    }
  }

  // Compute the test report data structure that will go into
  // the task finish notification after all tests are ran.
  def getTestReport: TestReport = {
    val report = new TestReport(targetId, passed, failed, ignored, cancelled, skipped)
    report.setTime(totalTime)
    report
  }

}

case class TestException(stackTrace: String, message: String, exClass: String)
