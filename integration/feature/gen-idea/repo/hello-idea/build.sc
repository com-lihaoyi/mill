import mill.api.Loose.Agg
import mill.define.Target
import mill._
import mill.scalalib.{Dep, DepSyntax, TestModule}

trait HelloIdeaModule extends scalalib.ScalaModule {
  def scalaVersion = "2.12.5"
  object test extends ScalaTests with TestModule.Utest {
    override def compileIvyDeps: Target[Agg[Dep]] = Agg(
      ivy"org.slf4j:jcl-over-slf4j:1.7.25"
    )
    override def ivyDeps: Target[Agg[Dep]] = Agg(
      ivy"org.slf4j:slf4j-api:1.7.25",
      ivy"ch.qos.logback:logback-core:1.2.3"
    )
    override def runIvyDeps: Target[Agg[Dep]] = Agg(
      ivy"ch.qos.logback:logback-core:1.2.3",
      ivy"ch.qos.logback:logback-classic:1.2.3"
    )
  }
}

object HelloIdea extends HelloIdeaModule {
  object scala3 extends HelloIdeaModule {
    def scalaVersion = "3.3.1"
  }
}

object HiddenIdea extends HelloIdeaModule {
  override def skipIdea = true
}
