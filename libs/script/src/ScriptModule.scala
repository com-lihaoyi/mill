package mill.script
import mill.*
import mill.api.{ExternalModule, ModuleCtx}
import mill.api.daemon.Segments
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  override def moduleCtx: ModuleCtx = super.moduleCtx
    .withFileName(scriptConfig.scriptFile.toString)
    .withLineNum(0)
  def scriptConfig: ScriptModule.Config

  override def moduleDir = scriptConfig.scriptFile

  private[mill] def allowNestedExternalModule = true

  private def relativeScriptFilePath =
    scriptConfig.scriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot)

  override def moduleSegments: Segments = Segments.labels(relativeScriptFilePath.toString + ":")

  private[mill] override def moduleDynamicBuildOverrides = scriptConfig
    .headerData
    .rest
    .map { case (k, v) => ((moduleSegments ++ mill.api.Segment.Label(k)).render, v) }
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
