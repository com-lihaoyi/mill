package mill.api

import java.io.InputStream
import java.io.PrintStream

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
  val original = new SystemStreams(System.out, System.err, System.in)
  val current = new DynamicVariable(original)
}
