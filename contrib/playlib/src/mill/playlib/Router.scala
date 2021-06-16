package mill.playlib

import mill.T

private[playlib] trait Router extends RouterModule with Layout {
  override def routes = T { conf() }
}
