package mill.playlib

import mill.{Task, T}

private[playlib] trait Router extends RouterModule with Layout {
  override def routes = Task { conf() }
}
