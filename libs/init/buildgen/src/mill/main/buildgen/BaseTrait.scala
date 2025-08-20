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
