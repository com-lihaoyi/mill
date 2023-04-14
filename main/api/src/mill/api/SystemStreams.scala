package mill.api

import java.io.{InputStream, PrintStream}

class SystemStreams(
    val out: PrintStream,
    val err: PrintStream,
    val in: InputStream
)

object SystemStreams{
  val original = new SystemStreams(System.out, System.err, System.in)

  def withStreams[T](systemStreams: SystemStreams)(t: => T): T = {

    val out = System.out
    val in = System.in
    val err = System.err
    try {
      System.setIn(systemStreams.in)
      System.setErr(systemStreams.err)
      System.setOut(systemStreams.out)
      Console.withIn(systemStreams.in) {
        Console.withOut(systemStreams.out) {
          Console.withErr(systemStreams.err) {
            t
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
