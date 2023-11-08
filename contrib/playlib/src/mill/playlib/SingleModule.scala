package mill.playlib

import mill.define.Module

@deprecated("extend RootModule instead.", since = "mill 0.11.6")
trait SingleModule extends Module {
  override def millSourcePath: os.Path = super.millSourcePath / os.up
}
