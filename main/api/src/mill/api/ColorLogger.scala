package mill.api

import java.io.PrintStream

trait ColorLogger extends Logger {
  override def withOutStream(outStream: PrintStream): ColorLogger = this
}
