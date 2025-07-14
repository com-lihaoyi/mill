package sbt.mill

import mill.api.daemon.internal.internal
import sbt.internal.util.{Appender, ManagedLogger}
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
}
