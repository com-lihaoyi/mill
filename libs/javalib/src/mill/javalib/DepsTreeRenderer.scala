package mill.javalib

import coursier.core.{Dependency, Module, Resolution}
import coursier.graph.DependencyTree
import coursier.util.{Print, Tree}
import coursier.version.{Version, VersionConstraint}

/**
 * Renders the non-inverted dependency tree printed by `showMvnDepsTree`.
 *
 * This mirrors `coursier.util.Print.dependencyTree0(reverse = false)`, except that repeated
 * nodes are elided with a `(*)` marker, the same way coursier already renders the inverted
 * tree.
 *
 * Coursier's non-inverted renderer walks every distinct *path* through the dependency graph
 * rather than every distinct *node*. The number of paths grows combinatorially with the graph,
 * so for large graphs the tree never finishes rendering: a ~460-dependency Kotlin/Spring/Vaadin
 * resolution expands to more than 20 million nodes from only ~150 distinct ones. Re-expanding a
 * node also re-runs `DependencyTree.Node#children`, which scans the whole dependency set once per
 * global dependency-management override, making each of those visits expensive as well. Those
 * overrides come from Gradle Module metadata, which is why modules that set `checkGradleModules`
 * (`KotlinModule`, `GroovyModule`, `AndroidModule`) hit this hardest.
 *
 * See https://github.com/com-lihaoyi/mill/issues/6823.
 */
private[mill] object DepsTreeRenderer {

  def forward(
      resolution: Resolution,
      roots: Seq[Dependency],
      colors: Boolean = true
  ): String = {
    val colors0 = Print.Colors.get(colors)
    val trees = DependencyTree(resolution, roots, withExclusions = false)
    Tree(trees.toVector)(_.children)
      .customRender0(deduplicateNodes = true) { tree =>
        render(
          tree.dependency.module,
          tree.dependency.versionConstraint,
          tree.excluded,
          resolution.retainedVersions.get(tree.dependency.module),
          colors0
        )
      }
  }

  /** Mirrors the private `coursier.util.Print#render`. */
  private def render(
      module: Module,
      version: VersionConstraint,
      excluded: Boolean,
      retainedVersionOpt: Option[Version],
      colors: Print.Colors
  ): String = {
    def renderModuleVersion(module: Module, version: String) = s"${module.repr}:$version"

    if (excluded)
      retainedVersionOpt match {
        case None =>
          s"${colors.yellow}(excluded)${colors.reset} ${module.repr}:${version.asString}"
        case Some(retainedVersion) =>
          val versionMsg =
            if (retainedVersion.asString == version.asString) "this version"
            else s"version ${retainedVersion.asString}"

          renderModuleVersion(module, version.asString) +
            s" ${colors.red}(excluded, $versionMsg present anyway)${colors.reset}"
      }
    else {
      assert(
        retainedVersionOpt.nonEmpty,
        s"No retained version found for non-excluded dependency $module"
      )
      val retainedVersion = retainedVersionOpt.get
      val versionStr =
        if (retainedVersion.asString == version.asString) version.asString
        else {
          val assumeCompatibleVersions = Print.compatibleVersions(version, retainedVersion)

          (if (assumeCompatibleVersions) colors.yellow else colors.red) +
            s"${version.asString} -> ${retainedVersion.asString}" +
            (if (assumeCompatibleVersions) "" else " (possible incompatibility)") +
            colors.reset
        }

      renderModuleVersion(module, versionStr)
    }
  }
}
