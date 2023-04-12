package mill.util

import mill.api.SystemStreams

object Util {

  def isInteractive() = System.console() != null

  val newLine = System.lineSeparator()

  val windowsPlatform = System.getProperty("os.name").startsWith("Windows")

  val java9OrAbove = !System.getProperty("java.specification.version").startsWith("1.")

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
