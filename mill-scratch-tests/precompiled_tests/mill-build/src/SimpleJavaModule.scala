package millbuild

import mill.*
import mill.api.Discover

class SimpleJavaModule(val scriptConfig: mill.api.PrecompiledModule.Config)
    extends mill.javalib.JavaModule
    with mill.api.PrecompiledModule {
  override lazy val millDiscover = Discover[this.type]
}

