package mill.main.buildgen

import mill.init.Util
import mill.main.buildgen.BuildInfo.millVersion
import mill.main.buildgen.ModuleSpec.*

import scala.collection.mutable

class BuildSpec(var packages: Seq[PackageSpec]) {

  var depNames: Seq[(MvnDep, String)] = Nil
  var baseModule: Option[ModuleSpec] = None

  def deriveDepNames(): Unit = {
    val depNames = packages.flatMap(_.module.tree).flatMap { module =>
      import module.*
      Seq(
        mandatoryMvnDeps,
        mvnDeps,
        compileMvnDeps,
        runMvnDeps,
        bomMvnDeps,
        depManagement,
        errorProneDeps,
        checkstyleMvnDeps,
        scalacPluginMvnDeps,
        scalafixIvyDeps
      )
    }.flatMap { values =>
      values.base ++ values.cross.flatMap(_._2)
    }.distinct.filter(_.version.nonEmpty).groupBy(_.name).flatMap { (name, deps) =>
      val ref = name.split("\\W") match {
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      }
      deps match {
        case Seq(dep) => Seq((dep, ref))
        case _ => deps.sortBy(_.toString).zipWithIndex.map((dep, i) => (dep, s"`$ref#$i`"))
      }
    }
    val lookupRef = depNames.lift.andThen(_.map(ref => s"Deps.$ref"))

    def updateMvnDeps(values: Values[MvnDep]) = values.copy(
      base = values.base.map(dep => dep.copy(ref = lookupRef(dep))),
      cross = values.cross.map((k, v) => (k, v.map(dep => dep.copy(ref = lookupRef(dep)))))
    )

    def updateModule(module: ModuleSpec) = {
      import module.*
      module.copy(
        imports = "millbuild.*" +: imports,
        mvnDeps = updateMvnDeps(mvnDeps),
        compileMvnDeps = updateMvnDeps(compileMvnDeps),
        runMvnDeps = updateMvnDeps(runMvnDeps),
        bomMvnDeps = updateMvnDeps(bomMvnDeps),
        depManagement = updateMvnDeps(depManagement),
        errorProneDeps = updateMvnDeps(errorProneDeps),
        checkstyleMvnDeps = updateMvnDeps(checkstyleMvnDeps),
        scalacPluginMvnDeps = updateMvnDeps(scalacPluginMvnDeps),
        scalafixIvyDeps = updateMvnDeps(scalafixIvyDeps)
      )
    }

    if (depNames.nonEmpty) {
      this.depNames = depNames.toSeq
      baseModule = baseModule.map(_.recMap(updateModule))
      packages = packages.map(pkg =>
        pkg.copy(module = pkg.module.recMap(updateModule))
      )
    }
  }

  def deriveBaseModule(baseTestHierarchy: (String, String)*): Unit = {
    def parentValue[A](a: Value[A], b: Value[A]) = Value(
      if (a.base == b.base) a.base else None,
      a.cross.intersect(b.cross)
    )

    def parentValues[A](a: Values[A], b: Values[A]) = Values(
      a.base.intersect(b.base),
      a.cross.flatMap { (k, a) =>
        b.cross.collectFirst {
          case (`k`, b) => (k, a.intersect(b))
        }.filter(_._2.nonEmpty)
      },
      a.appendSuper || b.appendSuper
    )

    def parentModule(a: ModuleSpec, b: ModuleSpec, name: String, hierarchy: Seq[String]) =
      ModuleSpec(
        name = name,
        imports = (a.imports ++ b.imports).distinct.filter(!_.startsWith("millbuild.")),
        supertypes = a.supertypes.intersect(b.supertypes) match {
          case Nil => hierarchy.take(1)
          case seq if hierarchy.contains(seq.head) => seq
          case seq => hierarchy.head +: seq
        },
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
        jmhCoreVersion = parentValue(a.jmhCoreVersion, b.jmhCoreVersion),
        checkstyleProperties = parentValues(a.checkstyleProperties, b.checkstyleProperties),
        checkstyleMvnDeps = parentValues(a.checkstyleMvnDeps, b.checkstyleMvnDeps),
        checkstyleConfig = parentValue(a.checkstyleConfig, b.checkstyleConfig),
        checkstyleVersion = parentValue(a.checkstyleVersion, b.checkstyleVersion),
        scalaVersion = parentValue(a.scalaVersion, b.scalaVersion),
        scalacOptions = parentValues(a.scalacOptions, b.scalacOptions),
        scalacPluginMvnDeps = parentValues(a.scalacPluginMvnDeps, b.scalacPluginMvnDeps),
        scalaJSVersion = parentValue(a.scalaJSVersion, b.scalaJSVersion),
        moduleKind = parentValue(a.moduleKind, b.moduleKind),
        scalaNativeVersion = parentValue(a.scalaNativeVersion, b.scalaNativeVersion),
        sourcesRootFolders = parentValues(a.sourcesRootFolders, b.sourcesRootFolders),
        testParallelism = parentValue(a.testParallelism, b.testParallelism),
        testSandboxWorkingDir = parentValue(a.testSandboxWorkingDir, b.testSandboxWorkingDir),
        testFramework = parentValue(a.testFramework, b.testFramework),
        scalafixConfig = parentValue(a.scalafixConfig, b.scalafixConfig),
        scalafixIvyDeps = parentValues(a.scalafixIvyDeps, b.scalafixIvyDeps),
        scoverageVersion = parentValue(a.scoverageVersion, b.scoverageVersion),
        branchCoverageMin = parentValue(a.branchCoverageMin, b.branchCoverageMin),
        statementCoverageMin = parentValue(a.statementCoverageMin, b.statementCoverageMin),
        mimaPreviousVersions = parentValues(a.mimaPreviousVersions, b.mimaPreviousVersions),
        mimaPreviousArtifacts = parentValues(a.mimaPreviousArtifacts, b.mimaPreviousArtifacts),
        mimaCheckDirection = parentValue(a.mimaCheckDirection, b.mimaCheckDirection),
        mimaBinaryIssueFilters = parentValues(a.mimaBinaryIssueFilters, b.mimaBinaryIssueFilters),
        mimaBackwardIssueFilters =
          parentValues(a.mimaBackwardIssueFilters, b.mimaBackwardIssueFilters),
        mimaForwardIssueFilters =
          parentValues(a.mimaForwardIssueFilters, b.mimaForwardIssueFilters),
        mimaExcludeAnnotations = parentValues(a.mimaExcludeAnnotations, b.mimaExcludeAnnotations),
        mimaReportSignatureProblems =
          parentValue(a.mimaReportSignatureProblems, b.mimaReportSignatureProblems)
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

    def extendModule(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = a.copy(
      supertypes = (parent.name +: a.supertypes).diff(parent.supertypes),
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
      jmhCoreVersion = extendValue(a.jmhCoreVersion, parent.jmhCoreVersion),
      checkstyleProperties = extendValues(a.checkstyleProperties, parent.checkstyleProperties),
      checkstyleMvnDeps = extendValues(a.checkstyleMvnDeps, parent.checkstyleMvnDeps),
      checkstyleConfig = extendValue(a.checkstyleConfig, parent.checkstyleConfig),
      checkstyleVersion = extendValue(a.checkstyleVersion, parent.checkstyleVersion),
      scalaVersion = extendValue(a.scalaVersion, parent.scalaVersion),
      scalacOptions = extendValues(a.scalacOptions, parent.scalacOptions),
      scalacPluginMvnDeps = extendValues(a.scalacPluginMvnDeps, parent.scalacPluginMvnDeps),
      scalaJSVersion = extendValue(a.scalaJSVersion, parent.scalaJSVersion),
      moduleKind = extendValue(a.moduleKind, parent.moduleKind),
      scalaNativeVersion = extendValue(a.scalaNativeVersion, parent.scalaNativeVersion),
      sourcesRootFolders = extendValues(a.sourcesRootFolders, parent.sourcesRootFolders),
      testParallelism = extendValue(a.testParallelism, parent.testParallelism),
      testSandboxWorkingDir = extendValue(a.testSandboxWorkingDir, parent.testSandboxWorkingDir),
      testFramework = extendValue(a.testFramework, parent.testFramework),
      scalafixConfig = extendValue(a.scalafixConfig, parent.scalafixConfig),
      scalafixIvyDeps = extendValues(a.scalafixIvyDeps, parent.scalafixIvyDeps),
      scoverageVersion = extendValue(a.scoverageVersion, parent.scoverageVersion),
      branchCoverageMin = extendValue(a.branchCoverageMin, parent.branchCoverageMin),
      statementCoverageMin = extendValue(a.statementCoverageMin, parent.statementCoverageMin),
      mimaPreviousVersions = extendValues(a.mimaPreviousVersions, parent.mimaPreviousVersions),
      mimaPreviousArtifacts = extendValues(a.mimaPreviousArtifacts, parent.mimaPreviousArtifacts),
      mimaCheckDirection = extendValue(a.mimaCheckDirection, parent.mimaCheckDirection),
      mimaBinaryIssueFilters =
        extendValues(a.mimaBinaryIssueFilters, parent.mimaBinaryIssueFilters),
      mimaBackwardIssueFilters =
        extendValues(a.mimaBackwardIssueFilters, parent.mimaBackwardIssueFilters),
      mimaForwardIssueFilters =
        extendValues(a.mimaForwardIssueFilters, parent.mimaForwardIssueFilters),
      mimaExcludeAnnotations =
        extendValues(a.mimaExcludeAnnotations, parent.mimaExcludeAnnotations),
      mimaReportSignatureProblems =
        extendValue(a.mimaReportSignatureProblems, parent.mimaReportSignatureProblems)
    )

    val (baseHierarchy, testHierarchy) = baseTestHierarchy.unzip

    def canExtend(module: ModuleSpec) = module.supertypes.exists(baseHierarchy.contains)

    def isTestModule(module: ModuleSpec) = module.supertypes.exists(testHierarchy.contains)

    def recExtendModule(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = {
      var module = a
      if (canExtend(module)) {
        module = extendModule(module.copy(imports = "millbuild.*" +: module.imports), parent)
      }
      val children = module.children.map { child =>
        if (parent.children.nonEmpty && isTestModule(child))
          extendModule(child, parent.children.head)
        else recExtendModule(child, parent)
      }
      module.copy(children = children)
    }

    val extendingModules = packages.flatMap(_.module.tree).filter(canExtend)
    if (extendingModules.length > 1) {
      var baseModule = extendingModules
        .reduce(parentModule(_, _, "ProjectBaseModule", baseHierarchy))
      val testModule = extendingModules.flatMap(_.children.filter(isTestModule))
        .reduceOption(parentModule(_, _, "ProjectBaseTests", testHierarchy))
        .map { testModule =>
          val testSupertypes = mutable.Buffer(testModule.supertypes*)
          val i = baseHierarchy.indexWhere(baseModule.supertypes.contains)
          val j = testHierarchy.indexWhere(testSupertypes.contains)
          if (i < j) {
            val k = testSupertypes.indexWhere(testHierarchy.contains)
            testSupertypes(k) = testHierarchy(i)
          }
          if (
            testSupertypes.contains("ScoverageTests") &&
            !baseModule.supertypes.contains("ScoverageModule")
          ) {
            testSupertypes -= "ScoverageTests"
          }
          testModule.copy(supertypes = testSupertypes.toSeq)
        }
      baseModule = baseModule.copy(children = testModule.toSeq)
      this.baseModule = Some(baseModule)
      packages = packages.map(pkg => pkg.copy(module = recExtendModule(pkg.module, baseModule)))
    }
  }

  def writeFiles(
      declarative: Boolean,
      merge: Boolean,
      workspace: os.Path = os.pwd,
      millJvmVersion: Option[String] = None,
      millJvmOpts: Seq[String] = Nil,
      metaMvnDeps: Seq[String] = Nil
  ): Unit = {
    // Recursively construct package tree "filling-in" any gaps
    var packages0 = {
      def recPackageTree(dir: os.SubPath): Seq[PackageSpec] = {
        val rootPackage = packages.find(_.dir == dir).getOrElse(PackageSpec.root(dir))
        val nestedPackages = packages.collect {
          case pkg if pkg.dir.startsWith(dir) && pkg.dir != dir =>
            os.sub / pkg.dir.segments.take(dir.segments.length + 1)
        }.distinct.flatMap(recPackageTree)
        rootPackage +: nestedPackages
      }

      recPackageTree(os.sub)
    }
    if (merge) {
      // Recursively add nested module trees as children of parent package module
      val rootPackage +: nestedPackages = packages0.runtimeChecked
      def recModuleTree(parentDir: os.SubPath): Seq[ModuleSpec] = {
        val childDepth = parentDir.segments.length + 1
        nestedPackages.collect {
          case child
              if child.dir.startsWith(parentDir) && child.dir.segments.length == childDepth =>
            child.module.copy(children = child.module.children ++ recModuleTree(child.dir))
        }
      }

      packages0 = Seq(rootPackage.copy(module =
        rootPackage.module.copy(children =
          rootPackage.module.children ++ recModuleTree(rootPackage.dir)
        )
      ))
    }
    val millVersion0 = if (sys.env.contains("MILL_UNSTABLE_VERSION")) "SNAPSHOT" else millVersion
    val millJvmVersion0 = millJvmVersion.getOrElse {
      val path = workspace / ".mill-jvm-version"
      if (os.exists(path)) os.read(path) else "system"
    }

    val existingBuildFiles = Util.buildFiles(workspace)
    if (existingBuildFiles.nonEmpty) {
      println("removing existing build files ...")
      for (file <- existingBuildFiles) do os.remove(file)
    }

    if (declarative) {
      // YAML build scripts require meta-build Scala files
      BuildGenScala.writeMetaBuildFiles(
        workspace = workspace,
        baseModule = baseModule,
        depNames = depNames,
        mvnDeps = metaMvnDeps
      )
      BuildGenYaml.writeBuildFiles(
        workspace = workspace,
        packages = packages0,
        millVersion = millVersion0,
        millJvmVersion = millJvmVersion0,
        millJvmOpts = millJvmOpts
      )
    } else {
      // metaMvnDeps are added in root build file header
      BuildGenScala.writeMetaBuildFiles(
        workspace = workspace,
        baseModule = baseModule,
        depNames = depNames
      )
      BuildGenScala.writeBuildFiles(
        workspace = workspace,
        packages = packages0,
        millVersion = millVersion0,
        millJvmVersion = millJvmVersion0,
        millJvmOpts = millJvmOpts,
        metaMvnDeps = metaMvnDeps
      )
    }
  }
}
