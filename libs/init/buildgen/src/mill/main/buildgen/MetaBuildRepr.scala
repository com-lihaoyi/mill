package mill.main.buildgen

import mill.internal.Util.backtickWrap

case class MetaBuildRepr(
    packageName: String,
    depsObjectName: String,
    baseTraits: Seq[MetaBuildRepr.BaseTrait]
)
object MetaBuildRepr {

  def of(
      packages: Tree[Tree[ModuleRepr]],
      packageName: String = "millbuild",
      depsObjectName: String = "Deps"
  ) = {
    var packages0 = packages
    val baseTraits = if (packages0.root.children.isEmpty && packages0.children.isEmpty) Nil
    else {
      val baseTrait = BaseTrait.abstracted(
        "BaseModule",
        packages0.iterator.flatMap(_.iterator).filter(_.configs.nonEmpty).toSeq
      )
      baseTrait.foreach: base =>
        packages0 = packages0.map: pkg =>
          pkg.map: module =>
            if (module.configs.isEmpty) module else base.inherited(module)
      // If the first base trait has no publish settings, we attempt to generate one that does.
      val publishTrait =
        if (baseTrait.exists(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))) None
        else BaseTrait.abstracted(
          "PublishModule",
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
    }
    (packages0, MetaBuildRepr(packageName, depsObjectName, baseTraits))
  }

  case class BaseTrait(
      name: String,
      supertypes: Seq[String],
      mixins: Seq[String] = Nil,
      configs: Seq[ModuleConfig] = Nil,
      crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil
  ) {

    def inherited(module: ModuleRepr): ModuleRepr =
      module.copy(
        supertypes = module.supertypes.diff(supertypes) :+ name,
        mixins = if (module.mixins == mixins) Nil else module.mixins,
        configs = ModuleConfig.inherited(module.configs, configs),
        crossConfigs = module.crossConfigs.map: (cross, configs1) =>
          crossConfigs.collectFirst:
            case (`cross`, configs2) => (cross, ModuleConfig.inherited(configs1, configs2))
          .getOrElse((cross, configs1))
      )
  }
  object BaseTrait {

    def abstracted(suffix: String, modules: Seq[ModuleRepr]) = Option.when(modules.length > 1) {
      modules.iterator.reduce((m1, m2) =>
        m1.copy(
          supertypes = m1.supertypes.intersect(m2.supertypes),
          mixins = if (m1.mixins == m2.mixins) m1.mixins else Nil,
          configs = ModuleConfig.abstracted(m1.configs, m2.configs),
          crossConfigs = m1.crossConfigs.flatMap: (cross, configs1) =>
            m2.crossConfigs.collectFirst:
              case (`cross`, configs2) => (cross, ModuleConfig.abstracted(configs1, configs2))
        )
      )
    }.collect {
      case module if module.configs.nonEmpty =>
        import module.*
        val name = os.pwd.last.dropWhile(!_.isLetter) match {
          case "" => backtickWrap(os.pwd.last + suffix)
          case s => s.split("\\W").map(_.capitalize).mkString("", "", suffix)
        }
        BaseTrait(name, supertypes, mixins, configs, crossConfigs)
    }
  }
}
