import mill._, scalalib._, publish._
import ammonite.ops._, ImplicitWd._

val binCrossScalaVersions = Seq("2.11.12", "2.12.7")
val fullCrossScalaVersions = Seq(
  "2.11.3", "2.11.4", "2.11.5", "2.11.6", "2.11.7", "2.11.8", "2.11.9", "2.11.11", "2.11.12",
  "2.12.0", "2.12.1", "2.12.2", "2.12.3", "2.12.4", "2.12.6", "2.12.7"
)

val latestAssemblies = binCrossScalaVersions.map(amm(_).assembly)

val buildVersion = "dev"

trait AmmInternalModule extends mill.scalalib.CrossSbtModule{
  def artifactName = "ammonite-" + millOuterCtx.segments.parts.last
  def testFramework = "utest.runner.Framework"
  def scalacOptions = Seq("-P:acyclic:force", "-target:jvm-1.7")
  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  trait Tests extends super.Tests{
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFrameworks = Seq("utest.runner.Framework")
    def forkArgs = Seq("-XX:MaxPermSize=2g", "-Xmx4g", "-Dfile.encoding=UTF8")
  }
  def allIvyDeps = T{transitiveIvyDeps() ++ scalaLibraryIvyDeps()}
  def externalSources = T{
    resolveDeps(allIvyDeps, sources = true)()
  }
}
trait AmmModule extends AmmInternalModule with PublishModule{
  def publishVersion = buildVersion
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "com.lihaoyi",
    url = "https://github.com/lihaoyi/Ammonite",
    licenses = Seq(License.MIT),
    versionControl = VersionControl.github("lihaoyi", "ammonite"),
    developers = Seq(
      Developer("lihaoyi", "Li Haoyi","https://github.com/lihaoyi")
    )
  )


}
trait AmmDependenciesResourceFileModule extends JavaModule{
  def crossScalaVersion: String
  def dependencyResourceFileName: String
  def resources = T.sources {

    val deps0 = T.task{compileIvyDeps() ++ transitiveIvyDeps()}()
    val (_, res) = mill.modules.Jvm.resolveDependenciesMetadata(
      repositories,
      deps0.map(resolveCoursierDependency().apply(_)),
      deps0.filter(_.force).map(resolveCoursierDependency().apply(_)),
      mapDependencies = Some(mapDependencies())
    )

    super.resources() ++
      Seq(PathRef(generateDependenciesFile(
        crossScalaVersion,
        dependencyResourceFileName,
        res.minDependencies.toSeq
      )))
  }
}

object ops extends Cross[OpsModule](binCrossScalaVersions:_*)
class OpsModule(val crossScalaVersion: String) extends AmmModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.2.0")
  def scalacOptions = super.scalacOptions().filter(!_.contains("acyclic"))
  object test extends Tests
}

object terminal extends Cross[TerminalModule](binCrossScalaVersions:_*)
class TerminalModule(val crossScalaVersion: String) extends AmmModule{
  def ivyDeps = Agg(
    ivy"com.lihaoyi::sourcecode:0.1.3",
    ivy"com.lihaoyi::fansi:0.2.4"
  )
  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:$crossScalaVersion"
  )

  object test extends Tests
}

object amm extends Cross[MainModule](fullCrossScalaVersions:_*){
  object util extends Cross[UtilModule](binCrossScalaVersions:_*)
  class UtilModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(ops())
    def ivyDeps = Agg(
      ivy"com.lihaoyi::upickle:0.6.7",
      ivy"com.lihaoyi::pprint:0.5.2",
      ivy"com.lihaoyi::fansi:0.2.4"
    )
    def compileIvyDeps = Agg(
      ivy"org.scala-lang:scala-reflect:$crossScalaVersion"
    )

  }


  object runtime extends Cross[RuntimeModule](binCrossScalaVersions:_*)
  class RuntimeModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(ops(), amm.util())
    def ivyDeps = Agg(
      ivy"io.get-coursier::coursier:1.1.0-M7",
      ivy"io.get-coursier::coursier-cache:1.1.0-M7",
      ivy"org.scalaj::scalaj-http:2.4.0"
    )

    def generatedSources = T{
      Seq(PathRef(generateConstantsFile(buildVersion)))
    }
  }

  object interp extends Cross[InterpModule](fullCrossScalaVersions:_*)
  class InterpModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(ops(), amm.util(), amm.runtime())
    def crossFullScalaVersion = true
    def ivyDeps = Agg(
      ivy"org.scala-lang:scala-compiler:$crossScalaVersion",
      ivy"org.scala-lang:scala-reflect:$crossScalaVersion",
      ivy"com.lihaoyi::scalaparse:2.0.5",
      ivy"org.javassist:javassist:3.21.0-GA"
    )
  }

  object repl extends Cross[ReplModule](fullCrossScalaVersions:_*)
  class ReplModule(val crossScalaVersion: String) extends AmmModule{
    def crossFullScalaVersion = true
    def moduleDeps = Seq(
      ops(), amm.util(),
      amm.runtime(), amm.interp(),
      terminal()
    )
    def ivyDeps = Agg(
      ivy"org.jline:jline-terminal:3.6.2",
      ivy"org.jline:jline-terminal-jna:3.6.2",
      ivy"org.jline:jline-reader:3.6.2",
      ivy"com.github.javaparser:javaparser-core:3.2.5",
      ivy"com.github.scopt::scopt:3.5.0"
    )

    object test extends Tests with AmmDependenciesResourceFileModule{
      def crossScalaVersion = ReplModule.this.crossScalaVersion
      def dependencyResourceFileName = "amm-test-dependencies.txt"
      def resources = T.sources {
        (super.resources() ++
          ReplModule.this.sources() ++
          ReplModule.this.externalSources() ++
          resolveDeps(ivyDeps, sources = true)()).distinct
      }
      def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"org.scalaz::scalaz-core:7.2.24"
      )
    }
  }
}
class MainModule(val crossScalaVersion: String) extends AmmModule with AmmDependenciesResourceFileModule{

  def artifactName = "ammonite"

  def crossFullScalaVersion = true

  def mainClass = Some("ammonite.Main")

  def moduleDeps = Seq(
    terminal(), ops(),
    amm.util(), amm.runtime(),
    amm.interp(), amm.repl()
  )
  def ivyDeps = Agg(
    ivy"com.github.scopt::scopt:3.5.0",
  )

  def runClasspath =
    super.runClasspath() ++
      ops().sources() ++
      terminal().sources() ++
      amm.util().sources() ++
      amm.runtime().sources() ++
      amm.interp().sources() ++
      amm.repl().sources() ++
      sources() ++
      externalSources()



  def prependShellScript = T{
    mill.modules.Jvm.launcherUniversalScript(
      mainClass().get,
      Agg("$0"),
      Agg("%~dpnx0"),
      // G1 Garbage Collector is awesome https://github.com/lihaoyi/Ammonite/issues/216
      Seq("-Xmx500m", "-XX:+UseG1GC")
    )
  }

  def dependencyResourceFileName = "amm-dependencies.txt"

  object test extends Tests{
    def moduleDeps = super.moduleDeps ++ Seq(amm.repl().test)
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.chuusai::shapeless:2.3.2"
    )
    // Need to duplicate this from MainModule due to Mill not properly propagating it through
    def runClasspath =
      super.runClasspath() ++
        ops().sources() ++
        terminal().sources() ++
        amm.util().sources() ++
        amm.runtime().sources() ++
        amm.interp().sources() ++
        amm.repl().sources() ++
        sources() ++
        externalSources()

  }
}

object shell extends Cross[ShellModule](fullCrossScalaVersions:_*)
class ShellModule(val crossScalaVersion: String) extends AmmModule{
  def moduleDeps = Seq(ops(), amm())
  def crossFullScalaVersion = true
  object test extends Tests{
    def moduleDeps = super.moduleDeps ++ Seq(amm.repl().test)
    def forkEnv = super.forkEnv() ++ Seq(
      "AMMONITE_SHELL" -> shell().jar().path.toString,
      "AMMONITE_ASSEMBLY" -> amm().assembly().path.toString
    )
  }
}
object integration extends Cross[IntegrationModule](fullCrossScalaVersions:_*)
class IntegrationModule(val crossScalaVersion: String) extends AmmInternalModule{
  def moduleDeps = Seq(ops(), amm())
  object test extends Tests {
    def forkEnv = super.forkEnv() ++ Seq(
      "AMMONITE_SHELL" -> shell().jar().path.toString,
      "AMMONITE_ASSEMBLY" -> amm().assembly().path.toString
    )
  }
}

object sshd extends Cross[SshdModule](fullCrossScalaVersions:_*)
class SshdModule(val crossScalaVersion: String) extends AmmModule{
  def moduleDeps = Seq(ops(), amm())
  def crossFullScalaVersion = true
  def ivyDeps = Agg(
    // sshd-core 1.3.0 requires java8
    ivy"org.apache.sshd:sshd-core:1.2.0",
    ivy"org.bouncycastle:bcprov-jdk15on:1.56",
  )
  object test extends Tests {
    def ivyDeps = super.ivyDeps() ++ Agg(
      // slf4j-nop makes sshd server use logger that writes into the void
      ivy"org.slf4j:slf4j-nop:1.7.12",
      ivy"com.jcraft:jsch:0.1.54",
      ivy"org.scalacheck::scalacheck:1.12.6"
    )
  }
}

def unitTest(scalaVersion: String = sys.env("TRAVIS_SCALA_VERSION")) = T.command{
  ops(scalaVersion).test.test()()
  terminal(scalaVersion).test.test()()
  amm.repl(scalaVersion).test.test()()
  amm(scalaVersion).test.test()()
  shell(scalaVersion).test.test()()
  sshd(scalaVersion).test.test()()
}

def integrationTest(scalaVersion: String = sys.env("TRAVIS_SCALA_VERSION")) = T.command{
  integration(scalaVersion).test.test()()
}

def generateConstantsFile(version: String = buildVersion,
                          unstableVersion: String = "<fill-me-in-in-Constants.scala>",
                          curlUrl: String = "<fill-me-in-in-Constants.scala>",
                          unstableCurlUrl: String = "<fill-me-in-in-Constants.scala>",
                          oldCurlUrls: Seq[(String, String)] = Nil,
                          oldUnstableCurlUrls: Seq[(String, String)] = Nil)
                         (implicit ctx: mill.util.Ctx.Dest)= {
  val versionTxt = s"""
    package ammonite
    object Constants{
      val version = "$version"
      val unstableVersion = "$unstableVersion"
      val curlUrl = "$curlUrl"
      val unstableCurlUrl = "$unstableCurlUrl"
      val oldCurlUrls = Seq[(String, String)](
        ${oldCurlUrls.map{case (name, value) => s""" "$name" -> "$value" """}.mkString(",\n")}
      )
      val oldUnstableCurlUrls = Seq[(String, String)](
        ${oldUnstableCurlUrls.map{case (name, value) => s""" "$name" -> "$value" """}.mkString(",\n")}
      )
    }
  """
  println("Writing Constants.scala")

  write(ctx.dest/"Constants.scala", versionTxt)
  ctx.dest/"Constants.scala"
}

def generateDependenciesFile(scalaVersion: String,
                             fileName: String,
                             deps: Seq[coursier.Dependency])
                            (implicit ctx: mill.util.Ctx.Dest) = {

  val dir = ctx.dest / "extra-resources"
  val dest = dir / fileName

  val content = deps
    .map { dep =>
      (dep.module.organization, dep.module.name, dep.version)
    }
    .sorted
    .map {
      case (org, name, ver) =>
        s"$org:$name:$ver"
    }
    .mkString("\n")

  println(s"Writing $dest")
  write(dest, content.getBytes("UTF-8"))

  dir
}

