import ammonite.ops._
import mill._
import mill.scalaplugin._


object Core extends ScalaModule {
  def scalaVersion = "2.12.4"
  override def compileIvyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
  )

  override def ivyDeps = Seq(
    Dep("com.lihaoyi", "sourcecode", "0.1.4"),
    Dep("com.lihaoyi", "pprint", "0.5.3"),
    Dep.Point("com.lihaoyi", "ammonite", "1.0.3"),
    Dep("com.typesafe.play", "play-json", "2.6.6"),
    Dep("org.scala-sbt", "zinc", "1.0.3"),
    Dep.Java("org.scala-sbt", "test-interface", "1.0")
  )

  def basePath = pwd / 'core
  override def sources = pwd/'core/'src/'main/'scala

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
}
object CoreTests extends ScalaModule {
  def scalaVersion = "2.12.4"
  override def projectDeps = Seq(Core)
  def basePath = pwd / 'scalaplugin
  override def sources = pwd/'core/'src/'test/'scala
  override def ivyDeps = Seq(
    Dep("com.lihaoyi", "utest", "0.6.0")
  )

  def test() = T.command{
    TestRunner.apply(
      "mill.UTestFramework",
      runDepClasspath().map(_.path) :+ compile().path,
      Seq(compile().path)
    )
  }
}

object ScalaPlugin extends ScalaModule {
  def scalaVersion = "2.12.4"
  override def projectDeps = Seq(Core)
  def basePath = pwd / 'scalaplugin
  override def sources = pwd/'scalaplugin/'src/'main/'scala

  override def prependShellScript =
    "#!/usr/bin/env sh\n" +
    "exec java -cp \"$0\" mill.scalaplugin.Main \"$@\""
}