package millbuild
import mill.*, javalib.*

class MyModule(val scriptConfig: mill.api.PrecompiledModule.Config)
    extends mill.javalib.JavaModule with mill.api.PrecompiledModule {

  override lazy val millDiscover = mill.api.Discover[this.type]

  object test extends JavaTests with mill.javalib.TestModule.Junit5
}

// A class with the wrong constructor signature (takes String instead of Config)
class WrongSigModule(val name: String)
