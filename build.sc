import $file.shared
import java.io.File

import ammonite.ops._
import coursier.maven.MavenRepository
import mill._
import mill.scalalib._, publish._
import mill.modules.Jvm.createAssembly


object moduledefs extends SbtModule{
  def scalaVersion = "2.12.4"
  def basePath = pwd / 'moduledefs
  def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", scalaVersion()),
    Dep("com.lihaoyi", "sourcecode", "0.1.4")
  )
}

trait MillModule extends SbtModule{ outer =>
  def scalaVersion = "2.12.4"

  def compileIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Seq(Dep("com.lihaoyi", "acyclic", "0.1.7"))

  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  def testArgs = T{ Seq.empty[String] }

  val test = new Tests
  class Tests extends super.Tests{
    def defaultCommandName() = "forkTest"
    def forkArgs = T{ testArgs() }
    def projectDeps =
      if (this == core.test) Seq(core)
      else Seq(outer, core.test)
    def ivyDeps = Seq(Dep("com.lihaoyi", "utest", "0.6.0"))
    def testFramework = "mill.UTestFramework"
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(moduledefs.jar())
  }
}

object core extends MillModule {
  def projectDeps = Seq(moduledefs)

  def compileIvyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-reflect", scalaVersion())
  )

  def ivyDeps = Seq(
    Dep("com.lihaoyi", "sourcecode", "0.1.4"),
    Dep("com.lihaoyi", "pprint", "0.5.3"),
    Dep.Point("com.lihaoyi", "ammonite", "1.0.3-21-05b5d32"),
    Dep("com.typesafe.play", "play-json", "2.6.6"),
    Dep("org.scala-sbt", "zinc", "1.0.5"),
    Dep.Java("org.scala-sbt", "test-interface", "1.0")
  )

  def basePath = pwd / 'core

  def generatedSources = T{
    mkdir(T.ctx().dest)
    shared.generateSources(T.ctx().dest)
    PathRef(T.ctx().dest)
  }

  def allSources = super.allSources() ++ Seq(generatedSources())
  val test = new Tests
  class Tests extends super.Tests{
    def generatedSources = T{
      mkdir(T.ctx().dest)
      shared.generateTests(T.ctx().dest)
      PathRef(T.ctx().dest)

    }
    def allSources = super.allSources() ++ Seq(generatedSources())
  }

  val cross =
    for(jarLabel <- mill.define.Cross("jarA", "jarB", "jarC"))
    yield new mill.Module{
      def printIt() = T.command{
        println("PRINTING IT: " + jarLabel)
      }
      def jar = T{
        val dest = T.ctx().dest
        ammonite.ops.mkdir(dest/ammonite.ops.up)
        ammonite.ops.cp(core.jar().path, dest)

        PathRef(dest)
      }
    }
}

val bridgeVersions = Seq("2.10.6", "2.11.8", "2.11.11", "2.12.3", "2.12.4")

val bridges = for{
  crossVersion <- mill.define.Cross(bridgeVersions:_*)
} yield new PublishModule {
  def publishName = "mill-bridge"
  def publishVersion = "0.1"

  def basePath = pwd / 'bridge
  def scalaVersion = crossVersion
  def sources = T.source {
    val path = basePath / 'src
    mkdir(path)
    PathRef(path)
  }
  def allSources = T{
    Seq(PathRef(shared.downloadBridgeSource(T.ctx().dest, crossVersion)))
  }
  def ivyDeps = Seq(
    Dep.Java("org.scala-lang", "scala-compiler", crossVersion),
    Dep.Java("org.scala-sbt", "compiler-interface", "1.0.5")
  )

  def publishWithFullScalaVersion = true

  def pomSettings = PomSettings(
    organization = "com.lihaoyi",
    description = artifactId(),
    developers = Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi/mill")),
    licenses = Seq(License("MIT License", "https://spdx.org/licenses/MIT.html#licenseText")),
    scm = SCM("https://github.com/lihaoyi/mill", "scm:git:https://github.com/lihaoyi/mill.git"),
    url = "https://github.com/lihaoyi/mill"
  )
}

object scalalib extends MillModule {
  def projectDeps = Seq(core)
  def basePath = pwd / 'scalalib

  def bridgeCompiles = mill.define.Task.traverse(bridges.items)(_._2.compile)
  def testArgs = T{
    val bridgeVersions = bridges.items.map(_._1.head.toString)

    for((version, compile) <- bridgeVersions.zip(bridgeCompiles()))
    yield {
      val underscored = version.replace('.', '_')
      val key = s"MILL_COMPILER_BRIDGE_$underscored"
      val value = compile.classes.path
      s"-D$key=$value"
    }
  }
}
val jsbridges = for{
  scalajsBinary <- mill.define.Cross("0.6", "1.0")
} yield new MillModule{
  def basePath = pwd / 'scalajslib / s"bridge_${scalajsBinary.replace('.', '_')}"
  val scalajsVersion = scalajsBinary match {
    case "0.6" => "0.6.21"
    case "1.0" => "1.0.0-M2"
  }
  override def ivyDeps = Seq(
    Dep("org.scala-js", "scalajs-tools", scalajsVersion)
  )
}

object scalajslib extends MillModule {

  def projectDeps = Seq(scalalib)
  def basePath = pwd / 'scalajslib

  def bridgeClasspath(runDepClasspath: Seq[PathRef], classes: PathRef) =
    (runDepClasspath :+ classes).map(_.path).mkString(File.pathSeparator)
  def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_BRIDGE_0_6" -> bridgeClasspath(jsbridges("0.6").runDepClasspath(), jsbridges("0.6").compile().classes),
      "MILL_SCALAJS_BRIDGE_1_0" -> bridgeClasspath(jsbridges("1.0").runDepClasspath(), jsbridges("1.0").compile().classes)
    )
    for((k, v) <- mapping.toSeq) yield s"-D$k=$v"
  }

}

val assemblyProjects = Seq(scalalib, scalajslib)

def assemblyClasspath = mill.define.Task.traverse(assemblyProjects)(_.assemblyClasspath)

def publishBridges(credentials: String, gpgPassphrase: String) = T.command {
  mill.define.Task.traverse(bridges.items)(_._2.publish(credentials, gpgPassphrase))
}

def assemblyBase(classpath: Seq[Path], extraArgs: String)
                (implicit ctx: mill.util.Ctx.DestCtx) = {
  createAssembly(
    classpath,
    prependShellScript =
      "#!/usr/bin/env sh\n" +
      s"""exec java $extraArgs $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
  )
}

def devAssembly = T{
  assemblyBase(assemblyClasspath().flatten, (scalalib.testArgs() ++ scalajslib.testArgs()).mkString(" "))
}

def releaseAssembly = T{
  assemblyBase(assemblyClasspath().flatten, "")
}

def idea() = T.command{ mill.scalalib.GenIdea() }