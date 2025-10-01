package mill.main.buildgen

import mill.main.buildgen.BuildInfo.millJvmVersion
import mill.util.BuildInfo.millVersion

/**
 * A build represented as a [[Tree]] of packages where each package corresponds to a build file
 * containing a [[Tree]] of modules.
 */
case class BuildRepr(
    packages: Tree[Tree[ModuleRepr]],
    metaBuild: Option[MetaBuildRepr] = None,
    millVersion: String = millVersion,
    millJvmVersion: String = millJvmVersion,
    millJvmOpts: Seq[String] = Nil
) {

  def merged: BuildRepr = copy(packages = {
    def canMerge(packages: Tree[Tree[ModuleRepr]], siblings: Seq[Tree[ModuleRepr]]): Boolean =
      siblings.forall(pkg =>
        pkg.root.segments.last != packages.root.root.segments.last &&
          packages.children.forall(canMerge(_, packages.root.children))
      )
    val (merge, nested) = packages.children.partition(canMerge(_, packages.root.children))
    if (merge.isEmpty) packages
    else Tree(
      packages.root.copy(children =
        packages.root.children ++ merge.map(pkg =>
          pkg.transform((root, children) => root.copy(children = root.children ++ children))
        )
      ),
      nested
    )
  })

  def withMetaBuild = if (metaBuild.nonEmpty) this
  else {
    val (packages0, metaBuild0) = MetaBuildRepr.of(packages)
    copy(packages = packages0, metaBuild = Some(metaBuild0))
  }
}
object BuildRepr {

  /**
   * Returns a build with empty packages introduced for intermediate segments not in `packages`.
   */
  def fill(packages: Seq[Tree[ModuleRepr]]): BuildRepr = apply(
    packages = Tree.from(Seq.empty[String]) { segments =>
      val pkg = packages.find(_.root.segments == segments).getOrElse(Tree(ModuleRepr(segments)))
      val nextDepth = segments.length + 1
      val children = packages.iterator.collect {
        case pkg
            if pkg.root.segments.startsWith(segments) && pkg.root.segments.length >= nextDepth =>
          pkg.root.segments.take(nextDepth)
      }.distinct.toSeq
      (pkg, children.sortBy(os.sub / _))
    }
  )
}
