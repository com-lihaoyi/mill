import $file.shared
import java.io.File

import ammonite.ops._
import coursier.maven.MavenRepository
import mill._
import mill.scalalib._, publish._
import mill.modules.Jvm.createAssembly


object moduledefs extends SbtModule{
  def scalaVersion = "2.12.4"
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

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.Module.Ctx) extends mill.Module()(ctx0) with super.Tests{
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

  def generatedCoreSources = T{
    mkdir(T.ctx().dest)
    shared.generateCoreSources(T.ctx().dest)
    PathRef(T.ctx().dest)
  }

  def generatedSources = T { super.generatedSources() ++ Seq(generatedCoreSources()) }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.Module.Ctx) extends super.Tests(ctx0){
    def generatedCoreTestSources = T{
      mkdir(T.ctx().dest)
      shared.generateCoreTestSources(T.ctx().dest)
      PathRef(T.ctx().dest)
    }
    def generatedSources = T { super.generatedSources() ++ Seq(generatedCoreTestSources()) }
  }
}

val bridgeVersions = Seq("2.10.6", "2.11.8", "2.11.11", "2.12.3", "2.12.4")

object bridges extends Cross[BridgeModule](bridgeVersions:_*)
class BridgeModule(crossVersion: String) extends PublishModule {
  def publishName = "mill-bridge"
  def publishVersion = "0.1"

  def scalaVersion = crossVersion
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
object jsbridges extends Cross[JsBridgeModule]("0.6", "1.0")
class JsBridgeModule(scalajsBinary: String) extends MillModule{
  def basePath = pwd / 'scalajslib / s"bridge_${scalajsBinary.replace('.', '_')}"
  val scalajsVersion = scalajsBinary match {
    case "0.6" => "0.6.21"
    case "1.0" => "1.0.0-M2"
  }
  def ivyDeps = Seq(
    Dep("org.scala-js", "scalajs-tools", scalajsVersion)
  )
}

object scalajslib extends MillModule {

  def projectDeps = Seq(scalalib)

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
def testRepos = T{
  Seq(
    "MILL_ACYCLIC_REPO" ->
      shared.downloadTestRepo("lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", T.ctx().dest/"acyclic"),
    "MILL_JAWN_REPO" ->
      shared.downloadTestRepo("non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", T.ctx().dest/"jawn"),
    "MILL_BETTERFILES_REPO" ->
      shared.downloadTestRepo("pathikrit/better-files", "e235722f91f78b8f34a41b8332d7fae3e8a64141", T.ctx().dest/"better-files")
  )
}

object integration extends MillModule{
  def projectDeps = Seq(moduledefs, scalalib, scalajslib)
  def testArgs = T{
    for((k, v) <- testRepos()) yield s"-D$k=$v"
  }
  def forkArgs() = testArgs()
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
  assemblyBase(assemblyClasspath().flatten.map(_.path), (scalalib.testArgs() ++ scalajslib.testArgs()).mkString(" "))
}

def releaseAssembly = T{
  assemblyBase(assemblyClasspath().flatten.map(_.path), "")
}

def idea() = T.command{ mill.scalalib.GenIdea() }