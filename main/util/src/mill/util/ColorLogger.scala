package mill.util

import mill.api.Logger

trait ColorLogger extends Logger {
  def infoColor: fansi.Attrs
  def errorColor: fansi.Attrs
}
