package mill.playlib

import mill.{task, T}

private[playlib] trait Router extends RouterModule with Layout {
  override def routes = task { conf() }
}
