package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.Result
import mill.api.daemon.Segments
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  def scriptConfig: ScriptModule.Config

  override def moduleDir = mill.api.BuildCtx.watch(scriptConfig.scriptFilePath)

  private[mill] def allowNestedExternalModule = true

  private def relativeScriptFilePath =
    scriptConfig.scriptFilePath.subRelativeTo(mill.api.BuildCtx.workspaceRoot)

  override def moduleSegments: Segments = Segments.labels(s"./$relativeScriptFilePath")

  private[mill] override def buildOverrides: Map[String, ujson.Value] =
    ScriptModule.parseHeaderData(scriptConfig.scriptFilePath).get.rest

  private val invalidBuildOverrides =
    buildOverrides.keySet.filter(!millDiscover.allTaskNames.contains(_))

  if (invalidBuildOverrides.nonEmpty) {
    val pretty = invalidBuildOverrides.map(pprint.Util.literalize(_)).mkString(",")
    throw new Exception(
      s"invalid build config `$relativeScriptFilePath` key does not override any task: $pretty"
    )
  }
}

object ScriptModule {
  case class Config(
      scriptFilePath: os.Path,
      moduleDeps: Seq[mill.Module],
      compileModuleDeps: Seq[mill.Module],
      runModuleDeps: Seq[mill.Module]
  )

  private[mill] def parseHeaderData(millSimplePath: os.Path): Result[HeaderData] = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(millSimplePath)) ""
      else mill.constants.Util.readBuildHeader(millSimplePath.toNIO, millSimplePath.last, true)
    }
    try Result.Success(upickle.read[HeaderData](mill.internal.Util.parsedHeaderData(headerData)))
    catch {
      case e: upickle.core.TraceVisitor.TraceException =>
        val relativePath = millSimplePath.relativeTo(mill.api.BuildCtx.workspaceRoot)
        Result.Failure(
          s"Failed de-serializing config key ${e.jsonPath} in $relativePath: ${e.getCause.getMessage}"
        )
    }
  }
}
