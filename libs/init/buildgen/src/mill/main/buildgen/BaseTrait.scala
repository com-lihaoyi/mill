package mill.main.buildgen

case class BaseTrait(
    name: String,
    supertypes: Seq[String] = Seq("Module"),
    mixins: Seq[String] = Nil,
    configs: Seq[ModuleConfig] = Nil,
    crossConfigs: Seq[(String, Seq[ModuleConfig])] = Nil
) {

  def inherited(module: ModuleRepr): ModuleRepr = module.copy(
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

  def compute(suffix: String, modules: IterableOnce[ModuleRepr]): Option[BaseTrait] =
    modules.iterator.reduceOption: (m1, m2) =>
      m1.copy(
        supertypes = m1.supertypes.intersect(m2.supertypes),
        mixins = if (m1.mixins == m2.mixins) m1.mixins else Nil,
        configs = ModuleConfig.abstracted(m1.configs, m2.configs),
        crossConfigs = m1.crossConfigs.flatMap: (cross, configs1) =>
          m2.crossConfigs.collectFirst:
            case (`cross`, configs2) => (cross, ModuleConfig.abstracted(configs1, configs2))
      )
    .collect:
      case module if module.supertypes.nonEmpty || module.mixins.nonEmpty =>
        import module.*
        BaseTrait(
          name = os.pwd.last.split("\\W").map(_.capitalize).mkString("", "", suffix),
          supertypes,
          mixins,
          configs,
          crossConfigs
        )
}
