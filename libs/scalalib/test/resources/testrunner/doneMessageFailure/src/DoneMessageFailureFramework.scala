package mill.scalalib

import sbt.testing._

class DoneMessageFailureFramework extends Framework {
  def fingerprints() = Array.empty
  def name() = "DoneMessageFailureFramework"
  def runner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader
  ): Runner = new Runner {
    def args() = Array.empty
    def done() = "test failure done message"
    def remoteArgs() = Array.empty
    def tasks(taskDefs: Array[TaskDef]) = Array(new Task {
      def taskDef(): TaskDef = taskDefs.headOption.getOrElse(null)
      def execute(
          eventHandler: EventHandler,
          loggers: Array[Logger]
      ): Array[Task] = {
        eventHandler.handle(new Event {
          override def fullyQualifiedName(): String = "foo.bar"
          override def fingerprint(): Fingerprint = new Fingerprint {}
          override def selector(): Selector = TestSelector("foo.bar")
          override def status(): Status = Status.Failure
          override def throwable(): OptionalThrowable = OptionalThrowable()
          override def duration(): Long = 0L
        })
        Array.empty
      }
      def tags = Array.empty
    })
  }
}
