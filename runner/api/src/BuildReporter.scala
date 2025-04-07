package mill.runner.api

import sbt.testing.Event

/**
 * Test reporter class that can be
 * injected into the test task and
 * report information upon the start
 * and the finish of testing events
 */
trait TestReporter {
  def logStart(event: Event): Unit

  def logFinish(event: Event): Unit

  def logLevel: TestReporter.LogLevel =
    TestReporter.LogLevel.Info
}

object TestReporter {
  enum LogLevel(val level: Int, val asString: String) extends Product with Serializable
      with Comparable[LogLevel]:
    case Error extends LogLevel(4, "error")
    case Warn extends LogLevel(3, "warn")
    case Info extends LogLevel(2, "info")
    case Debug extends LogLevel(1, "debug")

    def compareTo(other: LogLevel): Int =
      level.compareTo(other.level)

  object LogLevel {
    def fromString(input: String): LogLevel =
      input match {
        case "error" => Error
        case "warn" => Warn
        case "info" => Info
        case "debug" => Debug
        case _ => sys.error(s"Unrecognized log level value: '$input'")
      }
  }

  def apply(logLevel: LogLevel): TestReporter =
    new DefaultImpl(logLevel)

  private final class DefaultImpl(override val logLevel: LogLevel) extends TestReporter {
    override def logStart(event: Event): Unit = {}
    override def logFinish(event: Event): Unit = {}
  }
}

/**
 * Dummy Test Reporter that doesn't report
 * anything for any testing event.
 */
object DummyTestReporter extends TestReporter {
  override def logStart(event: Event): Unit = {}
  override def logFinish(event: Event): Unit = {}
}
