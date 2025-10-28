package mill.script
import mill.*
import mill.api.{Discover, ExternalModule}
import mill.javalib.{TestModule, DepSyntax, Dep}

class ScalaModule(val scriptConfig: ScriptModule.Config) extends ScalaModule.Base {
  override lazy val millDiscover = Discover[this.type]

  override def defaultScriptMvnDeps = Seq(
    mvn"com.lihaoyi::pprint:${mill.script.BuildInfo.pprintVersion}",
    mvn"com.lihaoyi::os-lib:${mill.script.BuildInfo.osLibVersion}",
    mvn"com.lihaoyi::upickle:${mill.script.BuildInfo.upickleVersion}",
    mvn"com.lihaoyi::requests:${mill.script.BuildInfo.requestsVersion}",
    mvn"com.lihaoyi::mainargs:${mill.script.BuildInfo.mainargsVersion}"
  )
}

object ScalaModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.TestNg with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.Junit4 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.Junit5 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaTest(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.ScalaTest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Specs2(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.Specs2 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Utest(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.Utest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Munit(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.Munit with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Weaver(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.Weaver with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ZioTest(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.ZioTest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaCheck(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig)
      with TestModule.ScalaCheck with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }

}
