package mill
package javalib

import mill.api.PathRef
import mill.javalib.Assembly.Rule
import mill.javalib.api.CompilationResult
import mill.javalib.publish.Dependency
import mill.util.Jvm

/**
 * A module that produces shaded (relocated) versions of third-party dependencies.
 *
 * Similar to [[BomModule]], this is typically a module with no sources of its own.
 * Other modules depend on it via `moduleDeps` to get access to the shaded classes.
 *
 * == Design Rationale ==
 *
 * This follows the standalone module pattern (like `BomModule`) rather than a mixin
 * pattern because:
 *
 * 1. Multiple modules can share the same shaded dependencies without duplication
 * 2. Cleaner separation: shading config is declared once, consumed many times
 * 3. Consistent with Mill's existing patterns for dependency management
 *
 * == Comparison with Other Build Tools ==
 *
 * '''Maven (maven-shade-plugin):'''
 * - Shading happens at build time via plugin configuration
 * - Each module that needs shading must configure the plugin
 * - No built-in way to share shading config across modules
 *
 * '''Gradle (shadow plugin):'''
 * - Similar to Maven: plugin-based, per-module configuration
 * - Can relocate packages using `relocate()` in shadowJar config
 * - Shadow configurations can be inherited but not easily shared
 *
 * '''SBT (sbt-assembly with jarjar-abrams):'''
 * - Uses `assemblyShadeRules` for relocation
 * - `sbt-shading` plugin provides `ShadedModuleID` for cleaner declaration
 * - Supports `validNamespaces` to ensure all deps are properly relocated
 *
 * '''Mill ShadingModule (this implementation):'''
 * - Standalone module pattern: define once, depend via `moduleDeps`
 * - Uses jarjar-abrams under the hood (same as assembly)
 * - Shaded JAR is produced and can be published independently
 * - Downstream modules automatically use relocated package names
 *
 * == Example Usage ==
 *
 * {{{
 * // Define a shading module
 * object gsonShaded extends ShadingModule {
 *   def shadedMvnDeps = Seq(mvn"com.google.code.gson:gson:2.10")
 *   def shadeRelocations = Seq(
 *     "com.google.gson.**" -> "shaded.gson.@1"
 *   )
 * }
 *
 * // Use it from other modules
 * object myApp extends JavaModule {
 *   def moduleDeps = Seq(gsonShaded)
 *   // Now use: import shaded.gson.Gson
 * }
 * }}}
 *
 * == Publishing ==
 *
 * When publishing a `ShadingModule`, the published JAR contains the relocated classes.
 * The original dependencies are NOT declared in the published POM, since they're bundled.
 *
 * To publish, mix in `ShadingPublishModule`:
 * {{{
 * object gsonShaded extends ShadingModule with ShadingPublishModule {
 *   def shadedMvnDeps = Seq(mvn"com.google.code.gson:gson:2.10")
 *   def shadeRelocations = Seq("com.google.gson.**" -> "shaded.gson.@1")
 *
 *   def publishVersion = "1.0.0"
 *   def pomSettings = PomSettings(...)
 * }
 * }}}
 */
trait ShadingModule extends JavaModule {

  /**
   * Dependencies to shade (relocate) into this module's JAR.
   * These will be downloaded, their bytecode relocated according to `shadeRelocations`,
   * and bundled into the output JAR.
   */
  def shadedMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Relocation rules for shading. Each entry maps a package pattern to its relocated name.
   *
   * Uses jarjar-abrams syntax:
   * - `**` matches any sequence of packages
   * - `@1`, `@2`, etc. refer to captured wildcards
   *
   * Example:
   * {{{
   * def shadeRelocations = Seq(
   *   "com.google.gson.**" -> "shaded.gson.@1",
   *   "com.google.common.**" -> "shaded.guava.@1"
   * )
   * }}}
   */
  def shadeRelocations: T[Seq[(String, String)]] = Task { Seq.empty[(String, String)] }

  /**
   * Resolved classpath of dependencies to be shaded.
   */
  def shadedDepsClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(shadedMvnDeps())
  }

  /**
   * Creates a JAR containing the shaded (relocated) classes from `shadedMvnDeps`.
   */
  def shadedJar: T[PathRef] = Task {
    val relocations = shadeRelocations()
    if (relocations.isEmpty && shadedMvnDeps().nonEmpty) {
      throw new Exception("ShadingModule has shadedMvnDeps but no shadeRelocations defined")
    }

    val inputPaths = shadedDepsClasspath().map(_.path)
    val destJar = Task.dest / "shaded.jar"

    // Use jarjar-abrams for relocation (same as assembly)
    val rules = relocations.map { case (from, to) => Rule.Relocate(from, to) }

    val assembly = Assembly.create(
      destJar = destJar,
      inputPaths = inputPaths,
      manifest = manifest(),
      assemblyRules = rules ++ Assembly.defaultRules,
      shader = AssemblyModule.jarjarabramsWorker()
    )

    assembly.pathRef
  }

  // Override to include the shaded JAR in local classpath
  abstract override def localClasspath: T[Seq[PathRef]] = Task {
    val base = super.localClasspath()
    if (shadedMvnDeps().nonEmpty) {
      base :+ shadedJar()
    } else {
      base
    }
  }

  // ShadingModule typically has no sources (like BomModule)
  abstract override def compile: T[CompilationResult] = Task {
    val sources = allSourceFiles()
    if (sources.nonEmpty) {
      throw new Exception("A ShadingModule typically has no sources of its own")
    }
    CompilationResult(Task.dest / "zinc", PathRef(Task.dest / "classes"))
  }

  abstract override def resources: T[Seq[PathRef]] = Task {
    val value = super.resources()
    if (value.nonEmpty) {
      throw new Exception("A ShadingModule typically has no resources of its own")
    }
    Seq.empty[PathRef]
  }

  // Override jar to return the shaded JAR
  abstract override def jar: T[PathRef] = Task {
    if (shadedMvnDeps().nonEmpty) {
      shadedJar()
    } else {
      // Empty jar if no deps to shade
      PathRef(Jvm.createJar(Task.dest / "out.jar", Seq.empty[os.Path]))
    }
  }
}

/**
 * Mixin for publishing a ShadingModule.
 *
 * When publishing, the shaded dependencies are bundled in the JAR but NOT
 * listed in the published POM dependencies, since they're internalized.
 */
trait ShadingPublishModule extends ShadingModule with PublishModule {

  /**
   * Override publishXmlDeps to exclude the shaded dependencies.
   * The shaded classes are bundled in the JAR, so they shouldn't be
   * declared as dependencies in the POM.
   */
  abstract override def publishXmlDeps: T[Seq[Dependency]] = Task {
    val shadedCoords = shadedMvnDeps().map(d => (d.dep.module.organization.value, d.dep.module.name.value))
    super.publishXmlDeps().filterNot { dep =>
      shadedCoords.contains((dep.artifact.group, dep.artifact.id))
    }
  }
}
