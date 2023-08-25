package mill.util

import mill.api.Logger

import java.io.PrintStream

trait ColorLogger extends Logger {
  def infoColor: fansi.Attrs
  def errorColor: fansi.Attrs
  def withOutStream(outStream: PrintStream): ColorLogger = this
}
