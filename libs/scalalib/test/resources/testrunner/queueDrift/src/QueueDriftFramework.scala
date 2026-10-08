package mill.scalalib

import sbt.testing._

import java.nio.file.{Files, Paths}

trait QueueDriftBase
class QueueDriftStart extends QueueDriftBase
class QueueDriftFiller extends QueueDriftBase
class QueueDriftExtra extends QueueDriftBase

class QueueDriftFramework extends Framework {
  override def fingerprints(): Array[Fingerprint] = Array(new SubclassFingerprint {
    override def isModule(): Boolean = false
    override def superclassName(): String = "mill.scalalib.QueueDriftBase"
    override def requireNoArgConstructor(): Boolean = true
  })
  override def name(): String = "QueueDriftFramework"
  override def runner(
      runnerArgs: Array[String],
      runnerRemoteArgs: Array[String],
      testClassLoader: ClassLoader
  ): Runner = new Runner {
    override def args(): Array[String] = runnerArgs
    override def remoteArgs(): Array[String] = runnerRemoteArgs
    override def done(): String = {
      QueueDriftFramework.queueExtraClass()
      ""
    }
    override def tasks(taskDefs: Array[TaskDef]): Array[Task] = {
      taskDefs.map(QueueDriftTask(_))
    }
  }
}

object QueueDriftFramework {
  def queueExtraClass(): Unit = {
    val queueFolder = Paths.get("").toAbsolutePath.getParent.resolve("test-classes")
    Files.createDirectories(queueFolder)
    Files.write(queueFolder.resolve("mill.scalalib.QueueDriftExtra"), Array.emptyByteArray)
    ()
  }
}

case class QueueDriftTask(taskDef0: TaskDef) extends Task {
  override def taskDef(): TaskDef = taskDef0
  override def tags(): Array[String] = Array.empty
  override def execute(
      eventHandler: EventHandler,
      loggers: Array[Logger]
  ): Array[Task] = {
    eventHandler.handle(new Event {
      override def fullyQualifiedName(): String = taskDef0.fullyQualifiedName()
      override def fingerprint(): Fingerprint = taskDef0.fingerprint()
      override def selector(): Selector = new SuiteSelector()
      override def status(): Status = Status.Success
      override def throwable(): OptionalThrowable = new OptionalThrowable()
      override def duration(): Long = 0L
    })
    Array.empty
  }
}
