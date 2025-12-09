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
      log: String => Unit,
      ansiCodesSupported0: Boolean
  ) extends ConsoleAppender(
        name,
        ConsoleAppender.Properties.from(ConsoleOut.NullConsoleOut, ansiCodesSupported0, false),
        suppressedMessage = _ => None
      ) {
    override def appendLog(level: Level.Value, message0: => String): Unit = {
      import mill.api.BuildCtx.workspaceRoot
      val message =
        if (level != Level.Info) message0
        else {
          // Hackily scrape the `compiling` messages logged by SBT to rewrite them in a
          // less verbose format by converting absolute paths to relative to workspaceRoot
          def maybeTruncate(path0: String): String = {
            val path = os.Path(path0, workspaceRoot)

            if (!path.startsWith(workspaceRoot)) path.toString
            else path.subRelativeTo(workspaceRoot).toString
          }
          val sourcesWords = Set("source", "sources")
          message0 match {
            case s"compiling $n Scala $sources to $path ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources) =>

              s"compiling $n Scala $sources to ${maybeTruncate(path)} ..."

            case s"compiling $n Java $sources to $path ..."
                if n.forall(_.isDigit) && sourcesWords.contains(sources) =>
              s"compiling $n Java $sources to ${maybeTruncate(path)} ..."

            case s"compiling $n Scala $sources1 and $m Java $sources2 to $path ..."
              if n.forall(_.isDigit) && sourcesWords.contains(sources1) &&
                m.forall(_.isDigit) && sourcesWords.contains(sources2)=>

              s"compiling $n Scala $sources1 and $m Java $sources2 to ${maybeTruncate(path)} ..."
              
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

      log(severityPrefix + message)
    }
  }
}
