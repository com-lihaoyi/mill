package mill.playlib

import mill.{Task, T}
import mill.scalalib._

private[playlib] trait Layout extends JavaModule {

  def conf = Task.sources { millSourcePath / "conf" }
  def app = Task.sources { millSourcePath / "app" }

  override def sources = Task.sources { app() }
  override def resources = Task.sources { conf() }
}
