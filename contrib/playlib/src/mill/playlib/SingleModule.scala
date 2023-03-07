package mill.playlib

import mill.define.Module

trait SingleModule extends Module {
  override def millSourcePath: os.Path = super.millSourcePath / os.up
}
