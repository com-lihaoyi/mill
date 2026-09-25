package mill.javalib.internal

import coursier.core.{Dependency, Module, Resolution}
import coursier.graph.DependencyTree
import coursier.util.Print
import coursier.version.{Version, VersionConstraint}
import mill.api.daemon.internal.internal

import scala.collection.mutable

/**
 * Renders the non-inverted dependency tree printed by `showMvnDepsTree`.
 *
 * This mirrors `coursier.util.Print.dependencyTree0(reverse = false)`, except that a dependency
 * reached by more than one path is only expanded once. Every occurrence of such a dependency,
 * the expanded one included, is tagged with a `(*n)` reference so the expansion can be found.
 *
 * Nodes are identified the way coursier identifies them, by the `Dependency` they were reached
 * with rather than by module and version alone. The same coordinates can therefore carry more
 * than one reference number, when they are pulled in with different exclusions or attributes -
 * and those really are different nodes, since `DependencyTree.Node#children` is computed from
 * that `Dependency`. On a large Spring/Vaadin resolution, 104 of 506 nodes render to a label
 * that some other node also renders to, and 26 of those genuinely have a different subtree.
 * Merging them by their rendered label would point the reader at an expansion that is not
 * theirs.
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
@internal
private[mill] object DepsTreeRenderer {

  def forward(
      resolution: Resolution,
      roots: Seq[Dependency],
      colors: Boolean = true
  ): String = {
    val colors0 = Print.Colors.get(colors)
    val rootTrees = DependencyTree(resolution, roots, withExclusions = false)

    // First pass: find the dependencies that are reached by more than one path, and so end up
    // expanded once and referenced elsewhere. Only those need a reference number.
    val seen = mutable.HashSet.empty[DependencyTree]
    val repeated = mutable.HashSet.empty[DependencyTree]
    def scan(elems: Seq[DependencyTree], ancestors: Set[DependencyTree]): Unit =
      for (elem <- elems if !ancestors.contains(elem))
        if (!seen.add(elem)) repeated += elem
        else scan(elem.children, ancestors + elem)
    scan(rootTrees, Set.empty)

    // Second pass: render, numbering repeated dependencies as they are first reached. A node's
    // first appearance is also its expansion, so the numbering follows the printed order.
    val references = mutable.HashMap.empty[DependencyTree, Int]
    val expanded = mutable.HashSet.empty[DependencyTree]
    val lines = mutable.ArrayBuffer.empty[String]

    def reference(elem: DependencyTree): String =
      if (!repeated.contains(elem)) ""
      else s" (*${references.getOrElseUpdate(elem, references.size + 1)})"

    def print0(elems: Seq[DependencyTree], ancestors: Set[DependencyTree], prefix: String): Unit = {
      val unseen = elems.filterNot(ancestors.contains)
      val unseenLen = unseen.length
      for ((elem, idx) <- unseen.iterator.zipWithIndex) {
        val isLast = idx == unseenLen - 1
        lines += prefix + (if (isLast) "└─ " else "├─ ") + show(elem, resolution, colors0) +
          reference(elem)
        if (expanded.add(elem))
          print0(elem.children, ancestors + elem, prefix + (if (isLast) "   " else "│  "))
      }
    }
    print0(rootTrees, Set.empty, "")

    lines.mkString(System.lineSeparator())
  }

  private def show(tree: DependencyTree, resolution: Resolution, colors: Print.Colors): String =
    render(
      tree.dependency.module,
      tree.dependency.versionConstraint,
      tree.excluded,
      resolution.retainedVersions.get(tree.dependency.module),
      colors
    )

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
