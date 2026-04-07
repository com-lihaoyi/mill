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
trait PrecompiledModule extends ExternalModule {
  override def moduleCtx: ModuleCtx = super.moduleCtx
    .withFileName(scriptConfig.scriptFile.toString)
    .withLineNum(0)
  def scriptConfig: ScriptModule.Config

  override def moduleDir = scriptConfig.scriptFile / os.up

  private[mill] def allowNestedExternalModule = true

  private def relativeScriptFilePath =
    scriptConfig.scriptFile.subRelativeTo(mill.api.BuildCtx.workspaceRoot)

  override def moduleSegments: Segments = Segments.labels(relativeScriptFilePath.toString + ":")

  private[mill] override def moduleDynamicBuildOverrides = scriptConfig
    .headerData
    .rest
    .map { case (k, v) =>
      val newKey = (moduleSegments ++ mill.api.Segment.Label(k.value)).render
      val (actualValue, append) = internal.Appendable.unwrapAppendMarker(v)
      (
        newKey,
        internal.Located(scriptConfig.scriptFile, k.index, internal.Appendable(actualValue, append))
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
