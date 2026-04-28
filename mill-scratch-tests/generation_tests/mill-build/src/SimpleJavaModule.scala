package millbuild
import mill.*, javalib.*

class SimpleJavaModule(val scriptConfig: mill.api.PrecompiledModule.Config)
    extends mill.javalib.JavaModule with mill.api.PrecompiledModule {

  override lazy val millDiscover = mill.api.Discover[this.type]
}
