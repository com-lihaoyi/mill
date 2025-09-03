package mill.main.buildgen

import mill.internal.Util.backtickWrap

import scala.collection.mutable

case class MetaBuildRepr(
    packageName: String,
    baseTraits: Seq[MetaBuildRepr.BaseTrait],
    depsObject: MetaBuildRepr.DepsObject
)
object MetaBuildRepr {

  def of(build: BuildRepr, packageName: String = "millbuild") = {
    val deps = DepsObject("Deps")
    def withDepRef(configs: Seq[ModuleConfig]) = configs.map {
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
    var packages = build.map: pkg =>
      pkg.map: module =>
        module.copy(
          configs = withDepRef(module.configs),
          crossConfigs = module.crossConfigs.map((k, v) => (k, withDepRef(v))),
          testModule =
            module.testModule.map(test =>
              test.copy(
                configs = withDepRef(test.configs),
                crossConfigs = test.crossConfigs.map((k, v) => (k, withDepRef(v)))
              )
            )
        )
    val baseTraits = if (packages.root.children.isEmpty && packages.children.isEmpty) Nil
    else
      def abstractedBase(suffix: String, modules: Seq[ModuleRepr]): Option[BaseTrait] =
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
      val baseTrait = abstractedBase(
        "BaseModule",
        packages.iterator.flatMap(_.iterator).filter(_.configs.nonEmpty).toSeq
      )
      baseTrait.foreach: base =>
        packages = packages.map: pkg =>
          pkg.map: module =>
            if (module.configs.isEmpty) module
            else base.inherited(module)
      // If the first base trait has no publish settings, we attempt to generate one that does.
      val publishTrait =
        if (baseTrait.exists(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))) None
        else abstractedBase(
          "PublishModule",
          packages.iterator.flatMap(_.iterator)
            .filter(_.configs.exists(_.isInstanceOf[PublishModuleConfig]))
            .toSeq
        )
      publishTrait.foreach: base =>
        packages = packages.map: pkg =>
          pkg.map: module =>
            if (module.configs.exists(_.isInstanceOf[PublishModuleConfig])) base.inherited(module)
            else module
      baseTrait.toSeq ++ publishTrait.toSeq
    (packages, MetaBuildRepr(packageName, baseTraits, deps))
  }

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

  case class DepsObject(name: String, refsByDep: mutable.Map[String, String] = mutable.Map.empty) {
    private val artifactRegex = """:([^:"]+)[:"]""".r

    def renderRef(dep: String) = {
      /*
        We use the artifact name as the seed for the reference. When a name collision occurs, a
        suffix is added that starts with the '#' character. This forces backticks in the final name,
        making the "duplicate" stand out visually in the output. The reference without backticks is
        saved in the map so that it is grouped together with the "original", on sort, in the output.
        Example output:
          val catsCore = mvn"org.typelevel::cats-core:2.0.0"
          val `catsCore#0` = mvn"org.typelevel::cats-core:2.6.1"
          val disciplineCore = mvn"org.typelevel::discipline-core::1.7.0"
          val `disciplineCore#0` = mvn"org.typelevel::discipline-core:1.7.0"
          val disciplineMunit = mvn"org.typelevel::discipline-munit:2.0.0"
          val `disciplineMunit#0` = mvn"org.typelevel::discipline-munit::2.0.0"
       */
      var ref = refsByDep.getOrElse(dep, null)
      if (ref == null) {
        val artifact = artifactRegex.findFirstMatchIn(dep).get.group(1)
        ref = artifact.split("\\W") match
          case Array(head) => head
          case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
        if (refsByDep.valuesIterator.contains(ref))
          ref += "#"
          ref += refsByDep.valuesIterator.count(_.startsWith(ref))
        refsByDep.put(dep, ref)
      }
      name + "." + backtickWrap(ref)
    }
  }
}
