package mill.api

import mill.api.DummyInputStream
import mill.constants.InputPumper

import java.io.{InputStream, OutputStream, PrintStream}

/**
 * Utilities for managing and redirecting the [[SystemStreams]] modelling
 * the stdin/stdout/stderr of the process
 */
object SystemStreamsUtils {
  import SystemStreams.*

  private class PumpedProcessOutput(dest: OutputStream) extends os.ProcessOutput {
    def redirectTo = ProcessBuilder.Redirect.PIPE
    def processOutput(processOut: => os.SubProcess.OutputStream): Some[InputPumper] =
      Some(new InputPumper(() => processOut.wrapped, () => dest, false))
  }
  def withStreams[T](systemStreams: mill.api.SystemStreams)(t: => T): T = {
    // If we are setting a stream back to its original value, make sure we reset
    // `os.Inherit` to `os.InheritRaw` for that stream. This direct inheritance
    // ensures that interactive applications involving console IO work, as the
    // presence of a `PumpedProcess` would cause most interactive CLIs (e.g.
    // scala console, REPL, etc.) to misbehave
    //
    // Use `DummyInputStream` for the `stdin` if we are not inheriting the raw streams,
    // because otherwise sharing the same `stdin` stream between multiple concurrent
    // tasks doesn't make sense (even though sharing the same `stdout` is generally fine)
    val inheritIn: os.ProcessInput =
      if (systemStreams.in eq original.in) os.InheritRaw
      else DummyInputStream

    val inheritOut =
      if (systemStreams.out eq original.out) os.InheritRaw
      else new PumpedProcessOutput(systemStreams.out)

    val inheritErr =
      if (systemStreams.err eq original.err) os.InheritRaw
      else new PumpedProcessOutput(systemStreams.err)

    ThreadLocalStreams.current.withValue(systemStreams) {
      Console.withIn(systemStreams.in) {
        Console.withOut(systemStreams.out) {
          Console.withErr(systemStreams.err) {
            os.Inherit.in.withValue(inheritIn) {
              os.Inherit.out.withValue(inheritOut) {
                os.Inherit.err.withValue(inheritErr) {
                  t
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * Manages the global override of `System.{in,out,err}`. Overrides of those streams are
   * global, so we cannot just override them per-use-site in a multithreaded environment
   * because different threads may interleave and stomp over each other's over-writes.
   *
   * Instead, we over-write them globally with a set of streams that does nothing but
   * forward to the per-thread [[ThreadLocalStreams.current]] streams, allowing callers
   * to each reach their own thread-local streams without clashing across multiple threads
   */
  def withTopLevelSystemStreamProxy[T](t: => T): T = {
    val in = System.in
    val out = System.out
    val err = System.err

    try {
      setTopLevelSystemStreamProxy()
      t
    } finally {
      System.setErr(err)
      System.setOut(out)
      System.setIn(in)
    }
  }

  def setTopLevelSystemStreamProxy(): Unit = {
    val _ = mill.api.SystemStreams.current
    // Make sure to initialize `Console` to cache references to the original
    // `System.{in,out,err}` streams before we redirect them
    val _ = Console.out
    val _ = Console.err
    val _ = Console.in
    System.setIn(ThreadLocalStreams.In)
    System.setOut(ThreadLocalStreams.Out)
    System.setErr(ThreadLocalStreams.Err)
  }

  def current(): mill.api.SystemStreams =
    ThreadLocalStreams.current.value

  private[mill] object ThreadLocalStreams {
    def current = mill.api.SystemStreams.current

    object Out extends PrintStream(new ProxyOutputStream { def delegate() = current.value.out })
    object Err extends PrintStream(new ProxyOutputStream { def delegate() = current.value.err })
    object In extends ProxyInputStream { def delegate() = current.value.in }

    abstract class ProxyOutputStream extends OutputStream {
      def delegate(): OutputStream
      override def write(b: Array[Byte], off: Int, len: Int): Unit = delegate().write(b, off, len)
      override def write(b: Array[Byte]): Unit = delegate().write(b)
      def write(b: Int): Unit = delegate().write(b)
      override def flush(): Unit = delegate().flush()
      override def close(): Unit = delegate().close()
    }
    abstract class ProxyInputStream extends InputStream {
      def delegate(): InputStream
      override def read(): Int = delegate().read()
      override def read(b: Array[Byte], off: Int, len: Int): Int = delegate().read(b, off, len)
      override def read(b: Array[Byte]): Int = delegate().read(b)
      override def readNBytes(b: Array[Byte], off: Int, len: Int): Int =
        delegate().readNBytes(b, off, len)
      override def readNBytes(len: Int): Array[Byte] = delegate().readNBytes(len)
      override def readAllBytes(): Array[Byte] = delegate().readAllBytes()
      override def mark(readlimit: Int): Unit = delegate().mark(readlimit)
      override def markSupported(): Boolean = delegate().markSupported()
      override def available(): Int = delegate().available()
      override def reset(): Unit = delegate().reset()
      override def skip(n: Long): Long = delegate().skip(n)
      // Not present in some versions of Java
      //      override def skipNBytes(n: Long): Unit = delegate().skipNBytes(n)
      override def close(): Unit = delegate().close()
      override def transferTo(out: OutputStream): Long = delegate().transferTo(out)
    }
  }
  private def debugPrintln(s: String) = ()
  private[mill] class DebugDelegateStream(delegate0: mill.api.SystemStreams)
      extends mill.api.SystemStreams(
        new PrintStream(new ThreadLocalStreams.ProxyOutputStream {
          override def delegate(): OutputStream = delegate0.out

          override def write(b: Array[Byte], off: Int, len: Int): Unit = {
            debugPrintln(new String(b, off, len))
            super.write(b, off, len)
          }

          override def write(b: Array[Byte]): Unit = {
            debugPrintln(new String(b))
            super.write(b)
          }

          override def write(b: Int): Unit = {
            debugPrintln(new String(Array(b.toByte)))
            super.write(b)
          }
        }),
        new PrintStream(new ThreadLocalStreams.ProxyOutputStream {
          override def delegate(): OutputStream = delegate0.err
          override def write(b: Array[Byte], off: Int, len: Int): Unit = {
            debugPrintln(new String(b, off, len))
            super.write(b, off, len)
          }

          override def write(b: Array[Byte]): Unit = {
            debugPrintln(new String(b))
            super.write(b)
          }

          override def write(b: Int): Unit = {
            debugPrintln(new String(Array(b.toByte)))
            super.write(b)
          }
        }),
        delegate0.in
      )
}
