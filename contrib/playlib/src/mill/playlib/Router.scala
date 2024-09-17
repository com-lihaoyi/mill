package mill.playlib

import mill.{T, Task}

private[playlib] trait Router extends RouterModule with Layout {
  override def routes = Task { conf() }
}
