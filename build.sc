import ammonite.ops._
import mill._
import mill.scalaplugin._

trait MillModule extends ScalaModule{ outer =>
  def scalaVersion = "2.12.4"
  override def sources = basePath/'src/'main/'scala
  object test extends this.Tests{
    def basePath = outer.basePath
    override def ivyDeps = Seq(Dep("com.lihaoyi", "utest", "0.6.0"))
    override def sources = basePath/'src/'test/'scala
    def testFramework = "mill.UTestFramework"
    
    def organization = "com.lihaoyi"
    def name = "mill-test"
    def version = "0.0.1" 
  }
}

object Core extends MillModule {
  override def compileIvyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
  )

  override def ivyDeps = Seq(
    Dep("com.lihaoyi", "sourcecode", "0.1.4"),
    Dep("com.lihaoyi", "pprint", "0.5.3"),
    Dep.Point("com.lihaoyi", "ammonite", "1.0.3"),
    Dep("com.typesafe.play", "play-json", "2.6.6"),
    Dep("org.scala-sbt", "zinc", "1.0.5"),
    Dep.Java("org.scala-sbt", "test-interface", "1.0")
  )

  def basePath = pwd / 'core

  val cross =
    for(jarLabel <- mill.define.Cross("jarA", "jarB", "jarC"))
    yield new mill.Module{
      def printIt() = T.command{
        println("PRINTING IT: " + jarLabel)
      }
      def jar = T{
        val dest = T.ctx().dest
        ammonite.ops.mkdir(dest/ammonite.ops.up)
        ammonite.ops.cp(Core.jar().path, dest)

        PathRef(dest)
      }
    }

  def organization = "com.lihaoyi"
  def name = "mill"
  def version = "0.0.1"
  override def useFullScalaVersionForPublish: T[Boolean] = true
}

object ScalaPlugin extends MillModule {

  override def projectDeps = Seq(Core)
  def basePath = pwd / 'scalaplugin
  override def prependShellScript =
    "#!/usr/bin/env sh\n" +
    "exec java -cp \"$0\" mill.scalaplugin.Main \"$@\""

  def organization = "com.lihaoyi"
  def name = "mill-scala"
  def version = "0.0.1"
  override def useFullScalaVersionForPublish: T[Boolean] = true
}
