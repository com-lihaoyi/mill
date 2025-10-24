package mill.script

import mill.*
import mill.api.ExternalModule
import mill.api.Discover
import mill.javalib.TestModule

class KotlinModule(val scriptConfig: ScriptModule.Config) extends KotlinModule.Base {
  override lazy val millDiscover = Discover[this.type]
}

object KotlinModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends KotlinModule(scriptConfig)
      with TestModule.TestNg with mill.kotlinlib.KotlinModule.KotlinTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends KotlinModule(scriptConfig)
      with TestModule.Junit4 with mill.kotlinlib.KotlinModule.KotlinTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends KotlinModule(scriptConfig)
      with TestModule.Junit5 with mill.kotlinlib.KotlinModule.KotlinTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.kotlinlib.KotlinModule {
    def kotlinVersion = "2.0.20"
  }
}
