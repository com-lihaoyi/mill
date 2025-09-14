package mill.script
import mill.*
import mill.api.Discover
import mill.javalib.{JavaModule, TestModule}
import mill.kotlinlib.KotlinModule
import mill.scalalib.ScalaModule
import mill.javalib.PublishModule
import mill.api.Discover

trait Java0 extends ScriptModule
class Java(val millFile: os.Path, override val moduleDeps: Seq[JavaModule]) extends ScriptModule {
  override lazy val millDiscover = Discover[this.type]
}
object Java {
  class Publish(val millFile: os.Path, override val moduleDeps: Seq[JavaModule with PublishModule]) extends ScriptModule, ScriptModule.Publish {
    override lazy val millDiscover = Discover[this.type]
  }
  trait Test0 extends Java0, JavaModule.Tests{
    def outer = moduleDeps.head
  }
  class TestNg(val millFile: os.Path, override val moduleDeps: Seq[JavaModule]) extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(val millFile: os.Path, override val moduleDeps: Seq[JavaModule]) extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(val millFile: os.Path, override val moduleDeps: Seq[JavaModule]) extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
}

trait Scala0 extends ScriptModule, ScalaModule {
  def scalaVersion = mill.util.BuildInfo.scalaVersion
}
class Scala(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Scala0 {
  override lazy val millDiscover = Discover[this.type]
}

object Scala {
  class Publish(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule with PublishModule]) extends Scala0, ScriptModule.Publish {
    override lazy val millDiscover = Discover[this.type]
  }
  trait Test0 extends Scala0, ScalaModule.Tests{
    def outer = moduleDeps.head.asInstanceOf[ScalaModule]
  }
  class TestNg(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaTest(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.ScalaTest {
    override lazy val millDiscover = Discover[this.type]
  }
  class Specs2(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.Specs2 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Utest(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.Utest {
    override lazy val millDiscover = Discover[this.type]
  }
  class Munit(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.Munit {
    override lazy val millDiscover = Discover[this.type]
  }
  class Weaver(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.Weaver {
    override lazy val millDiscover = Discover[this.type]
  }
  class ZioTest(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.ZioTest {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaCheck(val millFile: os.Path, override val moduleDeps: Seq[ScalaModule]) extends Test0, TestModule.ScalaCheck {
    override lazy val millDiscover = Discover[this.type]
  }
}

trait Kotlin0 extends ScriptModule, KotlinModule {
  def kotlinVersion = "1.9.24"
  override lazy val millDiscover = Discover[this.type]
}
class Kotlin(val millFile: os.Path, override val moduleDeps: Seq[KotlinModule]) extends Kotlin0 {
  override lazy val millDiscover = Discover[this.type]
}

object Kotlin {
  class Publish(val millFile: os.Path, override val moduleDeps: Seq[KotlinModule with PublishModule]) extends Kotlin0, ScriptModule.Publish {
    override lazy val millDiscover = Discover[this.type]
  }
  trait Test0 extends Kotlin0, KotlinModule.Tests{
    def outer = moduleDeps.head.asInstanceOf[KotlinModule]
  }
  class TestNg(val millFile: os.Path, override val moduleDeps: Seq[KotlinModule]) extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit4(val millFile: os.Path, override val moduleDeps: Seq[KotlinModule]) extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit5(val millFile: os.Path, override val moduleDeps: Seq[KotlinModule]) extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
}
