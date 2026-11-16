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

  // Appender that prints the log level only once per message, rather than once
  // per line, and skips it for INFO logging where it is usually unhelpful
  class ConciseLevelConsoleAppender(
      name: String,
      logInfo: String => Unit,
      logTicker: String => Unit,
      ansiCodesSupported0: Boolean
  ) extends ConsoleAppender(
        name,
        ConsoleAppender.Properties.from(ConsoleOut.NullConsoleOut, ansiCodesSupported0, false),
        suppressedMessage = _ => None
      ) {
    override def appendLog(level: Level.Value, message0: => String): Unit = {
      // flippables
      var logAction = logInfo
      var skipMsg = false

      val message =
        if (level != Level.Info) message0
        else {
          val sourcesWords = Set("source", "sources")
          message0 match {
            case s"compiling $n Scala $sources to $_ ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources) =>

              logAction = logTicker
              s"Compiling $n Scala $sources"

            case s"compiling $n Java $sources to $_ ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources) =>

              logAction = logTicker
              s"Compiling $n Java $sources"

            case s"compiling $n Scala $sources1 and $m Java $sources2 to $_ ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources1) &&
                  m.forall(_.isDigit) && sourcesWords.contains(sources2) =>

              logAction = logTicker
              s"Compiling $n Scala $sources1 and $m Java $sources2"

            case "done compiling" =>
              skipMsg = true

            case _ => message0
          }
        }
      val severityPrefix = level match {
        case Level.Error =>
          if (ansiCodesSupported0) "[" + fansi.Color.Red("error") + "] "
          else "[error] "
        case Level.Warn =>
          if (ansiCodesSupported0) "[" + fansi.Color.Yellow("warn") + "] "
          else "[warn] "
        case _ => ""
      }

      if (!skipMsg) logAction(severityPrefix + message)
    }
  }
}
