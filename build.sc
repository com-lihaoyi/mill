import $file.shared
import java.io.File

import ammonite.ops._
import coursier.maven.MavenRepository
import mill._
import mill.scalalib._, publish._
import mill.modules.Jvm.createAssembly

object moduledefs extends ScalaModule{
  def scalaVersion = "2.12.4"
  def ivyDeps = Agg(
    ivy"org.scala-lang:scala-compiler:${scalaVersion()}",
    ivy"com.lihaoyi::sourcecode:0.1.4"
  )
}

trait MillModule extends ScalaModule{ outer =>
  def scalaVersion = "2.12.4"

  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacOptions = Seq("-P:acyclic:force")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")

  def repositories = super.repositories ++ Seq(
    MavenRepository("https://oss.sonatype.org/content/repositories/releases")
  )

  def testArgs = T{ Seq.empty[String] }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends mill.Module()(ctx0) with super.Tests{
    def defaultCommandName() = "forkTest"
    def forkArgs = T{ testArgs() }
    def moduleDeps =
      if (this == core.test) Seq(core)
      else Seq(outer, core.test)
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFramework = "mill.UTestFramework"
    def scalacPluginClasspath = super.scalacPluginClasspath() ++ Seq(moduledefs.jar())
  }
}

object core extends MillModule {
  def moduleDeps = Seq(moduledefs)

  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:${scalaVersion()}"
  )

  def ivyDeps = Agg(
    ivy"com.lihaoyi::sourcecode:0.1.4",
    ivy"com.lihaoyi::pprint:0.5.3",
    ivy"com.lihaoyi:::ammonite:1.0.3-21-05b5d32",
    ivy"org.scala-sbt::zinc:1.0.5",
    ivy"org.scala-sbt:test-interface:1.0"
  )

  def generatedSources = T {
    mkdir(T.ctx().dest)
    shared.generateCoreSources(T.ctx().dest)
    Agg(PathRef(T.ctx().dest))
  }

  val test = new Tests(implicitly)
  class Tests(ctx0: mill.define.Ctx) extends super.Tests(ctx0){
    def generatedSources = T {
      mkdir(T.ctx().dest)
      shared.generateCoreTestSources(T.ctx().dest)
      Agg(PathRef(T.ctx().dest))
    }
  }
}

val bridgeVersions = Seq("2.10.6", "2.11.8", "2.11.11", "2.12.3", "2.12.4")

object bridges extends Cross[BridgeModule](bridgeVersions:_*)
class BridgeModule(crossVersion: String) extends PublishModule {
  def publishName = "mill-bridge"
  def publishVersion = "0.1"

  def scalaVersion = crossVersion
  def allSources = T{
    Agg(PathRef(shared.downloadBridgeSource(T.ctx().dest, crossVersion)))
  }
  def ivyDeps = Agg(
    ivy"org.scala-lang:scala-compiler:$crossVersion",
    ivy"org.scala-sbt:compiler-interface:1.0.5"
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
  def moduleDeps = Seq(core)

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

object scalajslib extends MillModule {

  def moduleDeps = Seq(scalalib)

  def bridgeClasspath(runDepClasspath: Agg[PathRef], classes: PathRef) =
    (runDepClasspath ++ Agg(classes)).map(_.path).mkString(File.pathSeparator)
  def testArgs = T{
    val mapping = Map(
      "MILL_SCALAJS_BRIDGE_0_6" -> bridgeClasspath(jsbridges("0.6").runDepClasspath(), jsbridges("0.6").compile().classes),
      "MILL_SCALAJS_BRIDGE_1_0" -> bridgeClasspath(jsbridges("1.0").runDepClasspath(), jsbridges("1.0").compile().classes)
    )
    for((k, v) <- mapping.toSeq) yield s"-D$k=$v"
  }

  object jsbridges extends Cross[JsBridgeModule]("0.6", "1.0")
  class JsBridgeModule(scalajsBinary: String) extends MillModule{
    val scalajsVersion = scalajsBinary match {
      case "0.6" => "0.6.21"
      case "1.0" => "1.0.0-M2"
    }
    def ivyDeps = Agg(
      ivy"org.scala-js::scalajs-tools:$scalajsVersion"
    )
  }
}
def testRepos = T{
  Seq(
    "MILL_ACYCLIC_REPO" ->
      shared.downloadTestRepo("lihaoyi/acyclic", "bc41cd09a287e2c270271e27ccdb3066173a8598", T.ctx().dest/"acyclic"),
    "MILL_JAWN_REPO" ->
      shared.downloadTestRepo("non/jawn", "fd8dc2b41ce70269889320aeabf8614fe1e8fbcb", T.ctx().dest/"jawn"),
    "MILL_BETTERFILES_REPO" ->
      shared.downloadTestRepo("pathikrit/better-files", "e235722f91f78b8f34a41b8332d7fae3e8a64141", T.ctx().dest/"better-files"),
    "MILL_AMMONITE_REPO" ->
      shared.downloadTestRepo("lihaoyi/ammonite", "96ea548d5e3b72ab6ad4d9765e205bf6cc1c82ac", T.ctx().dest/"ammonite")
  )
}

object integration extends MillModule{
  def moduleDeps = Seq(moduledefs, scalalib, scalajslib)
  def testArgs = T{
    for((k, v) <- testRepos()) yield s"-D$k=$v"
  }
  def forkArgs() = testArgs()
}

val assemblyProjects = Seq(scalalib, scalajslib)

def assemblyClasspath = mill.define.Task.traverse(assemblyProjects)(_.runClasspath)

def publishBridges(credentials: String, gpgPassphrase: String) = T.command {
  mill.define.Task.traverse(bridges.items)(_._2.publish(credentials, gpgPassphrase))
}

def assemblyBase(classpath: Agg[Path], extraArgs: String)
                (implicit ctx: mill.util.Ctx.DestCtx) = {
  createAssembly(
    classpath,
    prependShellScript =
      "#!/usr/bin/env sh\n" +
      s"""exec java $extraArgs $$JAVA_OPTS -cp "$$0" mill.Main "$$@" """
  )
}

def devAssembly = T{
  assemblyBase(Agg.from(assemblyClasspath().flatten.map(_.path)), (scalalib.testArgs() ++ scalajslib.testArgs()).mkString(" "))
}

def releaseAssembly = T{
  assemblyBase(Agg.from(assemblyClasspath().flatten.map(_.path)), "")
}

def idea() = T.command{ mill.scalalib.GenIdea() }