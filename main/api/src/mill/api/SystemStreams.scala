package mill.api

import java.io.{InputStream, OutputStream, PrintStream}
import mill.main.client.InputPumper

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

  /**
   * Used to check whether the system streams are all "original", i,e. they
   * have not been overriden. Used for code paths that need to work differently
   * if they have been overriden (e.g. handling subprocess stdout/stderr)
   *
   * Assumes that the application only uses [[withStreams]] to override
   * stdout/stderr/stdin.
   */
  def isOriginal(): Boolean = {
    (System.out eq original.out) &&
    (System.err eq original.err) &&
    (System.in eq original.in) &&
    (Console.out eq original.out) &&
    (Console.err eq original.err)

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

  private class PumpedProcessInput extends os.ProcessInput {
    def redirectFrom = ProcessBuilder.Redirect.PIPE
    def processInput(processIn: => os.SubProcess.InputStream): Some[InputPumper] = Some(
      new InputPumper(() => System.in, () => processIn.wrapped, true, () => true)
    )
  }

  private class PumpedProcessOutput(dest: OutputStream) extends os.ProcessOutput {
    def redirectTo = ProcessBuilder.Redirect.PIPE
    def processOutput(processOut: => os.SubProcess.OutputStream): Some[InputPumper] =
      Some(new InputPumper(() => processOut.wrapped, () => dest, false, () => true))
  }
  def withStreams[T](systemStreams: SystemStreams)(t: => T): T = {
    val in = System.in
    val out = System.out
    val err = System.err
    try {
      System.setIn(systemStreams.in)
      System.setOut(systemStreams.out)
      System.setErr(systemStreams.err)
      Console.withIn(systemStreams.in) {
        Console.withOut(systemStreams.out) {
          Console.withErr(systemStreams.err) {
            os.Inherit.in.withValue(new PumpedProcessInput) {
              os.Inherit.out.withValue(new PumpedProcessOutput(System.out)) {
                os.Inherit.err.withValue(new PumpedProcessOutput(System.err)) {
                  t
                }
              }
            }
          }
        }
      }
    } finally {
      System.setErr(err)
      System.setOut(out)
      System.setIn(in)
    }
  }
}
