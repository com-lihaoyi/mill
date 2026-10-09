package com.example

import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Fingerprint
import sbt.testing.Framework
import sbt.testing.Logger
import sbt.testing.OptionalThrowable
import sbt.testing.Runner
import sbt.testing.Selector
import sbt.testing.Status
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef

trait BatchSuite
trait BatchResource

object SharedResource extends BatchResource
object BatchSuiteTest extends BatchSuite

class BatchFramework extends Framework {
  def name(): String = "batch-framework"

  def fingerprints(): Array[Fingerprint] = Array(
    BatchFramework.ResourceFingerprint,
    BatchFramework.SuiteFingerprint
  )

  def runner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader
  ): Runner = new BatchRunner(args, remoteArgs)

  def slaveRunner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader,
      send: String => Unit
  ): Runner = new BatchRunner(args, remoteArgs)
}

object BatchFramework {
  object SuiteFingerprint extends SubclassFingerprint {
    def isModule(): Boolean = true
    def requireNoArgConstructor(): Boolean = true
    def superclassName(): String = "com.example.BatchSuite"
  }

  object ResourceFingerprint extends SubclassFingerprint {
    def isModule(): Boolean = true
    def requireNoArgConstructor(): Boolean = true
    def superclassName(): String = "com.example.BatchResource"
  }
}

final class BatchRunner(
    runnerArgs: Array[String],
    runnerRemoteArgs: Array[String]
) extends Runner {

  def args(): Array[String] = runnerArgs
  def remoteArgs(): Array[String] = runnerRemoteArgs

  def tasks(taskDefs: Array[TaskDef]): Array[Task] = {
    val names = taskDefs.map(_.fullyQualifiedName()).toSet
    if (
      names.contains("com.example.SharedResource") &&
      sys.env.get("MY_ENV") != Some("MY_ENV_VALUE")
    ) {
      throw new RuntimeException("Missing MY_ENV while materializing tasks")
    }

    if (
      names.contains("com.example.BatchSuiteTest") &&
      !names.contains("com.example.SharedResource")
    ) {
      throw new RuntimeException("BatchSuiteTest was split from SharedResource")
    }

    taskDefs
      .filter(_.fullyQualifiedName() == "com.example.BatchSuiteTest")
      .map(new BatchTask(_))
  }

  def done(): String = ""
}

final class BatchTask(td: TaskDef) extends Task {
  def taskDef(): TaskDef = td
  def tags(): Array[String] = Array.empty

  def execute(
      eventHandler: EventHandler,
      loggers: Array[Logger]
  ): Array[Task] = {
    loggers.foreach(_.info("batch task ran with MY_ENV_VALUE"))
    eventHandler.handle(new Event {
      def fullyQualifiedName(): String = td.fullyQualifiedName()
      def fingerprint(): Fingerprint = td.fingerprint()
      def selector(): Selector = new SuiteSelector
      def status(): Status = Status.Success
      def throwable(): OptionalThrowable = new OptionalThrowable()
      def duration(): Long = 0L
    })
    Array.empty
  }

  def execute(
      eventHandler: EventHandler,
      loggers: Array[Logger],
      continuation: Array[Task] => Unit
  ): Unit = {
    continuation(execute(eventHandler, loggers))
  }
}
