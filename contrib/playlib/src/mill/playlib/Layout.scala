package mill.playlib

import mill.Task
import mill.scalalib._

private[playlib] trait Layout extends JavaModule {

  def conf = Task.Sources("conf")
  def app = Task.Sources("app")

  override def sources = Task { app() }
  override def resources = Task { conf() }
}
