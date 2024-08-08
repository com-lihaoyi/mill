package mill.playlib

import mill.{task, T}
import mill.scalalib._

private[playlib] trait Layout extends JavaModule {

  def conf = task.sources { millSourcePath / "conf" }
  def app = task.sources { millSourcePath / "app" }

  override def sources = task.sources { app() }
  override def resources = task.sources { conf() }
}
