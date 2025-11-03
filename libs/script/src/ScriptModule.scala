package mill.script
import mill.*
import mill.api.ExternalModule
import mill.api.Result
import mill.api.daemon.Segments
import mill.api.ModuleCtx.HeaderData
trait ScriptModule extends ExternalModule {
  def scriptConfig: ScriptModule.Config

  override def moduleDir = scriptConfig.scriptFile

  private[mill] def allowNestedExternalModule = true

  private def relativeScriptFilePath =
    scriptConfig.scriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot)

  override def moduleSegments: Segments = Segments.labels(s"./$relativeScriptFilePath")

  private[mill] override def buildOverrides = scriptConfig.headerData.rest

  mill.internal.Util.validateBuildHeaderKeys(
    buildOverrides.keySet,
    millDiscover.allTaskNames,
    relativeScriptFilePath
  )
}

object ScriptModule {
  case class Config(
      scriptFile: os.Path,
      moduleDeps: Seq[mill.Module],
      compileModuleDeps: Seq[mill.Module],
      runModuleDeps: Seq[mill.Module],
      headerData: HeaderData
  )

  private[mill] def parseHeaderData(scriptFile: os.Path): Result[HeaderData] = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(scriptFile)) ""
      else mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
    }

    def relativePath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)
    try Result.Success(upickle.read[HeaderData](mill.internal.Util.parseYaml(headerData)))
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
