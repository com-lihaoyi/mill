package millbuild

import mill.*
import mill.api.{BuildCtx, Discover, Module, PrecompiledModule}

class SimpleJavaModule(val scriptConfig: PrecompiledModule.Config)
    extends mill.javalib.JavaModule
    with PrecompiledModule {
  override lazy val millDiscover = Discover[this.type]

  // Walks the root module tree from within a task body. Used by the integration
  // test to verify `BuildCtx.rootModule` exposes every top-level precompiled
  // YAML module — not just the one this task happens to live in.
  def listModules = Task {
    BuildCtx.rootModule.asInstanceOf[Module]
      .moduleInternal.modules.map(_.moduleSegments.render).toSeq
  }
}
