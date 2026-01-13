package mill.util.internal

import mill.*
import mill.api.Discover
import mill.util.MainRootModule

import DummyMiscInfo.given

/**
 * Pre-compiled empty root module for Mill scripting use cases.
 * Used when there's no build.mill file in the project directory.
 */
object DummyModule extends MainRootModule {
  override lazy val millDiscover: Discover = Discover[this.type]
}
