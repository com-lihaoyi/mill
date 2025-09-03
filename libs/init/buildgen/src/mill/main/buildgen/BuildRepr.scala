package mill.main.buildgen

/**
 * A representation for a build defined as a tree of packages, where a package is a tree of modules.
 */
type BuildRepr = Tree[Tree[ModuleRepr]]
object BuildRepr {

  /**
   * Returns a build with empty packages added for intermediate segments not in the given list.
   */
  def fill(packages: Seq[Tree[ModuleRepr]]): BuildRepr = Tree.from(Seq.empty[String]): segments =>
    val pkg = packages.find(_.root.segments == segments).getOrElse(Tree(ModuleRepr(segments)))
    val nextDepth = segments.length + 1
    val children = packages.iterator.collect:
      case pkg
          if pkg.root.segments.startsWith(segments) && pkg.root.segments.length >= nextDepth =>
        pkg.root.segments.take(nextDepth)
    .distinct.toSeq
    (pkg, children.sortBy(os.sub / _))

  /**
   * A build transformation that moves modules in nested packages into the root package.
   */
  def merged(packages: BuildRepr) = {
    def canUnify(pkg: Tree[Tree[ModuleRepr]], into: Seq[Tree[ModuleRepr]]): Boolean =
      into.forall: modules =>
        modules.root.segments.last != pkg.root.root.segments.last &&
          !modules.root.testModule.exists(_.name == pkg.root.root.segments.last) &&
          pkg.children.forall(canUnify(_, pkg.root.children))
    val (unify, nested) = packages.children.partition(canUnify(_, packages.root.children))
    if (unify.isEmpty) packages
    else Tree(
      packages.root.copy(children =
        packages.root.children ++ unify.map: pkg =>
          pkg.transform: (root, children) =>
            root.copy(children = root.children ++ children)
      ),
      nested
    )
  }
}
