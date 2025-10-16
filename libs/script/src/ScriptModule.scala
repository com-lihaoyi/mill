package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.Discover
import mill.api.daemon.Segments
import mill.javalib.*

trait ScriptModule extends ExternalModule {
  def simpleConf: ScriptModule.Config

  override def moduleDir =
    if (os.isDir(simpleConf.simpleModulePath)) simpleConf.simpleModulePath
    else simpleConf.simpleModulePath / os.up

  private[mill] def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(
      simpleConf.simpleModulePath.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*
    )
  }
  override def buildOverrides: Map[String, ujson.Value] =
    ScriptModule.parseHeaderData(simpleConf.simpleModulePath)
}

object ScriptModule {
  case class Config(simpleModulePath: os.Path, moduleDeps: Seq[mill.Module])
  private[mill] def parseHeaderData(millSimplePath: os.Path) = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      mill.constants.Util.readBuildHeader(millSimplePath.toNIO, millSimplePath.last, true)
    }
    upickle.read[Map[String, ujson.Value]](mill.internal.Util.parsedHeaderData(headerData))
  }

  class JavaModule(val simpleConf: ScriptModule.Config) extends JavaModuleBase {
    override lazy val millDiscover = Discover[this.type]
  }

  trait JavaModuleBase extends ScriptModule with mill.javalib.JavaModule
      with mill.javalib.NativeImageModule {
    override def moduleDeps = simpleConf.moduleDeps.map(_.asInstanceOf[mill.javalib.JavaModule])

    override def sources =
      if (os.isDir(simpleConf.simpleModulePath)) super.sources else Task.Sources()

    def scriptSource = Task.Source(simpleConf.simpleModulePath)

    override def allSources =
      if (os.isDir(simpleConf.simpleModulePath)) super.allSources()
      else sources() ++ Seq(scriptSource())

    def includDefaultScriptMvnDeps: T[Boolean] = true
    def defaultScriptMvnDeps = Task{ Seq.empty[Dep] }

    override def mandatoryMvnDeps = Task{
      super.mandatoryMvnDeps() ++
        (if (includDefaultScriptMvnDeps()) defaultScriptMvnDeps() else Seq.empty[Dep])
    }
  }

  class KotlinModule(val simpleConf: ScriptModule.Config) extends KotlinModuleBase {
    override lazy val millDiscover = Discover[this.type]
  }

  trait KotlinModuleBase extends JavaModuleBase, mill.kotlinlib.KotlinModule {
    def kotlinVersion = "2.0.20"
  }

  class ScalaModule(val simpleConf: ScriptModule.Config) extends ScalaModuleBase {
    override lazy val millDiscover = Discover[this.type]
    override def defaultScriptMvnDeps = Seq(
      mvn"com.lihaoyi::pprint:${mill.script.BuildInfo.pprintVersion}",
      mvn"com.lihaoyi::os-lib:${mill.script.BuildInfo.osLibVersion}",
      mvn"com.lihaoyi::upickle:${mill.script.BuildInfo.upickleVersion}",
      mvn"com.lihaoyi::requests:${mill.script.BuildInfo.requestsVersion}",
      mvn"com.lihaoyi::mainargs:${mill.script.BuildInfo.mainargsVersion}",
    )
  }

  trait ScalaModuleBase extends JavaModuleBase, mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }
}
