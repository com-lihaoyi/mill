package mill.simple

import mill.api.{Discover, ModuleRef}
import mill.*
import mill.scalalib.*
class ScalaModule(val simpleConf: SimpleModule.Config) extends ScalaModule.Base {
  override lazy val millDiscover = Discover[this.type]
}
object ScalaModule {

  trait Base extends mill.simple.JavaModule.Base, mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }

  class Publish(simpleConf: SimpleModule.Config)
      extends mill.simple.JavaModule.Publish(simpleConf), ScalaModule.Base {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Test0 extends Base, mill.scalalib.ScalaModule.Tests {
    def outerRef = ModuleRef(simpleConf.moduleDeps.head.asInstanceOf[mill.scalalib.ScalaModule])
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

  class ScalaTest(val simpleConf: SimpleModule.Config) extends Test0, TestModule.ScalaTest {
    override lazy val millDiscover = Discover[this.type]
  }

  class Specs2(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Specs2 {
    override lazy val millDiscover = Discover[this.type]
  }

  class Utest(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Utest {
    override lazy val millDiscover = Discover[this.type]
  }

  class Munit(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Munit {
    override lazy val millDiscover = Discover[this.type]
  }

  class Weaver(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Weaver {
    override lazy val millDiscover = Discover[this.type]
  }

  class ZioTest(val simpleConf: SimpleModule.Config) extends Test0, TestModule.ZioTest {
    override lazy val millDiscover = Discover[this.type]
  }

  class ScalaCheck(val simpleConf: SimpleModule.Config) extends Test0, TestModule.ScalaCheck {
    override lazy val millDiscover = Discover[this.type]
  }
}
