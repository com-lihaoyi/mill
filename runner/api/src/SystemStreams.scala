package mill.runner.api

import java.io.{InputStream, OutputStream, PrintStream}

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
