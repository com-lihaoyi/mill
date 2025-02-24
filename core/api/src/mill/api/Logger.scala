package mill.api

import java.io.{InputStream, PrintStream}

/**
 * The standard logging interface of the Mill build tool.
 *
 * Contains these primary logging methods, in order of increasing importance:
 *
 * - `debug` : internal debug messages normally not shown to the user;
 * mostly useful when debugging issues
 *
 * - `ticker`: short-lived logging output where consecutive lines over-write
 * each other; useful for information which is transient and disposable
 *
 * - `info`: miscellaneous logging output which isn't part of the main output
 * a user is looking for, but useful to provide context on what Mill is doing
 *
 * - `error`: logging output which represents problems the user should care
 * about
 *
 * Also contains the two forwarded stdout and stderr streams, for code executed
 * by Mill to use directly. Typically, these correspond to the stdout and stderr,
 * but when `show` is used both are forwarded to stderr and stdout is only
 * used to display the final `show` output for easy piping.
 */
trait Logger extends AutoCloseable {
  def infoColor: fansi.Attrs = fansi.Attrs.Empty
  def errorColor: fansi.Attrs = fansi.Attrs.Empty
  def colored: Boolean

  private[mill] def unprefixedSystemStreams: SystemStreams = systemStreams
  def systemStreams: SystemStreams

  def errorStream: PrintStream = systemStreams.err
  def outputStream: PrintStream = systemStreams.out

  /**
   * [[rawOutputStream]] is intended to be a version of [[outputStream]]
   * without decoration: colors, prefixes, timestamps, etc. It is intended
   * for the use of tasks like `show` which output data in a way that is
   * easily readable by downstream programs.
   */
  def rawOutputStream: PrintStream = systemStreams.out
  def inStream: InputStream = systemStreams.in

  def info(s: String): Unit
  def debug(s: String): Unit
  def error(s: String): Unit
  def ticker(s: String): Unit

  private[mill] def setPromptDetail(key: Seq[String], s: String): Unit = ticker(s)
  private[mill] def reportKey(key: Seq[String]): Unit = ()
  private[mill] def setPromptLine(
      key: Seq[String],
      verboseKeySuffix: String,
      message: String
  ): Unit =
    ticker(s"${key.mkString("-")} $message")
  private[mill] def setPromptLine(): Unit = ()
  private[mill] def setPromptHeaderPrefix(s: String): Unit = ()
  private[mill] def clearPromptStatuses(): Unit = ()
  private[mill] def removePromptLine(key: Seq[String]): Unit = ()
  private[mill] def removePromptLine(): Unit = ()
  private[mill] def withPromptPaused[T](t: => T): T = t
  private[mill] def withPromptUnpaused[T](t: => T): T = t

  /**
   * @since Mill 0.10.5
   */
  // We only default-implement it to keep binary compatibility in 0.10.x
  def debugEnabled: Boolean = false

  def close(): Unit = ()

  def enableTicker: Boolean = false

  private[mill] def subLogger(path: os.Path, verboseKeySuffix: String, message: String): Logger =
    this

  private[mill] def withPrompt[T](t: => T): T = {
    setPromptLine()
    try t
    finally removePromptLine()
  }

  def withOutStream(outStream: PrintStream): Logger = this
  private[mill] def logPrefixKey: Seq[String] = Nil

  private[mill] def setFailedTasksCount(count: Int): Unit = ()
}
