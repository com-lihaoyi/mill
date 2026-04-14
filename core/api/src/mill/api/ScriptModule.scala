package mill.api

import mill.*
import mill.api.{ExternalModule, ModuleCtx}
import mill.api.daemon.Segments
import mill.api.internal.HeaderData

/**
 * Base trait for modules instantiated from YAML configuration files.
 * Sets `moduleDir` to the directory containing the config file, suitable for
 * directory-based modules with standard `src/` layouts.
 *
 * For single-file script modules (`.scala`, `.java`, `.kt`), use [[ScriptModule]]
 * which overrides `moduleDir` to point to the script file itself.
 */
@experimental
trait PrecompiledModule extends ExternalModule with ConfigModuleDepsModule {
  override def moduleCtx: ModuleCtx = super.moduleCtx
    .withFileName(scriptConfig.scriptFile.toString)
    .withLineNum(0)
  def scriptConfig: ScriptModule.Config

  private[mill] override def configModuleDeps = scriptConfig.moduleDeps
  private[mill] override def configCompileModuleDeps = scriptConfig.compileModuleDeps
  private[mill] override def configRunModuleDeps = scriptConfig.runModuleDeps
  private[mill] override def configBomModuleDeps = scriptConfig.bomModuleDeps

  override def moduleDir = scriptConfig.scriptFile / os.up

  private[mill] def allowNestedExternalModule = true

  private[api] def relativeScriptFilePath =
    scriptConfig.scriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot)

  // For directory-based YAML modules (package.mill.yaml), use the directory path
  // so that segments render as e.g. "foo.test" instead of "foo/package.mill.yaml:test"
  override def moduleSegments: Segments = {
    val dirPath = relativeScriptFilePath / os.up
    Segments(dirPath.segments.map(s => mill.api.Segment.Label(s)).toVector)
  }

  private[mill] override def moduleDynamicBuildOverrides =
    flattenHeaderDataRest(moduleSegments, scriptConfig.headerData)

  private def flattenHeaderDataRest(
      segments: Segments,
      data: HeaderData
  ): Map[String, internal.Located[internal.Appendable[upickle.core.BufferedValue]]] = {
    HeaderData.processRest(scriptConfig.scriptFile, data)(
      onProperty = { (locatedKey, v) =>
        val newKey = (segments ++ mill.api.Segment.Label(locatedKey.value)).render
        val (actualValue, append) = internal.Appendable.unwrapAppendMarker(v)
        Map(newKey -> internal.Located(
          scriptConfig.scriptFile,
          locatedKey.index,
          internal.Appendable(actualValue, append)
        ))
      },
      onNestedObject = { (_, name, nestedData) =>
        val nestedSegments = segments ++ mill.api.Segment.Label(name)
        flattenHeaderDataRest(nestedSegments, nestedData)
      }
    ).foldLeft(Map.empty[
      String,
      internal.Located[internal.Appendable[upickle.core.BufferedValue]]
    ])(
      _ ++ _
    )
  }
}

@experimental
object PrecompiledModule {
  export ScriptModule.Config
}

/**
 * Trait for single-file script modules (`.scala`, `.java`, `.kt`).
 * Overrides `moduleDir` to point to the script file itself rather than
 * its parent directory.
 */
@experimental
trait ScriptModule extends PrecompiledModule {
  override def moduleDir = scriptConfig.scriptFile
  // For single-file scripts, use the file path with ':' suffix for script module convention
  override def moduleSegments: Segments = Segments.labels(relativeScriptFilePath.toString + ":")
}

@experimental
object ScriptModule {
  case class Config(
      scriptFile: os.Path,
      moduleDeps: Map[String, Seq[mill.api.Module]],
      compileModuleDeps: Map[String, Seq[mill.api.Module]],
      runModuleDeps: Map[String, Seq[mill.api.Module]],
      bomModuleDeps: Map[String, Seq[mill.api.Module]],
      headerData: HeaderData
  )

}
