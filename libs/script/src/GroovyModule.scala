package mill.script

import mill.*
import mill.api.ExternalModule
import mill.api.Discover
import mill.api.PrecompiledModule
import mill.javalib.TestModule

@mill.api.experimental
class GroovyModule(val scriptConfig: PrecompiledModule.Config) extends GroovyModule.Base {
  override lazy val millDiscover = Discover[this.type]
}

@mill.api.experimental
object GroovyModule {
  class TestNg(scriptConfig: PrecompiledModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.TestNg with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: PrecompiledModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.Junit4 with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: PrecompiledModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.Junit5 with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Spock(scriptConfig: PrecompiledModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.Spock with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.groovylib.GroovyModule {
    def groovyVersion = BuildInfo.groovyVersion
  }
}
