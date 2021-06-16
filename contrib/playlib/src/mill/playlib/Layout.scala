package mill.playlib

import mill.T
import mill.scalalib._

private[playlib] trait Layout extends JavaModule {

  def conf = T.sources { millSourcePath / "conf" }
  def app = T.sources { millSourcePath / "app" }

  override def sources = T.sources { app() }
  override def resources = T.sources { conf() }
}
