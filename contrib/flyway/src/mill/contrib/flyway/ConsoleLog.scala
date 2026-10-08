package mill.contrib.flyway

import org.flywaydb.core.api.logging.Log
import org.flywaydb.core.api.logging.LogCreator

/**
 * Adapted from https://github.com/flyway/flyway/blob/25f562283e45d717bf56e44fb9988b4adc98a0ef/flyway-commandline/src/main/java/org/flywaydb/commandline/ConsoleLog.java
 */

/**
 * Wrapper around a simple Console output.
 */
object ConsoleLog {

  object Level extends Enumeration {
    type Level = Value
    val DEBUG, INFO, WARN = Value
  }
}

/**
 * Creates a new Console Log.
 *
 * @param level the log level.
 */
class ConsoleLog(val level: ConsoleLog.Level.Level) extends Log {
  override def isDebugEnabled: Boolean = level eq ConsoleLog.Level.DEBUG

  override def debug(message: String): Unit = {
    if (isDebugEnabled) System.out.println("DEBUG: " + message)
  }

  override def info(message: String): Unit = {
    if (level.compareTo(ConsoleLog.Level.INFO) <= 0) System.out.println(message)
  }

  override def warn(message: String): Unit = {
    System.out.println("WARNING: " + message)
  }

  override def notice(message: String): Unit = {
    System.err.println("NOTICE: " + message)
  }

  override def error(message: String): Unit = {
    System.err.println("ERROR: " + message)
  }

  override def error(message: String, e: Exception): Unit = {
    System.err.println("ERROR: " + message)
    e.printStackTrace(System.err)
  }
}

/**
 * Log Creator for the Command-Line console.
 */
class ConsoleLogCreator(val level: ConsoleLog.Level.Level) extends LogCreator {
  override def createLogger(clazz: Class[?]): Log = ConsoleLog(level)
}
