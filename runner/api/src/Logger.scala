package mill.runner.api

import java.io.{InputStream, OutputStream, PrintStream}

/**
 * The standard logging interface of the Mill build tool.
 * Also contains the two forwarded stdout and stderr streams, for code executed
 * by Mill to use directly. Typically, these correspond to the stdout and stderr,
 * but when `show` is used both are forwarded to stderr and stdout is only
 * used to display the final `show` output for easy piping.
 */
trait Logger {

  /**
   * This Logger's versions of stdin, stdout, and stderr. Typically enabled
   * thread-locally while the logger is being used via `SystemStreams.withStreams`,
   * such that every `println` or `System.err.println` goes through the logger
   */
  def streams: SystemStreams

  /**
   * A version of [[streams]] without the logging prefix appended to every line.
   * Used by the logging hierarchy to print things such that the logging prefixes
   * can be more finely customized per logger.
   */
  private[mill] def unprefixedStreams: SystemStreams = streams

  /**
   * Prints miscellaneous logging output which isn't part of the main output
   * a user is looking for, but useful to provide context on what Mill is doing
   */
  def info(s: String): Unit

  /**
   * Prints internal debug messages normally not shown to the user;
   * mostly useful when debugging issues
   */
  def debug(s: String): Unit

  /**
   * Prints logging output which represents warnings the user should care
   * about
   */
  def warn(s: String): Unit

  /**
   * Prints logging output which represents problems the user should care
   * about
   */
  def error(s: String): Unit

  /**
   * Prints short-lived logging output where consecutive lines over-write
   * each other; this shows up in the logger's prompt line in the multi-line
   * prompt when [[withPromptLine]] is running.
   *
   * Useful for information which is transient and disposable, e.g. progress
   * indicators.
   */
  def ticker(s: String): Unit

  /**
   * Global APIs that let the logger access the command line configuration and
   * manipulate the global prompt, e.g. enabling or disabling it
   */
  private[mill] def prompt: Logger.Prompt

  /**
   * Helper method to enable this logger as a line item in the global prompt
   * while the given code block is running
   */
  private[mill] final def withPromptLine[T](t: => T): T = {
    prompt.setPromptLine(logKey, keySuffix, message)
    try t
    finally prompt.removePromptLine(logKey)
  }

  /**
   * A short dash-separated prefix that is printed before every log line. Used to
   * uniquely identify log lines belonging to this logger from log lines belonging
   * to others, which is especially necessary in the presence of concurrency and
   * where logs get interleaved. Typically a single ID number or sequence of numbers.
   */
  private[mill] def logKey: Seq[String] = Nil

  /**
   * A longer one-liner message describing this logger that is the first time a log
   * line is generated. Useful for cross-referencing the short [[logKey]] with a more
   * meaningful module path and task name.
   */
  private[mill] def message: String = ""

  /**
   * A suffix appended to the [[logKey]] when the [[message]] is printed. Usually
   * the total task count, so the task ID in [[logKey]] can be compared to the total
   * task count to judge how much of the build has been completed
   */
  private[mill] def keySuffix: String = ""

  /**
   * Creates a new logger identical to this one but with stdout redirected
   * to the given stream; typically used to redirect out to err in `mill show`
   */
  def withOutStream(outStream: PrintStream): Logger = this

  /**
   * Whether the `--debug` flag was passed to Mill. Used to turn on additional
   * logging to console and files on disk that you may not want to turn on by
   * default due to verbosity or performance cost.
   */
  final def debugEnabled = prompt.debugEnabled
}

private[mill] object Logger {

  /**
   * APIs that allow a logger to interact with the global prompt: setting and unsetting
   * lines, enabling or disabling the prompt, etc. Normally passed through from logger
   * to logger unchanged without any customization.
   */
  trait Prompt {

    private[mill] def setPromptDetail(key: Seq[String], s: String): Unit
    private[mill] def reportKey(key: Seq[String]): Unit
    private[mill] def setPromptLine(key: Seq[String], keySuffix: String, message: String): Unit
    private[mill] def setPromptHeaderPrefix(s: String): Unit
    private[mill] def clearPromptStatuses(): Unit
    private[mill] def removePromptLine(key: Seq[String]): Unit
    private[mill] def withPromptPaused[T](t: => T): T
    private[mill] def withPromptUnpaused[T](t: => T): T

    def debugEnabled: Boolean

    private[mill] def enableTicker: Boolean

    def infoColor(s: String): String
    def warnColor(s: String): String
    def errorColor(s: String): String
    def colored: Boolean
  }
  object Prompt {
    class NoOp extends Prompt {
      private[mill] def setPromptDetail(key: Seq[String], s: String): Unit = ()
      private[mill] def reportKey(key: Seq[String]): Unit = ()
      private[mill] def setPromptLine(key: Seq[String], keySuffix: String, message: String): Unit =
        ()
      private[mill] def setPromptHeaderPrefix(s: String): Unit = ()
      private[mill] def clearPromptStatuses(): Unit = ()
      private[mill] def removePromptLine(key: Seq[String]): Unit = ()
      private[mill] def withPromptPaused[T](t: => T): T = t
      private[mill] def withPromptUnpaused[T](t: => T): T = t

      def debugEnabled: Boolean = false

      def enableTicker: Boolean = false
      def infoColor(s: String): String = s
      def warnColor(s: String): String = s
      def errorColor(s: String): String = s
      def colored: Boolean = false
    }
  }
}
