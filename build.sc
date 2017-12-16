import ammonite.ops._
import mill._
import mill.scalaplugin._
import mill.modules.Jvm.createAssembly


object CompilerPlugin extends SbtScalaModule{
  def scalaVersion = "2.12.4"
  def basePath = pwd / 'plugin
  def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion()),
    Dep("com.lihaoyi", "sourcecode", "0.1.4")
  )
}

trait MillModule extends SbtScalaModule{ outer =>
  def scalaVersion = "2.12.4"

  def compileIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))

  def testArgs = T{ Seq.empty[String] }

  object test extends this.Tests{
    def defaultCommandName() = "forkTest"
    def forkArgs = T{ testArgs() }
    def projectDeps =
      if (this == Core.test) Seq(Core)
      else Seq(outer, Core.test)
    def ivyDeps = Seq(Dep("com.lihaoyi", "utest", "0.6.0"))
    def testFramework = "mill.UTestFramework"
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(CompilerPlugin.jar())

  }
}

object Core extends MillModule {
  def projectDeps = Seq(CompilerPlugin)

  def compileIvyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
  )

  def ivyDeps = Seq(
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
  def allSources = T{

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
  def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", crossVersion),
    Dep.Java("org.scala-sbt", "compiler-interface", "1.0.5")
  )
}

object ScalaPlugin extends MillModule {

  def projectDeps = Seq(Core)
  def basePath = pwd / 'scalaplugin

  def testArgs = T{
    val mapping = Map(
      "MILL_COMPILER_BRIDGE_2_10_6"  -> bridges("2.10.6").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_11_8"  -> bridges("2.11.8").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_11_11" -> bridges("2.11.11").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_12_3"  -> bridges("2.12.3").compile().classes.path,
      "MILL_COMPILER_BRIDGE_2_12_4"  -> bridges("2.12.4").compile().classes.path
    )
    for((k, v) <- mapping.toSeq) yield s"-D$k=$v"
  }

}

object Bin extends MillModule {

  def projectDeps = Seq(ScalaPlugin)
  def basePath = pwd / 'bin

  def releaseAssembly = T{
    createAssembly(
      (runDepClasspath().filter(_.path.ext != "pom") ++
        Seq(resources(), compile().classes)).map(_.path).filter(exists),
      prependShellScript =
        "#!/usr/bin/env sh\n" +
        s"""exec java $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
    )
  }

  def prependShellScript =
    "#!/usr/bin/env sh\n" +
    s"""exec java ${ScalaPlugin.testArgs().mkString(" ")} $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """

}
