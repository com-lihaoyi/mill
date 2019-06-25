import mill.Agg
import mill.scalalib._
import ammonite.ops._

object core extends ScalaModule{
  def scalaVersion = "2.12.8"
  def ivyDeps = Agg(
    ivy"org.eclipse.jetty:jetty-websocket:8.1.16.v20140903",
    ivy"org.eclipse.jetty:jetty-server:8.1.16.v20140903"
  )
}

object foo extends ScalaModule {
  def scalaVersion = "2.12.8"
  // def scalacPluginIvyDeps = Agg(ivy"org.scala-lang:scala-compiler:${scalaVersion()}")
  def ivyDeps = Agg(ivy"org.scala-lang:scala-reflect:${scalaVersion()}",
    ivy"org.scala-lang:scala-compiler:${scalaVersion()}")
  object foo_test extends Tests {
    def moduleDeps = Seq(foo)
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFrameworks = Seq("utest.runner.Framework")
    def generatedSources = T {
      Seq(PathRef(os.pwd / "foo/test/src/Generated.scala"))
    }
  }
}

object bar extends ScalaModule {
  def scalaVersion = "2.12.8"
  def moduleDeps = Seq(foo)
  def mainClass = Some("BarMain")
  def compileIvyDeps = Agg(ivy"org.scala-lang:scala-reflect:${scalaVersion()}")
  def scalacOptions = Seq("-no-specialization")
  def ivyDeps = Agg(ivy"ch.epfl.scala::bloop-config:1.2.5")
}
