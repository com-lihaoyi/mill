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

  // Validate that all nested config dep keys (e.g. "test") correspond to actual
  // nested objects in this module class. This catches typos like `object typo:` in YAML
  // that would otherwise silently have their moduleDeps ignored.
  locally {
    val allConfigKeys = (
      scriptConfig.moduleDeps.keySet ++
        scriptConfig.compileModuleDeps.keySet ++
        scriptConfig.runModuleDeps.keySet ++
        scriptConfig.bomModuleDeps.keySet
    ).filter(_.nonEmpty) // skip root key ""

    for (key <- allConfigKeys) {
      // Only validate the first segment (direct child); nested paths like "test.sub"
      // will be validated when the child module does its own check
      val directChild = key.split("\\.").head
      val hasMethod =
        try { getClass.getMethod(directChild); true }
        catch { case _: NoSuchMethodException => false }
      if (!hasMethod) {
        throw new mill.api.daemon.Result.Exception(
          s"Config key ${pprint.Util.literalize("object " + directChild)} in " +
            s"${scriptConfig.scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)} " +
            s"does not match any nested module in ${getClass.getName}",
          Some(mill.api.daemon.Result.Failure(
            s"Config key ${pprint.Util.literalize("object " + directChild)} " +
              s"does not match any nested module in ${getClass.getName}",
            scriptConfig.scriptFile.toNIO,
            0
          ))
        )
      }
    }
  }

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
