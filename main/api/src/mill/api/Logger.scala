package mill.api

import java.io._

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
  *
  * Also contains the two forwarded stdout and stderr streams, for code executed
  * by Mill to use directly. Typically these correspond to the stdout and stderr,
  * but when `show` is used both are forwarded to stderr and stdout is only
  * used to display the final `show` output for easy piping.
  */
trait Logger {
  def colored: Boolean

  def errorStream: PrintStream
  def outputStream: PrintStream
  def inStream: InputStream

  def info(s: String): Unit
  def error(s: String): Unit
  def ticker(s: String): Unit
  def debug(s: String): Unit

  def close(): Unit = ()
}
