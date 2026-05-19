package millbuild

import mill.*
import mill.api.{Discover, PrecompiledModule}

class SimpleJavaModule(val scriptConfig: PrecompiledModule.Config)
    extends mill.javalib.JavaModule
    with PrecompiledModule {
  override lazy val millDiscover = Discover[this.type]
}
