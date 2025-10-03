package mill.simple

import mill.api.{Discover, ModuleRef}
import mill.*
import mill.kotlinlib.*
class KotlinModule(val simpleConf: SimpleModule.Config) extends KotlinModule.Base {
  override lazy val millDiscover = Discover[this.type]
}
object KotlinModule {

  trait Base extends mill.simple.JavaModule.Base, mill.kotlinlib.KotlinModule {
    def kotlinVersion = "1.9.24"
  }

  class Publish(simpleConf: SimpleModule.Config)
      extends mill.simple.JavaModule.Publish(simpleConf), KotlinModule.Base {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Test0 extends KotlinModule.Base, mill.kotlinlib.KotlinModule.Tests {
    def outerRef = ModuleRef(simpleConf.moduleDeps.head.asInstanceOf[mill.kotlinlib.KotlinModule])
  }

  class TestNg(val simpleConf: SimpleModule.Config) extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit4(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit5(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
}
