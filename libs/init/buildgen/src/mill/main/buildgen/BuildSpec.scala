package mill.main.buildgen

import mill.util.BuildInfo.millVersion

/**
 * Specification for generating source files for a Mill build.
 * @see [[BuildSpec.fill]]
 */
case class BuildSpec(
    packages: Seq[PackageSpec],
    metaBuild: Option[BuildSpec.MetaSpec] = None,
    millVersion: String = millVersion,
    millJvmOpts: Seq[String] = Nil
) {

  /**
   * Moves modules in nested packages to the root module hierarchy. This transformation eliminates
   * nested build files without modifying the build.
   */
  def merged = {
    def recurse(pkg: PackageSpec): ModuleSpec = {
      val mergedModules = packages.collect {
        case subPkg
            if subPkg.segments.startsWith(pkg.segments) &&
              subPkg.segments.length == pkg.segments.length + 1 =>
          recurse(subPkg)
      }
      import pkg.*
      module.copy(
        name = segments.last,
        nestedModules = module.nestedModules ++ mergedModules
      )
    }

    val mergedModules = packages.collect {
      case pkg if pkg.segments.length == 1 => recurse(pkg)
    }
    var rootPackage = packages.head
    rootPackage = rootPackage.copy(module =
      rootPackage.module.copy(nestedModules =
        rootPackage.module.nestedModules ++ mergedModules
      )
    )
    copy(packages = Seq(rootPackage))
  }

  /**
   * Derives a meta-build, with at most 2 base modules, and attaches it to this build.
   *
   * A base module is derived for all non-test modules.
   * If publish data is missing in the base module, another one is derived for publishable modules.
   */
  def withDefaultMetaBuild = {
    def abstractedModule(module1: ModuleSpec, module2: ModuleSpec) = module1.copy(
      supertypes = module1.supertypes.intersect(module2.supertypes),
      mixins = ModuleConfig.abstractedValue(module1.mixins, module2.mixins, Nil),
      configs = ModuleConfig.abstractedConfigs(module1.configs, module2.configs),
      crossConfigs = module1.crossConfigs.flatMap((cross, configs1) =>
        module2.crossConfigs.collectFirst {
          case (`cross`, configs2) =>
            (cross, ModuleConfig.abstractedConfigs(configs1, configs2))
        }
      ),
      nestedModules = Nil
    )
    def inheritedModule(self: ModuleSpec, base: ModuleSpec) = self.copy(
      supertypes = self.supertypes.diff(base.supertypes) :+ base.name,
      mixins = ModuleConfig.inheritedValue(self.mixins, base.mixins, Nil),
      configs = ModuleConfig.inheritedConfigs(self.configs, base.configs),
      crossConfigs = self.crossConfigs.map((cross, selfConfigs) =>
        base.crossConfigs.collectFirst {
          case (`cross`, baseConfigs) =>
            (cross, ModuleConfig.inheritedConfigs(selfConfigs, baseConfigs))
        }.getOrElse((cross, selfConfigs))
      )
    )

    var packages0 = packages
    def baseModuleFor(criteria: ModuleSpec => Boolean, suffix: String) = {
      val modules = packages0
        .flatMap(_.module.sequence)
        .filter(_.configs.nonEmpty)
        .filter(criteria).toSeq
      Option.when(modules.length > 1)(modules.reduce(abstractedModule)).collect {
        case baseModule if baseModule.configs.nonEmpty =>
          val pwdName = os.pwd.last
          val baseModuleName = pwdName.dropWhile(!_.isLetter).split("\\W") match {
            case Array("") => "Project" + suffix
            case parts => parts.map(_.capitalize).mkString("", "", suffix)
          }
          val baseModuleSupertypes = baseModule.supertypes ++ (
            if (baseModule.crossConfigs.nonEmpty && baseModule.isScalaModule)
              Seq("CrossScalaModule")
            else Nil
          )
          val baseModule0 = baseModule.copy(
            name = baseModuleName,
            supertypes = baseModuleSupertypes
          )
          packages0 = packages0.map { pkg =>
            pkg.copy(module =
              pkg.module.transform { module =>
                if (module.configs.isEmpty || !criteria(module)) module
                else inheritedModule(module, baseModule0)
              }
            )
          }
          baseModule0
      }
    }

    val baseModule = baseModuleFor(!_.isTestModule, "BaseModule")
    val basePublishModule =
      if (baseModule.exists(_.isPublishModule)) None
      else baseModuleFor(_.isPublishModule, "PublishModule")
    val baseModules = baseModule.toSeq ++ basePublishModule
    copy(packages0, Some(BuildSpec.MetaSpec(baseModules)))
  }
}
object BuildSpec {

  /**
   * Specification for generating source files for a Mill meta-build.
   * @param baseModules    Module supertypes, containing shared settings, for build modules.
   * @param depsObjectName Name of the Scala object that defines constants for dependencies.
   * @param rootModuleName Name of the module at the root of the meta-build tree.
   */
  case class MetaSpec(
      baseModules: Seq[ModuleSpec] = Nil,
      depsObjectName: String = "Deps",
      rootModuleName: String = "millbuild"
  )

  /**
   * Returns a build specification by adding "unit" packages for any missing path segments.
   */
  def fill(packages: Seq[PackageSpec]): BuildSpec = {
    def unitPackage(dir: Seq[String]) = {
      val module = ModuleSpec(dir.lastOption.getOrElse(os.pwd.last))
      PackageSpec(dir, module)
    }
    def recurse(dir: Seq[String]): Seq[PackageSpec] = {
      val rootPackage = packages.find(_.segments == dir).getOrElse(unitPackage(dir))
      val nestedPackages = packages.map(_.segments).collect {
        case segments if segments.startsWith(dir) && segments.length > dir.length =>
          segments.take(dir.length + 1)
      }.distinct.flatMap(recurse).toSeq
      rootPackage +: nestedPackages
    }

    apply(packages = recurse(Seq.empty))
  }
}
