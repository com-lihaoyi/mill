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
      consoleOut: ConsoleOut,
      ansiCodesSupported0: Boolean
  ) extends ConsoleAppender(
        name,
        ConsoleAppender.Properties.from(consoleOut, ansiCodesSupported0, false),
        suppressedMessage = _ => None
      ) {
    override def appendLog(level: Level.Value, message0: => String): Unit = {
      import mill.api.BuildCtx.workspaceRoot
      val message =
        if (level != Level.Info) message0
        else {
          def maybeTruncate(n: String, lang: String, sources: String, path0: String): String = {
            val path = os.Path(path0, workspaceRoot)

            if (!path.startsWith(workspaceRoot)) path.toString
            else {
              val truncated = path.subRelativeTo(workspaceRoot).toString
              s"compiling $n $lang $sources to $truncated ..."
            }
          }
          val sourcesWords = Set("source", "sources")
          message0 match {
            case s"compiling $n Scala $sources to $path ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources) =>

              maybeTruncate(n, "Scala", sources, path)

            case s"compiling $n Java $sources to $path ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources) =>
              maybeTruncate(n, "java", sources, path)

            case _ => message0
          }
        }
      val severityPrefix = level match {
        case Level.Error => "[" + fansi.Color.Red("error") + "] "
        case Level.Warn => "[" + fansi.Color.Yellow("warn") + " ] "
        case _ => ""
      }

      consoleOut.println(severityPrefix + message)
    }
  }
}
