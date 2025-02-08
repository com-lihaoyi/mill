package mill.api

import mill.api.Logger

import java.io.PrintStream

trait ColorLogger extends Logger {
  override def withOutStream(outStream: PrintStream): ColorLogger = this
}
