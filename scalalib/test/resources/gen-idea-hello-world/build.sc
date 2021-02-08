import mill.api.Loose.Agg
import mill.define.Target
import mill.scalalib
import mill.scalalib.{Dep, DepSyntax}

trait HelloWorldModule extends scalalib.ScalaModule {
  def scalaVersion = "2.12.4"
  object test extends super.Tests {
    def testFrameworks = Seq("utest.runner.Framework")
    override def compileIvyDeps: Target[Agg[Dep]] = Agg(
      ivy"org.slf4j:jcl-over-slf4j:1.7.25"
    )
    override def ivyDeps: Target[Agg[Dep]] = Agg(
      ivy"org.slf4j:slf4j-api:1.7.25",
      ivy"ch.qos.logback:logback-core:1.2.3",
    )
    override def runIvyDeps: Target[Agg[Dep]] = Agg(
      ivy"ch.qos.logback:logback-core:1.2.3",
      ivy"ch.qos.logback:logback-classic:1.2.3"
    )
  }
}

object HelloWorld extends HelloWorldModule

object HiddenWorld extends HelloWorldModule {
  override def skipIdea = true
}