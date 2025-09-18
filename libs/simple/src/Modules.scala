package mill.simple
import mill.*
import mill.api.ModuleRef
import mill.javalib.{JavaModule, TestModule}
import mill.kotlinlib.KotlinModule
import mill.scalalib.ScalaModule
import mill.javalib.PublishModule
import mill.api.Discover

class Java(val simpleConf: SimpleModule.Config0[JavaModule])
    extends Java.Base {
  override lazy val millDiscover = Discover[this.type]
}
object Java {
  trait Base extends SimpleModule
  class Publish(val simpleConf: SimpleModule.Config0[JavaModule with PublishModule])
      extends Java.Base, SimpleModule.Publish {
    override lazy val millDiscover = Discover[this.type]
  }
  trait Test0 extends Java.Base, JavaModule.Tests {
    def outerRef = ModuleRef(simpleConf.moduleDeps.head)
  }
  class TestNg(val simpleConf: SimpleModule.Config0[JavaModule]) extends Test0,
        TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(val simpleConf: SimpleModule.Config0[JavaModule]) extends Test0,
        TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(val simpleConf: SimpleModule.Config0[JavaModule]) extends Test0,
        TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
}

class Scala(val simpleConf: SimpleModule.Config0[JavaModule])
    extends Scala.Base {
  override lazy val millDiscover = Discover[this.type]
}

object Scala {
  trait Base extends SimpleModule, ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }
  class Publish(val simpleConf: SimpleModule.Config0[JavaModule with PublishModule])
      extends Scala.Base, SimpleModule.Publish {
    override lazy val millDiscover = Discover[this.type]
  }
  trait Test0 extends Base, ScalaModule.Tests {
    def outerRef = ModuleRef(simpleConf.moduleDeps.head.asInstanceOf[ScalaModule])
  }
  class TestNg(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaTest(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.ScalaTest {
    override lazy val millDiscover = Discover[this.type]
  }
  class Specs2(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.Specs2 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Utest(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.Utest {
    override lazy val millDiscover = Discover[this.type]
  }
  class Munit(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.Munit {
    override lazy val millDiscover = Discover[this.type]
  }
  class Weaver(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.Weaver {
    override lazy val millDiscover = Discover[this.type]
  }
  class ZioTest(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.ZioTest {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaCheck(val simpleConf: SimpleModule.Config0[ScalaModule])
      extends Test0, TestModule.ScalaCheck {
    override lazy val millDiscover = Discover[this.type]
  }
}

class Kotlin(val simpleConf: SimpleModule.Config0[JavaModule])
    extends Kotlin.Base {
  override lazy val millDiscover = Discover[this.type]
}

object Kotlin {
  trait Base extends SimpleModule, KotlinModule {
    def kotlinVersion = "1.9.24"
  }
  class Publish(val simpleConf: SimpleModule.Config0[JavaModule with PublishModule])
      extends Kotlin.Base, SimpleModule.Publish {
    override lazy val millDiscover = Discover[this.type]
  }
  trait Test0 extends Kotlin.Base, KotlinModule.Tests {
    def outerRef = ModuleRef(simpleConf.moduleDeps.head.asInstanceOf[KotlinModule])
  }
  class TestNg(val simpleConf: SimpleModule.Config0[KotlinModule])
      extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit4(val simpleConf: SimpleModule.Config0[KotlinModule])
      extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit5(val simpleConf: SimpleModule.Config0[KotlinModule])
      extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
}
