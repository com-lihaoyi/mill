package mill.javalib

import mill.api.{PathRef, Task}
import mill.api.Task.Simple as T
import mill.javalib.publish.Artifact
import mill.util.{JarManifest, Jvm}

/**
 * A module trait that provides support for shading (relocating) third-party dependencies.
 *
 * Shading allows you to include dependencies directly in your JAR with relocated package names,
 * avoiding version conflicts when your library is used alongside other versions of the same
 * dependencies.
 *
 * Example usage:
 * {{{
 * object myModule extends JavaModule with ShadingModule {
 *   def shadedMvnDeps = Seq(
 *     mvn"com.google.guava:guava:32.1.3-jre"
 *   )
 *
 *   def shadeRelocations = Seq(
 *     ("com.google.**", "shaded.com.google.@1")
 *   )
 * }
 * }}}
 *
 * The shaded dependencies will be:
 * - Included in the module's JAR with relocated package names
 * - Available on the runtime classpath with relocated names
 * - Excluded from the published POM/ivy.xml when combined with [[ShadingPublishModule]]
 */
trait ShadingModule extends JavaModule {

  /**
   * Dependencies to shade (relocate packages and bundle into output).
   *
   * These dependencies and their transitives will be:
   * - Resolved and included in [[shadedJar]]
   * - Relocated according to [[shadeRelocations]]
   * - Removed from [[runClasspath]] (replaced by shaded JAR)
   * - Excluded from assembly's upstream ivy classpath
   */
  def shadedMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Package relocation rules for shaded dependencies.
   *
   * Each rule is a tuple of (fromPattern, toPattern) where:
   * - `fromPattern` uses `**` to match any characters including `.` (e.g., `com.google.**`)
   * - `fromPattern` uses `*` to match a single package component (excluding `.`)
   * - `toPattern` uses `@1`, `@2`, etc. as backreferences to matched wildcards (left to right)
   *
   * Example:
   * {{{
   * def shadeRelocations = Seq(
   *   ("com.google.**", "shaded.com.google.@1"),
   *   ("org.apache.commons.**", "myapp.shaded.commons.@1")
   * )
   * }}}
   *
   * Note: If [[shadedMvnDeps]] is non-empty but this is empty, dependencies will be
   * bundled without relocation. A warning will be logged in this case since it rarely
   * makes sense to bundle without relocating.
   */
  def shadeRelocations: T[Seq[(String, String)]] = Task { Seq.empty[(String, String)] }

  /**
   * Resolved shaded dependencies including all transitives.
   *
   * This resolves [[shadedMvnDeps]] with their full transitive dependency tree.
   */
  def resolvedShadedDeps: T[Seq[PathRef]] = Task {
    if (shadedMvnDeps().isEmpty) Seq.empty[PathRef]
    else defaultResolver().classpath(shadedMvnDeps())
  }

  /**
   * Artifact coordinates of all shaded dependencies (including transitives).
   *
   * Used by [[ShadingPublishModule]] to filter these from published metadata.
   */
  def shadedArtifacts: T[Set[Artifact]] = Task {
    if (shadedMvnDeps().isEmpty) Set.empty[Artifact]
    else {
      val resolution = defaultResolver().resolution(shadedMvnDeps())
      resolution.dependencySet.set.map { dep =>
        Artifact(
          group = dep.module.organization.value,
          id = dep.module.name.value,
          version = dep.version
        )
      }.toSet
    }
  }

  /**
   * The shaded JAR containing all shaded dependencies with relocated packages.
   *
   * This JAR includes:
   * - All classes from [[resolvedShadedDeps]]
   * - Package names rewritten according to [[shadeRelocations]] (if any)
   * - Resources from shaded dependencies
   *
   * If [[shadeRelocations]] is empty, dependencies are bundled without relocation.
   */
  def shadedJar: T[PathRef] = Task {
    if (shadedMvnDeps().isEmpty || resolvedShadedDeps().isEmpty) {
      val jar = Task.dest / "shaded.jar"
      Jvm.createJar(jar, Seq.empty, JarManifest.Empty)
      PathRef(jar)
    } else {
      val relocations = shadeRelocations()
      if (relocations.isEmpty) {
        Task.log.error(
          "shadedMvnDeps is non-empty but shadeRelocations is empty. " +
            "Dependencies will be bundled without package relocation, which may cause classpath conflicts. " +
            "Consider adding relocation rules, e.g.: def shadeRelocations = Seq((\"com.google.**\", \"shaded.google.@1\"))"
        )
      }

      val relocateRules = relocations.map { case (from, to) =>
        Assembly.Rule.Relocate(from, to)
      }

      val created = Assembly.create(
        destJar = Task.dest / "shaded.jar",
        inputPaths = resolvedShadedDeps().map(_.path),
        manifest = JarManifest.Empty,
        assemblyRules = Assembly.defaultRules ++ relocateRules,
        shader = AssemblyModule.jarjarabramsWorker()
      )
      created.pathRef
    }
  }

  /**
   * Compile classpath with shaded dependencies replaced by the shaded JAR.
   *
   * Source code must use the relocated package names in imports
   * (e.g., `import shaded.gson.Gson` instead of `import com.google.gson.Gson`)
   * since the original packages will not exist at runtime.
   */
  override def compileClasspath: T[Seq[PathRef]] = Task {
    if (shadedMvnDeps().isEmpty) super.compileClasspath()
    else {
      val shadedPaths = resolvedShadedDeps().map(_.path).toSet
      val filteredClasspath = super.compileClasspath().filterNot(p => shadedPaths.contains(p.path))
      filteredClasspath ++ Seq(shadedJar())
    }
  }

  /**
   * Runtime classpath with shaded dependencies replaced by the shaded JAR.
   *
   * The original (unshaded) dependency JARs are removed from the classpath
   * and replaced with [[shadedJar]] containing the relocated classes.
   */
  override def runClasspath: T[Seq[PathRef]] = Task {
    if (shadedMvnDeps().isEmpty) super.runClasspath()
    else {
      val shadedPaths = resolvedShadedDeps().map(_.path).toSet
      val filteredClasspath = super.runClasspath().filterNot(p => shadedPaths.contains(p.path))
      filteredClasspath ++ Seq(shadedJar())
    }
  }

  /**
   * Upstream ivy assembly classpath excluding shaded dependencies.
   *
   * Shaded dependencies are excluded because they will be included
   * via [[shadedJar]] in the local classpath instead.
   */
  override def upstreamIvyAssemblyClasspath: T[Seq[PathRef]] = Task {
    if (shadedMvnDeps().isEmpty) super.upstreamIvyAssemblyClasspath()
    else {
      val shadedPaths = resolvedShadedDeps().map(_.path).toSet
      super.upstreamIvyAssemblyClasspath().filterNot(p => shadedPaths.contains(p.path))
    }
  }

  /**
   * Local classpath including the shaded JAR.
   *
   * This ensures shaded classes are included in assemblies and the module's JAR.
   */
  override def localClasspath: T[Seq[PathRef]] = Task {
    if (shadedMvnDeps().isEmpty) super.localClasspath()
    else super.localClasspath() ++ Seq(shadedJar())
  }

  /**
   * The module's JAR with shaded classes properly merged.
   *
   * Overrides the default `jar` to use Assembly.create which properly extracts
   * and merges JAR contents, rather than Jvm.createJar which treats JARs as opaque files.
   */
  override def jar: T[PathRef] = Task {
    if (shadedMvnDeps().isEmpty) {
      // No shading, use default behavior
      val jarPath = Task.dest / "out.jar"
      Jvm.createJar(jarPath, super.localClasspath().map(_.path).filter(os.exists), manifest())
      PathRef(jarPath)
    } else {
      // Use Assembly.create to properly merge shaded JAR contents
      val created = Assembly.create(
        destJar = Task.dest / "out.jar",
        inputPaths = super.localClasspath().map(_.path).filter(os.exists) ++ Seq(shadedJar().path),
        manifest = manifest(),
        assemblyRules = Assembly.defaultRules,
        shader = AssemblyModule.jarjarabramsWorker()
      )
      created.pathRef
    }
  }
}
