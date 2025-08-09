package mill.main.buildgen

/**
 * A representation for a build defined as a tree of packages and auxiliary type definitions.
 * @note A package is defined as a tree of modules.
 */
case class BuildRepr private (
    packages: Tree[Tree[ModuleRepr]],
    baseTraits: Seq[BaseTrait] = Nil,
    depsObject: Option[DepsObject] = None
) {

  /**
   * A build transformation that moves modules in nested packages into the root package.
   * A consequence of this transformation is that nested build files are eliminated.
   * @note Only nested packages with modules with no name collisions are merged.
   */
  def merged = {
    def canMerge(pkg: Tree[Tree[ModuleRepr]], into: Seq[Tree[ModuleRepr]]): Boolean =
      into.forall: modules =>
        modules.root.segments.last != pkg.root.root.segments.last &&
          !modules.root.testModule.exists(_.name == pkg.root.root.segments.last) &&
          pkg.children.forall(canMerge(_, pkg.root.children))
    val (merge, nested) = packages.children.partition(canMerge(_, packages.root.children))
    if (merge.isEmpty) this
    else copy(packages =
      Tree(
        packages.root.copy(children =
          packages.root.children ++ merge.map: pkg =>
            pkg.transform: (root, children) =>
              root.copy(children = root.children ++ children)
        ),
        nested
      )
    )
  }

  /**
   * A build transformation that generates base traits for sharing module settings.
   * @note No base trait is generated for a build with a single root module.
   */
  def withBaseTraits =
    if (baseTraits.nonEmpty) this
    else if (packages.root.children.isEmpty && packages.children.isEmpty) this
    else {
      val baseTrait = BaseTrait.compute(
        "BaseModule",
        packages.iterator.flatMap(_.iterator).filter(_.configs.nonEmpty)
      )
      var packages0 = packages
      baseTrait.foreach: base =>
        packages0 = packages0.map: pkg =>
          pkg.map: module =>
            if (module.configs.isEmpty) module
            else base.inherited(module)
      // If the first base trait has no publish settings, we attempt to generate one that does.
      val basePublishTrait =
        if (baseTrait.exists(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))) None
        else BaseTrait.compute(
          "PublishModule",
          packages0.iterator.flatMap(_.iterator)
            .filter(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))
        )
      basePublishTrait.foreach: base =>
        packages0 = packages0.map: pkg =>
          pkg.map: module =>
            if (module.configs.exists(_.isInstanceOf[PublishModuleConfig])) base.inherited(module)
            else module
      copy(packages = packages0, baseTraits = baseTrait.toSeq ++ basePublishTrait.toSeq)
    }

  /**
   * A build transformation that assigns constant references to Maven dependencies.
   */
  def withDepsObject(deps: DepsObject) = {
    if (depsObject.isEmpty)
      def updated(configs: Seq[ModuleConfig]) = configs.map {
        case config: JavaModuleConfig => config.copy(
            mandatoryMvnDeps = config.mandatoryMvnDeps.map(deps.renderRef),
            mvnDeps = config.mvnDeps.map(deps.renderRef),
            compileMvnDeps = config.compileMvnDeps.map(deps.renderRef),
            runMvnDeps = config.runMvnDeps.map(deps.renderRef)
          )
        case config: ScalaModuleConfig => config.copy(
            scalacPluginMvnDeps = config.scalacPluginMvnDeps.map(deps.renderRef)
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
}
object BuildRepr {

  /**
   * Returns a build with empty packages added for intermediate segments not in the given list.
   */
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
