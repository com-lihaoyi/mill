package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.{MvnDep, Value, Values}

/**
 * Shared interface to generate Mill project files.
 */
trait BuildGen {

  def writeBuildFiles(
      baseDir: os.Path,
      packages: Seq[PackageSpec],
      merge: Boolean = false,
      baseModule: Option[ModuleSpec] = None,
      millJvmVersion: Option[String] = None,
      millJvmOpts: Seq[String] = Nil,
      depNames: Seq[(MvnDep, String)] = Nil
  ): Seq[os.Path]

  def withBaseModule(
      packages: Seq[PackageSpec],
      moduleHierarchy: Seq[String],
      testHierarchy: Seq[String]
  ): Option[(ModuleSpec, Seq[PackageSpec])] = {
    def parentValue[A](a: Value[A], b: Value[A]) = Value(
      if (a.base == b.base) a.base else None,
      a.cross.intersect(b.cross)
    )

    def parentValues[A](a: Values[A], b: Values[A]) = Values(
      a.base.intersect(b.base),
      (a.cross ++ b.cross).groupMapReduce(_._1)(_._2)(_.intersect(_)).toSeq.filter(_._2.nonEmpty),
      a.appendSuper && b.appendSuper
    )

    def parentModule(a: ModuleSpec, b: ModuleSpec, name: String, defaultSupertypes: Seq[String]) =
      ModuleSpec(
        name = name,
        imports = (a.imports ++ b.imports).distinct.filter(!_.startsWith("import millbuild.")),
        supertypes = a.supertypes.intersect(b.supertypes) match {
          case Nil => defaultSupertypes
          case seq => seq
        },
        mixins = if (a.supertypes == b.supertypes && a.mixins == b.mixins) a.mixins else Nil,
        repositories = parentValues(a.repositories, b.repositories),
        forkArgs = parentValues(a.forkArgs, b.forkArgs),
        forkWorkingDir = parentValue(a.forkWorkingDir, b.forkWorkingDir),
        mandatoryMvnDeps = parentValues(a.mandatoryMvnDeps, b.mandatoryMvnDeps),
        mvnDeps = parentValues(a.mvnDeps, b.mvnDeps),
        compileMvnDeps = parentValues(a.compileMvnDeps, b.compileMvnDeps),
        runMvnDeps = parentValues(a.runMvnDeps, b.runMvnDeps),
        bomMvnDeps = parentValues(a.bomMvnDeps, b.bomMvnDeps),
        depManagement = parentValues(a.depManagement, b.depManagement),
        javacOptions = parentValues(a.javacOptions, b.javacOptions),
        sourcesFolders = parentValues(a.sourcesFolders, b.sourcesFolders),
        sources = parentValues(a.sources, b.sources),
        resources = parentValues(a.resources, b.resources),
        artifactName = parentValue(a.artifactName, b.artifactName),
        pomPackagingType = parentValue(a.pomPackagingType, b.pomPackagingType),
        pomParentProject = parentValue(a.pomParentProject, b.pomParentProject),
        pomSettings = parentValue(a.pomSettings, b.pomSettings),
        publishVersion = parentValue(a.publishVersion, b.publishVersion),
        versionScheme = parentValue(a.versionScheme, b.versionScheme),
        publishProperties = parentValues(a.publishProperties, b.publishProperties),
        errorProneDeps = parentValues(a.errorProneDeps, b.errorProneDeps),
        errorProneOptions = parentValues(a.errorProneOptions, b.errorProneOptions),
        errorProneJavacEnableOptions =
          parentValues(a.errorProneJavacEnableOptions, b.errorProneJavacEnableOptions),
        scalaVersion = parentValue(a.scalaVersion, b.scalaVersion),
        scalacOptions = parentValues(a.scalacOptions, b.scalacOptions),
        scalacPluginMvnDeps = parentValues(a.scalacPluginMvnDeps, b.scalacPluginMvnDeps),
        scalaJSVersion = parentValue(a.scalaJSVersion, b.scalaJSVersion),
        moduleKind = parentValue(a.moduleKind, b.moduleKind),
        scalaNativeVersion = parentValue(a.scalaNativeVersion, b.scalaNativeVersion),
        sourcesRootFolders = parentValues(a.sourcesRootFolders, b.sourcesRootFolders),
        testParallelism = parentValue(a.testParallelism, b.testParallelism),
        testSandboxWorkingDir = parentValue(a.testSandboxWorkingDir, b.testSandboxWorkingDir),
        testFramework = parentValue(a.testFramework, b.testFramework)
      )

    def extendValue[A](a: Value[A], parent: Value[A]) = a.copy(
      if (a.base == parent.base) None else a.base,
      a.cross.diff(parent.cross)
    )

    def extendValues[A](a: Values[A], parent: Values[A]) = a.copy(
      a.base.diff(parent.base),
      a.cross.map((k, a) =>
        parent.cross.collectFirst {
          case (`k`, b) => (k, a.diff(b))
        }.getOrElse((k, a))
      ).filter(_._2.nonEmpty),
      a.appendSuper || parent.base.nonEmpty || parent.cross.nonEmpty
    )

    def extendModule0(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = a.copy(
      supertypes = (parent.name +: a.supertypes).diff(parent.supertypes),
      mixins = if (a.mixins == parent.mixins) Nil else a.mixins,
      repositories = extendValues(a.repositories, parent.repositories),
      forkArgs = extendValues(a.forkArgs, parent.forkArgs),
      forkWorkingDir = extendValue(a.forkWorkingDir, parent.forkWorkingDir),
      mandatoryMvnDeps = extendValues(a.mandatoryMvnDeps, parent.mandatoryMvnDeps),
      mvnDeps = extendValues(a.mvnDeps, parent.mvnDeps),
      compileMvnDeps = extendValues(a.compileMvnDeps, parent.compileMvnDeps),
      runMvnDeps = extendValues(a.runMvnDeps, parent.runMvnDeps),
      bomMvnDeps = extendValues(a.bomMvnDeps, parent.bomMvnDeps),
      depManagement = extendValues(a.depManagement, parent.depManagement),
      javacOptions = extendValues(a.javacOptions, parent.javacOptions),
      sourcesFolders = extendValues(a.sourcesFolders, parent.sourcesFolders),
      sources = extendValues(a.sources, parent.sources),
      resources = extendValues(a.resources, parent.resources),
      artifactName = extendValue(a.artifactName, parent.artifactName),
      pomPackagingType = extendValue(a.pomPackagingType, parent.pomPackagingType),
      pomParentProject = extendValue(a.pomParentProject, parent.pomParentProject),
      pomSettings = extendValue(a.pomSettings, parent.pomSettings),
      publishVersion = extendValue(a.publishVersion, parent.publishVersion),
      versionScheme = extendValue(a.versionScheme, parent.versionScheme),
      publishProperties = extendValues(a.publishProperties, parent.publishProperties),
      errorProneDeps = extendValues(a.errorProneDeps, parent.errorProneDeps),
      errorProneOptions = extendValues(a.errorProneOptions, parent.errorProneOptions),
      errorProneJavacEnableOptions =
        extendValues(a.errorProneJavacEnableOptions, parent.errorProneJavacEnableOptions),
      scalaVersion = extendValue(a.scalaVersion, parent.scalaVersion),
      scalacOptions = extendValues(a.scalacOptions, parent.scalacOptions),
      scalacPluginMvnDeps = extendValues(a.scalacPluginMvnDeps, parent.scalacPluginMvnDeps),
      scalaJSVersion = extendValue(a.scalaJSVersion, parent.scalaJSVersion),
      moduleKind = extendValue(a.moduleKind, parent.moduleKind),
      scalaNativeVersion = extendValue(a.scalaNativeVersion, parent.scalaNativeVersion),
      sourcesRootFolders = extendValues(a.sourcesRootFolders, parent.sourcesRootFolders),
      testParallelism = extendValue(a.testParallelism, parent.testParallelism),
      testSandboxWorkingDir = extendValue(a.testSandboxWorkingDir, parent.testSandboxWorkingDir),
      testFramework = extendValue(a.testFramework, parent.testFramework)
    )

    def canExtend(module: ModuleSpec) = module.supertypes.exists(moduleHierarchy.contains)

    def isTestModule(module: ModuleSpec) = module.supertypes.exists(testHierarchy.contains)

    def recExtendModule(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = {
      var a0 = a
      var (tests0, children0) = a0.children.partition(isTestModule)
      if (canExtend(a0)) {
        a0 = extendModule0(a0.copy(imports = "import millbuild.*" +: a0.imports), parent)
        if (parent.children.nonEmpty) {
          tests0 = tests0.map(extendModule0(_, parent.children.head))
        }
      }
      children0 = children0.map(recExtendModule(_, parent))
      a0.copy(children = tests0 ++ children0)
    }

    val extendingModules = packages.flatMap(_.module.tree).filter(canExtend)
    Option.when(extendingModules.length > 1) {
      val defaultSupertypes = moduleHierarchy.take(1)
      val defaultTestSupertypes = testHierarchy.take(1)
      val baseModule = extendingModules
        .reduce(parentModule(_, _, "ProjectBaseModule", defaultSupertypes))
        .copy(children =
          extendingModules.flatMap(_.children.filter(isTestModule))
            .reduceOption(parentModule(_, _, "ProjectBaseTests", defaultTestSupertypes)).toSeq
        )
      val packages0 =
        packages.map(pkg => pkg.copy(module = recExtendModule(pkg.module, baseModule)))
      (baseModule, packages0)
    }
  }
}
