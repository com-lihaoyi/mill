import ammonite.ops._
import mill._
import mill.scalaplugin._

trait MillModule extends ScalaModule{ outer =>
  def scalaVersion = "2.12.4"
  override def sources = basePath/'src/'main/'scala

  override def compileIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))
  override def scalacOptions = Seq("-P:acyclic:force")
  override def scalacPluginIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))

  def testArgs = T{ Seq.empty[String] }

  object test extends this.Tests{
    override def defaultCommandName() = "forkTest"
    override def forkArgs = T{ testArgs() }
    override def projectDeps =
      if (this == Core.test) Seq(Core)
      else Seq(outer, Core.test)
    def basePath = outer.basePath
    override def ivyDeps = Seq(Dep("com.lihaoyi", "utest", "0.6.0"))
    override def sources = basePath/'src/'test/'scala
    def testFramework = "mill.UTestFramework"
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
}


val bridges = for{
  crossVersion <- mill.define.Cross("2.10.6", "2.11.8", "2.11.11", "2.12.3", "2.12.4")
} yield new ScalaModule{
  def basePath = pwd / 'bridge
  def scalaVersion = crossVersion
  override def allSources = T{

    val v = crossVersion.split('.').dropRight(1).mkString(".")
    val url =
      s"http://repo1.maven.org/maven2/org/scala-sbt/compiler-bridge_$v/1.0.5/compiler-bridge_$v-1.0.5-sources.jar"
    val curlDest = T.ctx().dest
    implicit val pwd = curlDest
    mkdir(curlDest)
    rm(curlDest/"bridge.jar")

    %("curl", "-L", "-o", curlDest / "bridge.jar", url)
    %("unzip", curlDest / "bridge.jar" , "-d", curlDest / 'classes)


    Seq(PathRef(curlDest / 'classes))
  }
  override def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", crossVersion),
    Dep.Java("org.scala-sbt", "compiler-interface", "1.0.5")
  )
}
object ScalaPlugin extends MillModule {

  override def projectDeps = Seq(Core)
  def basePath = pwd / 'scalaplugin
  override def testArgs = T{
    val mapping = Map(
      "MILL_COMPILER_BRIDGE_2_10_6"  -> bridges("2.10.6").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_11_8"  -> bridges("2.11.8").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_11_11" -> bridges("2.11.11").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_12_3"  -> bridges("2.12.3").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_12_4"  -> bridges("2.12.4").compile().classes.path,
    )
    for((k, v) <- mapping.toSeq) yield s"-D$k=$v"
  }

  override def prependShellScript =
    "#!/usr/bin/env sh\n" +
    s"""exec java ${testArgs().mkString(" ")} $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
}
