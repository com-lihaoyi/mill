package mill.main.buildgen

/**
 * A representation for a build defined as a tree of packages, where a package is a tree of modules.
 */
case class BuildRepr(packages: Tree[Tree[ModuleRepr]], meta: Option[MetaBuildRepr] = None) {

  /**
   * A build transformation that moves modules in nested packages into the root package.
   * A consequence of this transformation is that nested build files are eliminated.
   */
  def unified = {
    def canUnify(pkg: Tree[Tree[ModuleRepr]], into: Seq[Tree[ModuleRepr]]): Boolean =
      into.forall: modules =>
        modules.root.segments.last != pkg.root.root.segments.last &&
          !modules.root.testModule.exists(_.name == pkg.root.root.segments.last) &&
          pkg.children.forall(canUnify(_, pkg.root.children))
    val (unify, nested) = packages.children.partition(canUnify(_, packages.root.children))
    if (unify.isEmpty) this
    else copy(packages =
      Tree(
        packages.root.copy(children =
          packages.root.children ++ unify.map: pkg =>
            pkg.transform: (root, children) =>
              root.copy(children = root.children ++ children)
        ),
        nested
      )
    )
  }

  def withMetaBuild(args: MetaBuildArgs): BuildRepr = {
    if (meta.nonEmpty || args.noMetaBuild.value) return this
    val deps = DepsObject(args.depsObjectName)
    def updated(configs: Seq[ModuleConfig]) = configs.map {
      case config: JavaModuleConfig => config.copy(
          mandatoryMvnDeps = config.mandatoryMvnDeps.map(deps.renderRef),
          mvnDeps = config.mvnDeps.map(deps.renderRef),
          compileMvnDeps = config.compileMvnDeps.map(deps.renderRef),
          runMvnDeps = config.runMvnDeps.map(deps.renderRef),
          bomMvnDeps = config.bomMvnDeps.map(deps.renderRef)
        )
      case config: ErrorProneModuleConfig => config.copy(
          errorProneDeps = config.errorProneDeps.map(deps.renderRef)
        )
      case config: ScalaModuleConfig => config.copy(
          scalacPluginMvnDeps = config.scalacPluginMvnDeps.map(deps.renderRef)
        )
      case config => config
    }
    var packages0 = packages.map: pkg =>
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
        )
    val baseTraits = if (packages0.root.children.isEmpty && packages0.children.isEmpty) Nil
    else
      def base(suffix: String, modules: Seq[ModuleRepr]): Option[BaseTrait] =
        Option.when(modules.length > 1):
          modules.iterator.reduce: (m1, m2) =>
            m1.copy(
              supertypes = m1.supertypes.intersect(m2.supertypes),
              mixins = if (m1.mixins == m2.mixins) m1.mixins else Nil,
              configs = ModuleConfig.abstracted(m1.configs, m2.configs),
              crossConfigs = m1.crossConfigs.flatMap: (cross, configs1) =>
                m2.crossConfigs.collectFirst:
                  case (`cross`, configs2) => (cross, ModuleConfig.abstracted(configs1, configs2))
            )
        .collect:
          case module
              if (module.configs.nonEmpty || module.crossConfigs.nonEmpty) && (module.supertypes.nonEmpty || module.mixins.nonEmpty) =>
            import module.*
            BaseTrait(
              name = os.pwd.last.split("\\W").map(_.capitalize).mkString("", "", suffix),
              supertypes,
              mixins,
              configs,
              crossConfigs
            )
      val baseTrait = base(
        args.baseTraitSuffix,
        packages0.iterator.flatMap(_.iterator).filter(_.configs.nonEmpty).toSeq
      )
      baseTrait.foreach: base =>
        packages0 = packages0.map: pkg =>
          pkg.map: module =>
            if (module.configs.isEmpty) module
            else base.inherited(module)
      // If the first base trait has no publish settings, we attempt to generate one that does.
      val publishTrait =
        if (baseTrait.exists(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))) None
        else base(
          args.publishTraitSuffix,
          packages0.iterator.flatMap(_.iterator)
            .filter(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))
            .toSeq
        )
      publishTrait.foreach: base =>
        packages0 = packages0.map: pkg =>
          pkg.map: module =>
            if (module.configs.exists(_.isInstanceOf[PublishModuleConfig])) base.inherited(module)
            else module
      baseTrait.toSeq ++ publishTrait.toSeq
    copy(packages0, Some(MetaBuildRepr(args.packageName, baseTraits, deps)))
  }
}

object BuildRepr {

  /**
   * Returns a build with empty packages added for intermediate segments not in the given list.
   */
  def fill(packages: Seq[Tree[ModuleRepr]]): BuildRepr = BuildRepr(
    Tree.from(Seq.empty[String]): segments =>
      val pkg = packages.find(_.root.segments == segments).getOrElse(Tree(ModuleRepr(segments)))
      val nextDepth = segments.length + 1
      val children = packages.iterator.collect:
        case pkg
            if pkg.root.segments.startsWith(segments) && pkg.root.segments.length >= nextDepth =>
          pkg.root.segments.take(nextDepth)
      .distinct.toSeq
      (pkg, children.sortBy(os.sub / _))
  )
}
