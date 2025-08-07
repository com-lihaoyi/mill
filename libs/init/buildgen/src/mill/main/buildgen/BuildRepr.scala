package mill.main.buildgen

case class BuildRepr private (
    packages: Tree[Tree[ModuleRepr]],
    depsObject: Option[DepsObject] = None
) {

  def merged = copy(packages =
    packages.copy(root =
      packages.root.copy(children =
        packages.root.children ++ packages.children.map(_.transform: (pkg, children1) =>
          Tree(pkg.root, pkg.children ++ children1))
      )
    )
  )

  def withDepsObject(deps: DepsObject) =
    if (depsObject.isEmpty)
      def updated(configs: Seq[ModuleConfig]) = configs.map {
        case config: JavaModuleConfig => config.copy(
            mandatoryMvnDeps = config.mandatoryMvnDeps.map(deps.renderName),
            mvnDeps = config.mvnDeps.map(deps.renderName),
            compileMvnDeps = config.compileMvnDeps.map(deps.renderName),
            runMvnDeps = config.runMvnDeps.map(deps.renderName)
          )
        case config: ScalaModuleConfig => config.copy(
            scalacPluginMvnDeps = config.scalacPluginMvnDeps.map(deps.renderName)
          )
        case config => config
      }
      copy(
        packages = packages.map: pkg =>
          pkg.map: module =>
            module.copy(
              configs = updated(module.configs),
              crossConfigs = module.crossConfigs.map((k, v) => (k, updated(v))),
              testModule =
                module.testModule.map(test =>
                  test.copy(
                    configs = updated(test.configs),
                    crossConfigs = test.crossConfigs.map((k, v) => (k, updated(v)))
                  )
                )
            ),
        depsObject = Some(deps)
      )
    else this
}
object BuildRepr {

  def apply(packages: Seq[Tree[ModuleRepr]]): BuildRepr = BuildRepr(
    Tree.from(Seq.empty[String]): segments =>
      val pkg = packages.find(_.root.segments == segments).getOrElse(Tree(ModuleRepr(segments)))
      val nextDepth = segments.length + 1
      val (children, descendants) = packages.iterator.map(_.root.segments)
        .filter(_.length > segments.length)
        .partition(_.length == nextDepth)
      val children0 =
        if (children.nonEmpty) children else descendants.map(_.take(nextDepth)).distinct
      (pkg, children0.toSeq.sortBy(os.sub / _))
  )
}
