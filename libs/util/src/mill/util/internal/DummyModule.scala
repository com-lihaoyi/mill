package mill.util.internal

import mill.*
import mill.api.Discover
import mill.util.MainRootModule

/**
 * Pre-compiled empty root module used when codegen produces no `build_.package_`
 * (e.g. the workspace has no `build.mill`/`build.mill.yaml`, or the root build
 * file is itself precompiled). Behaves as an empty root; orphan precompiled
 * YAML modules show up via `MainRootModule`'s `moduleDirectChildren` override.
 */
private[mill] object DummyModule extends MainRootModule {
  override lazy val millDiscover: Discover = Discover[this.type]
}
