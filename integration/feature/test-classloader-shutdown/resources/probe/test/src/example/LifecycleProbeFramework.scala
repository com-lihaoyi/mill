package example

import sbt.testing.*

trait LifecycleProbeSuite
object LifecycleProbeTest extends LifecycleProbeSuite

final class LifecycleProbeFramework extends Framework {
  def name(): String = "lifecycle-probe"
  def fingerprints(): Array[Fingerprint] = Array(LifecycleProbeFramework.Fingerprint)

  def runner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader
  ): Runner = {
    val cwd = java.nio.file.Paths.get("").toAbsolutePath.normalize()
    System.err.println(s"LIFECYCLE_PROBE_SETUP=$cwd")
    LifecycleProbeRunner(args, remoteArgs, testClassLoader)
  }

  def slaveRunner(
      args: Array[String],
      remoteArgs: Array[String],
      testClassLoader: ClassLoader,
      send: String => Unit
  ): Runner = runner(args, remoteArgs, testClassLoader)
}

object LifecycleProbeFramework {
  object Fingerprint extends SubclassFingerprint {
    def isModule(): Boolean = true
    def requireNoArgConstructor(): Boolean = true
    def superclassName(): String = "example.LifecycleProbeSuite"
  }
}

final class LifecycleProbeRunner(
    runnerArgs: Array[String],
    runnerRemoteArgs: Array[String],
    testClassLoader: ClassLoader
) extends Runner {
  def args(): Array[String] = runnerArgs
  def remoteArgs(): Array[String] = runnerRemoteArgs

  def tasks(taskDefs: Array[TaskDef]): Array[Task] = {
    if (testClassLoader.getClass.getName == "mill.api.daemon.MillURLClassLoader") {
      val thread = new Thread(
        () => {
          val deadline = System.nanoTime() + 10_000_000_000L
          while (
            testClassLoader.getResource("lifecycle-probe.marker") != null &&
            System.nanoTime() < deadline
          ) Thread.onSpinWait()

          try {
            val module = testClassLoader
              .loadClass("example.LateLoaded$")
              .getField("MODULE$")
              .get(null)
            val value = module.getClass.getMethod("value").invoke(module)
            System.err.println(s"LATE_CLASS_LOAD_SUCCEEDED=$value")
          } catch {
            case error: Throwable =>
              System.err.println("TEST_CLASSLOADER_CLOSED_EARLY")
              error.printStackTrace()
          }
        },
        "lifecycle-probe-thread"
      )
      thread.setDaemon(false)
      thread.start()
    }
    taskDefs.map(LifecycleProbeTask(_))
  }

  def done(): String = ""
}

final class LifecycleProbeTask(taskDefinition: TaskDef) extends Task {
  def taskDef(): TaskDef = taskDefinition
  def tags(): Array[String] = Array.empty

  def execute(eventHandler: EventHandler, loggers: Array[Logger]): Array[Task] = {
    eventHandler.handle(new Event {
      def fullyQualifiedName(): String = taskDefinition.fullyQualifiedName()
      def fingerprint(): Fingerprint = taskDefinition.fingerprint()
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
  ): Unit = continuation(execute(eventHandler, loggers))
}
