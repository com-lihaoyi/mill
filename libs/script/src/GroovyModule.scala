package mill.script

import mill.*
import mill.api.ExternalModule
import mill.api.Discover
import mill.api.ScriptModule
import mill.javalib.TestModule

@mill.api.experimental
class GroovyModule(val scriptConfig: ScriptModule.Config) extends GroovyModule.Base {
  override lazy val millDiscover = Discover[this.type]
}

object GroovyModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.TestNg with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.Junit4 with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.Junit5 with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Spock(scriptConfig: ScriptModule.Config) extends GroovyModule(scriptConfig)
      with TestModule.Spock with mill.groovylib.GroovyModule.GroovyTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.groovylib.GroovyModule {
    def groovyVersion = "5.0.2"
  }
}
