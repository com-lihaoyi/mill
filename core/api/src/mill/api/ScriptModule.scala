package mill.api

import mill.*
import mill.api.{ExternalModule, ModuleCtx}
import mill.api.daemon.Segments
import mill.api.internal.HeaderData
@experimental
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
    .map { case (k, v) =>
      import upickle.core.BufferedValue
      val newKey = (moduleSegments ++ mill.api.Segment.Label(k.value)).render
      // Extract append flag from marker object if present
      val (actualValue, append) = v match {
        case obj: BufferedValue.Obj =>
          val kvMap = obj.value0.collect { case (BufferedValue.Str(k, _), v) =>
            k.toString -> v
          }.toMap
          (kvMap.get("__mill_append__"), kvMap.get("__mill_values__")) match {
            case (Some(BufferedValue.True(_)), Some(values)) => (values, true)
            case _ => (v, false)
          }
        case _ => (v, false)
      }
      (newKey, internal.AppendLocated(internal.Located(scriptConfig.scriptFile, k.index, actualValue), append))
    }
}
@experimental
object ScriptModule {
  case class Config(
      scriptFile: os.Path,
      moduleDeps: Seq[mill.api.Module],
      compileModuleDeps: Seq[mill.api.Module],
      runModuleDeps: Seq[mill.api.Module],
      headerData: HeaderData
  )

}
