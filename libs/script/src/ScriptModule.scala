package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.daemon.Segments
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  def scriptConfig: ScriptModule.Config

  override def moduleDir = scriptConfig.scriptFile

  private[mill] def allowNestedExternalModule = true

  private def relativeScriptFilePath =
    scriptConfig.scriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot)

  override def moduleSegments: Segments = Segments.labels(s"./$relativeScriptFilePath")

  private[mill] override def moduleDynamicBuildOverrides = scriptConfig
    .headerData
    .rest
    .map { case (k, v) => ((moduleSegments ++ mill.api.Segment.Label(k)).render, v) }

//  mill.internal.Util.validateBuildHeaderKeys(
//    moduleDynamicBuildOverrides.keySet,
//    millDiscover.allTaskNames,
//    relativeScriptFilePath
//  )
}

object ScriptModule {
  case class Config(
      scriptFile: os.Path,
      moduleDeps: Seq[mill.Module],
      compileModuleDeps: Seq[mill.Module],
      runModuleDeps: Seq[mill.Module],
      headerData: HeaderData
  )

}
