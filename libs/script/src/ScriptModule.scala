package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.Discover
import mill.api.daemon.Segments
import mill.javalib.*
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  def scriptConfig: ScriptModule.Config

  override def moduleDir =
    if (os.isDir(scriptConfig.simpleModulePath)) scriptConfig.simpleModulePath
    else scriptConfig.simpleModulePath / os.up

  private[mill] def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(
      scriptConfig.simpleModulePath.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*
    )
  }
  private[mill] override def buildOverrides: Map[String, ujson.Value] =
    ScriptModule.parseHeaderData(scriptConfig.simpleModulePath).rest
}

object ScriptModule {
  case class Config(
      simpleModulePath: os.Path,
      moduleDeps: Seq[mill.Module],
      compileModuleDeps: Seq[mill.Module],
      runModuleDeps: Seq[mill.Module]
  )

  private[mill] def parseHeaderData(millSimplePath: os.Path) = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      mill.constants.Util.readBuildHeader(millSimplePath.toNIO, millSimplePath.last, true)
    }

    upickle.read[HeaderData](mill.internal.Util.parsedHeaderData(headerData), trace = true)
  }

  class JavaModule(val scriptConfig: ScriptModule.Config) extends JavaModuleBase {
    override lazy val millDiscover = Discover[this.type]
  }

  trait JavaModuleBase extends ScriptModule with mill.javalib.JavaModule
      with mill.javalib.NativeImageModule {
    override def moduleDeps = scriptConfig.moduleDeps.map(_.asInstanceOf[mill.javalib.JavaModule])

    override def sources =
      if (os.isDir(scriptConfig.simpleModulePath)) super.sources else Task.Sources()

    /** The script file itself */
    def scriptSource = Task.Source(scriptConfig.simpleModulePath)

    override def allSources =
      if (os.isDir(scriptConfig.simpleModulePath)) super.allSources()
      else sources() ++ Seq(scriptSource())

    /**
     * Whether or not to include the default `mvnDeps` that are bundled with single-file scripts.
     */
    def includDefaultScriptMvnDeps: T[Boolean] = true

    /**
     * The default `mvnDeps` for single-file scripts. For Scala scripts that means MainArgs,
     * uPickle, Requests-Scala, OS-Lib, and PPrint. For Java and Kotlin scripts it is currently
     * empty
     */
    def defaultScriptMvnDeps = Task { Seq.empty[Dep] }

    override def mandatoryMvnDeps = Task {
      super.mandatoryMvnDeps() ++
        (if (includDefaultScriptMvnDeps()) defaultScriptMvnDeps() else Seq.empty[Dep])
    }
  }

  class KotlinModule(val scriptConfig: ScriptModule.Config) extends KotlinModuleBase {
    override lazy val millDiscover = Discover[this.type]
  }

  trait KotlinModuleBase extends JavaModuleBase, mill.kotlinlib.KotlinModule {
    def kotlinVersion = "2.0.20"
  }

  class ScalaModule(val scriptConfig: ScriptModule.Config) extends ScalaModuleBase {
    override lazy val millDiscover = Discover[this.type]
    override def defaultScriptMvnDeps = Seq(
      mvn"com.lihaoyi::pprint:${mill.script.BuildInfo.pprintVersion}",
      mvn"com.lihaoyi::os-lib:${mill.script.BuildInfo.osLibVersion}",
      mvn"com.lihaoyi::upickle:${mill.script.BuildInfo.upickleVersion}",
      mvn"com.lihaoyi::requests:${mill.script.BuildInfo.requestsVersion}",
      mvn"com.lihaoyi::mainargs:${mill.script.BuildInfo.mainargsVersion}"
    )
  }

  trait ScalaModuleBase extends JavaModuleBase, mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }
}
