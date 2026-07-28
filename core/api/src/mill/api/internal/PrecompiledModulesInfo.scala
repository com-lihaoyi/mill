package mill.api.internal

import mill.api.daemon.internal.internal

/**
 * Runtime view of the precompiled YAML modules that codegen identified as
 * "orphans" — i.e. precompiled modules with no compiled ancestor whose
 * generated `package_` would have wired them as children. The empty fallback
 * root (and `MainRootModule` generally) reads this list and appends the
 * orphans to its `moduleDirectChildren`, so they're reachable via `resolve _`
 * and `BuildCtx.rootModule.moduleInternal.modules` regardless of whether the
 * root itself is compiled or absent.
 *
 * Written by `CodeGen.generateWrappedAndSupportSources` as the classpath
 * resource `mill/precompiled-modules.json` alongside `module-deps-config.json`.
 * Bootstrap's walker (`BuildFileDiscovery.walkBuildFiles`) is the single source
 * of truth for which YAML files exist; runtime code never re-walks the
 * workspace.
 */
@internal object PrecompiledModulesInfo {

  /**
   * One entry per orphan precompiled YAML.
   *
   * `relPath` and `extendsClass` mirror what codegen otherwise embeds in
   * generated `PrecompiledModuleRef.apply` calls. Each *ModuleDeps map is
   * keyed by nested-module path inside the YAML (`""` for top-level,
   * `"test"` for `object test:` etc.), with values listing sibling refs
   * paired with their byte offset in the YAML for diagnostic reporting.
   */
  case class Entry(
      relPath: String,
      extendsClass: String,
      moduleDeps: Map[String, Seq[(String, Int)]],
      compileModuleDeps: Map[String, Seq[(String, Int)]],
      runModuleDeps: Map[String, Seq[(String, Int)]],
      bomModuleDeps: Map[String, Seq[(String, Int)]]
  ) derives upickle.default.ReadWriter

  /**
   * Reads the entries from the run classloader's resource. Returns an empty
   * sequence when the resource is absent — the no-codegen case where no
   * `mill-build` step ran (e.g. a workspace with no Mill files at all).
   */
  def fromClasspath(loader: ClassLoader): Seq[Entry] = {
    val stream = loader.getResourceAsStream("mill/precompiled-modules.json")
    if (stream == null) Nil
    else
      try {
        val content = scala.io.Source.fromInputStream(stream, "UTF-8").mkString
        upickle.default.read[Seq[Entry]](content)
      } finally stream.close()
  }
}
