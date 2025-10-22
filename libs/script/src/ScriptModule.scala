package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.daemon.Segments
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  def scriptConfig: ScriptModule.Config

  override def moduleDir = scriptConfig.simpleModulePath / os.up

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

    upickle.read[HeaderData](mill.internal.Util.parsedHeaderData(headerData))
  }
}
