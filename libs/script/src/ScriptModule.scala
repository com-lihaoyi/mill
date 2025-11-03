package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.Result
import mill.api.daemon.Segments
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  def scriptConfig: ScriptModule.Config

  override def moduleDir = scriptConfig.scriptFilePath

  private[mill] def allowNestedExternalModule = true

  override def moduleSegments: Segments = {
    Segments.labels(
      scriptConfig.scriptFilePath.subRelativeTo(mill.api.BuildCtx.workspaceRoot).segments*
    )
  }

  def loadBuildOverrides() = ScriptModule.parseHeaderData(scriptConfig.scriptFilePath).get.rest
  private[mill] override val buildOverrides = loadBuildOverrides()
  private[mill] override val buildOverridePaths = Seq(scriptConfig.scriptFilePath)

  private val invalidBuildOverrides =
    buildOverrides.keySet.filter(!millDiscover.allTaskNames.contains(_))

  if (invalidBuildOverrides.nonEmpty) {
    val pretty = invalidBuildOverrides.map(pprint.Util.literalize(_)).mkString(",")
    // If we fail the module initialization, make sure we watch the `scriptFilePath` so that
    // if script file is updated `-w` will trigger. If we don't fail, we don't need to do this
    // since the `scriptSource` will watch for changes
    mill.api.BuildCtx.watch(scriptConfig.scriptFilePath)
    throw new Exception(
      s"invalid build config `${scriptConfig.scriptFilePath.relativeTo(mill.api.BuildCtx.workspaceRoot)}` key does not override any task: $pretty"
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
    def relativePath = millSimplePath.relativeTo(mill.api.BuildCtx.workspaceRoot)
    try Result.Success(upickle.read[HeaderData](mill.internal.Util.parsedHeaderData(headerData)))
    catch {
      case e: org.snakeyaml.engine.v2.exceptions.ParserException =>
        Result.Failure(s"Failed de-serializing build header in $relativePath: " + e.getMessage)
      case e: upickle.core.TraceVisitor.TraceException =>
        Result.Failure(
          s"Failed de-serializing config key ${e.jsonPath} in $relativePath: ${e.getCause.getMessage}"
        )
    }
  }
}
