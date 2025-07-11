package mill.api.daemon

import java.io.{InputStream, PrintStream}
import scala.util.DynamicVariable

/**
 * Represents a set of streams that look similar to those provided by the
 * operating system. These may internally be proxied/redirected/processed, but
 * from the consumer's perspective they look just like the stdout/stderr/stdin
 * that any Unix process receives from the OS.
 */
class SystemStreams(
    val out: PrintStream,
    val err: PrintStream,
    val in: InputStream
)

object SystemStreams {

  /**
   * The original system streams of this process, before any redirection.
   *
   * NOTE: you should not use this! They do not get captured properly by Mill's stdout/err
   * redirection, and thus only get picked up from the Mill server log files asynchronously.
   * That means that the logs may appear out of order, jumbling your logs and screwing up
   * your terminal
   */
  val original = new SystemStreams(System.out, System.err, System.in)
  val current = new DynamicVariable(original)

  /**
   * Used to check whether the system streams are all "original", i,e. they
   * have not been overridden. Used for code paths that need to work differently
   * if they have been overridden (e.g. handling subprocess stdout/stderr)
   *
   * Assumes that the application only uses [[withStreams]] to override
   * stdout/stderr/stdin.
   */
  def isOriginal(): Boolean = {
    (Console.out eq original.out) && (Console.err eq original.err)
    // We do not check System.* for equality because they are always overridden by
    // `ThreadLocalStreams`
    //    (System.out eq original.out) &&
    //    (System.err eq original.err) &&
    //    (System.in eq original.in) &&

    // We do not check `Console.in` for equality, because `Console.withIn` always wraps
    // `Console.in` in a `new BufferedReader` each time, and so it is impossible to check
    // whether it is original or not. We just have to assume that it is kept in sync with
    // `System.in`, which `withStreams` does ensure.
    //
    // (Console.in eq original.consoleIn)
  }

  /**
   * The original non-override stderr, used for debugging purposes e.g. if you
   * want to print stuff while the system streams override are messed up
   */

  def originalErr: PrintStream = original.err
}
