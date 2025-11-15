package sbt.mill

import mill.api.daemon.internal.internal
import sbt.internal.util.{Appender, ConsoleAppender, ConsoleOut, ManagedLogger}
import sbt.util.{Level, LoggerContext}

// We put this in the sbt package so it can access `private[sbt]` methods
@internal
object SbtLoggerUtils {
  def createLogger(name: String, appender: Appender, level: Level.Value): ManagedLogger = {
    val context = LoggerContext.globalContext
    val logger = context.logger(name = name, channelName = None, execId = None)
    context.clearAppenders(name)
    context.addAppender(name, (appender, level))

    logger
  }

  /** Creates a ConsoleAppender that skips the [error]/[warning]/[info] label prefixes */
  def createNoLabelAppender(
      name: String,
      consoleOut: ConsoleOut,
      ansiCodesSupported: Boolean
  ): Appender = {
    new ConsoleAppender(
      name,
      ConsoleAppender.Properties.from(consoleOut, ansiCodesSupported, false),
      suppressedMessage = _ => None
    ) {
      override def appendLog(level: Level.Value, message: => String): Unit = {
        // Override to skip adding the [level] prefix
        // Write message directly without the label prefix
        message.linesIterator.foreach(consoleOut.println)
      }
    }
  }
}
