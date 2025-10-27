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

  private val invalidBuildOverrides =
    buildOverrides.keySet.filter(!millDiscover.allTaskNames.contains(_))

  if (invalidBuildOverrides.nonEmpty) {
    val pretty = invalidBuildOverrides.map(pprint.Util.literalize(_)).mkString(",")
    throw new Exception("invalid build config does not override any task: " + pretty)
  }
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
      // If the module file got deleted, handle that gracefully
      if (!os.exists(millSimplePath)) ""
      else mill.constants.Util.readBuildHeader(millSimplePath.toNIO, millSimplePath.last, true)
    }

    upickle.read[HeaderData](mill.internal.Util.parsedHeaderData(headerData))
  }
}
