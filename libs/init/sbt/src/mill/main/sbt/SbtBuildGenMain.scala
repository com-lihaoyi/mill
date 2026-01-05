package mill.main.sbt

import mill.main.buildgen.*
import mill.main.buildgen.ModuleSpec.*
import mill.main.sbt.BuildInfo.*
import pprint.Util.literalize

import scala.util.Properties.isWin
import scala.util.Using

object SbtBuildGenMain {

  def main(args: Array[String]): Unit = mainargs.Parser(this).runOrExit(args.toSeq)

  @mainargs.main(doc = "Generates Mill build files that are derived from an SBT build.")
  def init(
      @mainargs.arg(doc = "path to custom SBT executable")
      sbt: Option[String],
      @mainargs.arg(doc = "command line arguments for SBT")
      sbtArgs: mainargs.Leftover[String],
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag,
      @mainargs.arg(doc = "Coursier JVM ID to assign to mill-jvm-version key in the build header")
      millJvmId: String = "system"
  ): Unit = {
    println("converting sbt build")

    val sbtCmd = sbt.getOrElse {
      def systemSbtExists(cmd: String) = os.call((cmd, "--help"), check = false).exitCode == 1
      if (isWin) {
        val cmd = "sbt.bat"
        if (systemSbtExists(cmd)) cmd else sys.error(s"No system wide $cmd found")
      } else {
        val exes = Seq("sbt", "sbtx")
        val cmd = "sbt"
        exes.collectFirst {
          case exe if os.isFile(os.pwd / exe) => s"./$exe"
        }.orElse {
          Option.when(systemSbtExists(cmd))(cmd)
        }.getOrElse {
          sys.error(
            s"No sbt executable (${exes.mkString("./", ", ./", "")}), or system-wide $cmd found"
          )
        }
      }
    }
    val exportPluginJar =
      Using.resource(getClass.getResourceAsStream(exportpluginAssemblyResource))(
        os.temp(_, suffix = ".jar")
      )
    val sbtMetaDir = os.pwd / "project"
    if (!os.exists(sbtMetaDir)) os.makeDir(sbtMetaDir)
    os.temp(
      s"""addSbtPlugin("com.lihaoyi" % "mill-libs-init-sbt-exportplugin" % "dummy-version" from ${
          literalize(exportPluginJar.wrapped.toUri.toString)
        })""",
      dir = sbtMetaDir,
      suffix = ".sbt"
    )
    val exportDir = os.temp.dir()
    try {
      os.proc(
        sbtCmd,
        sbtArgs.value,
        s"-DmillInitExportDir=$exportDir",
        // Run task with cross-build prefix to export data for all cross Scala versions.
        "+millInitExportBuild"
      ).call(stdout = os.Inherit)
    } catch {
      case e: os.SubprocessException =>
        val message =
          "The sbt command to run the `millInitExportBuild` sbt task has failed, " +
            "please check out the following solutions and try again:\n" +
            "1. check whether your existing sbt build works properly;\n" +
            "2. make sure there are no other sbt processes running;\n" +
            "3. clear your build output and cache;\n" +
            s"4. update the project's sbt version to the latest or our tested version v$sbtVersion;\n" +
            "5. check whether you have the appropriate Java version.\n"
        throw RuntimeException(message, e)
    }
    val exportedBuild = os.list.stream(exportDir)
      .map(path => upickle.default.read[SbtModuleSpec](path.toNIO)).toSeq
    var packages = exportedBuild.groupMap(_.root)(_.module).map {
      case (Left(dir), Seq(module)) => PackageSpec(dir, module)
      case (Left(dir), crossVersionModules) => PackageSpec(dir, toCrossModule(crossVersionModules))
      case (Right(pkg), modules) =>
        val children = modules.groupBy(_.name).map {
          case (_, Seq(module)) => module
          case (_, crossVersionModules) => toCrossModule(crossVersionModules)
        }.toSeq
        pkg.copy(module = pkg.module.copy(children = children))
    }.toSeq
    packages = normalizeBuild(packages)

    val (depNames, packages0) =
      if (noMeta.value) (Nil, packages) else BuildGen.withNamedDeps(packages)
    val (baseModule, packages1) = Option.when(!noMeta.value)(BuildGen.withBaseModule(
      packages0,
      Seq("CrossSbtModule" -> "CrossSbtTests", "CrossSbtPlatformModule" -> "CrossSbtPlatformTests")
    ).orElse(BuildGen.withBaseModule(
      packages0,
      Seq("SbtModule" -> "SbtTests", "SbtPlatformModule" -> "SbtPlatformTests")
    ))).flatten.fold((None, packages0))((base, packages) => (Some(base), packages))
    val millJvmOpts = {
      val file = os.pwd / ".jvmopts"
      if (os.isFile(file)) os.read.lines(file)
        .map(_.trim)
        .filter(s => s.nonEmpty && !s.startsWith("#"))
        .flatMap(_.split("\\s"))
      else Nil
    }
    val mvnDeps = packages.iterator.flatMap(_.module.tree).flatMap(_.supertypes).distinct.collect {
      case "JmhModule" => "com.lihaoyi::mill-contrib-jmh:$MILL_VERSION"
      case "ScoverageModule" => "com.lihaoyi::mill-contrib-scoverage:$MILL_VERSION"
      case "ScalafixModule" => scalafixDep
    }.toSeq
    BuildGen.writeBuildFiles(
      packages1,
      merge.value,
      depNames,
      baseModule,
      millJvmId,
      millJvmOpts,
      mvnDeps
    )
  }

  private def toCrossModule(crossVersionModules: Seq[ModuleSpec]) = {
    def combineValue[A](a: Value[A], b: Value[A]) = Value(
      if (a.base == b.base) a.base else None,
      a.cross ++ b.cross
    )
    def combineValues[A](a: Values[A], b: Values[A]) = Values(
      a.base.intersect(b.base),
      a.cross ++ b.cross,
      appendSuper = a.appendSuper && b.appendSuper
    )
    def combineModule(a: ModuleSpec, b: ModuleSpec): ModuleSpec = ModuleSpec(
      name = a.name,
      imports = (a.imports ++ b.imports).distinct,
      supertypes = a.supertypes.intersect(b.supertypes),
      crossKeys = a.crossKeys ++ b.crossKeys,
      snippets = a.snippets.intersect(b.snippets),
      repositories = combineValues(a.repositories, b.repositories),
      mvnDeps = combineValues(a.mvnDeps, b.mvnDeps),
      compileMvnDeps = combineValues(a.compileMvnDeps, b.compileMvnDeps),
      runMvnDeps = combineValues(a.runMvnDeps, b.runMvnDeps),
      bomMvnDeps = combineValues(a.bomMvnDeps, b.bomMvnDeps),
      depManagement = combineValues(a.depManagement, b.depManagement),
      moduleDeps = combineValues(a.moduleDeps, b.moduleDeps),
      compileModuleDeps = combineValues(a.compileModuleDeps, b.compileModuleDeps),
      runModuleDeps = combineValues(a.runModuleDeps, b.runModuleDeps),
      bomModuleDeps = combineValues(a.bomModuleDeps, b.bomModuleDeps),
      javacOptions = combineValues(a.javacOptions, b.javacOptions),
      artifactName = combineValue(a.artifactName, b.artifactName),
      pomPackagingType = combineValue(a.pomPackagingType, b.pomPackagingType),
      pomParentProject = combineValue(a.pomParentProject, b.pomParentProject),
      pomSettings = combineValue(a.pomSettings, b.pomSettings),
      publishVersion = combineValue(a.publishVersion, b.publishVersion),
      versionScheme = combineValue(a.versionScheme, b.versionScheme),
      publishProperties = combineValues(a.publishProperties, b.publishProperties),
      jmhCoreVersion = combineValue(a.jmhCoreVersion, b.jmhCoreVersion),
      scalacOptions = combineValues(a.scalacOptions, b.scalacOptions),
      scalacPluginMvnDeps = combineValues(a.scalacPluginMvnDeps, b.scalacPluginMvnDeps),
      scalaJSVersion = combineValue(a.scalaJSVersion, b.scalaJSVersion),
      moduleKind = combineValue(a.moduleKind, b.moduleKind),
      scalaNativeVersion = combineValue(a.scalaNativeVersion, b.scalaNativeVersion),
      testFramework = combineValue(a.testFramework, b.testFramework),
      scalafixIvyDeps = combineValues(a.scalafixIvyDeps, b.scalafixIvyDeps),
      scoverageVersion = combineValue(a.scoverageVersion, b.scoverageVersion),
      branchCoverageMin = combineValue(a.branchCoverageMin, b.branchCoverageMin),
      statementCoverageMin = combineValue(a.statementCoverageMin, b.statementCoverageMin),
      children =
        a.children.map(a => b.children.find(_.name == a.name).fold(a)(combineModule(a, _)))
    )
    def normalizeValue[A](a: Value[A]): Value[A] = a.copy(cross = a.cross.collect {
      case kv @ (_, v) if !a.base.contains(v) => kv
    })
    def normalizeValues[A](a: Values[A]): Values[A] =
      a.copy(cross = a.cross.map((k, v) => (k, v.diff(a.base))).filter(_._2.nonEmpty))
    def normalizeModule(a: ModuleSpec): ModuleSpec = a.copy(
      repositories = normalizeValues(a.repositories),
      mvnDeps = normalizeValues(a.mvnDeps),
      compileMvnDeps = normalizeValues(a.compileMvnDeps),
      runMvnDeps = normalizeValues(a.runMvnDeps),
      bomMvnDeps = normalizeValues(a.bomMvnDeps),
      depManagement = normalizeValues(a.depManagement),
      moduleDeps = normalizeValues(a.moduleDeps),
      compileModuleDeps = normalizeValues(a.compileModuleDeps),
      runModuleDeps = normalizeValues(a.runModuleDeps),
      bomModuleDeps = normalizeValues(a.bomModuleDeps),
      javacOptions = normalizeValues(a.javacOptions),
      artifactName = normalizeValue(a.artifactName),
      pomPackagingType = normalizeValue(a.pomPackagingType),
      pomParentProject = normalizeValue(a.pomParentProject),
      pomSettings = normalizeValue(a.pomSettings),
      publishVersion = normalizeValue(a.publishVersion),
      versionScheme = normalizeValue(a.versionScheme),
      publishProperties = normalizeValues(a.publishProperties),
      jmhCoreVersion = normalizeValue(a.jmhCoreVersion),
      scalacOptions = normalizeValues(a.scalacOptions),
      scalacPluginMvnDeps = normalizeValues(a.scalacPluginMvnDeps),
      scalaJSVersion = normalizeValue(a.scalaJSVersion),
      moduleKind = normalizeValue(a.moduleKind),
      scalaNativeVersion = normalizeValue(a.scalaNativeVersion),
      testFramework = normalizeValue(a.testFramework),
      scalafixIvyDeps = normalizeValues(a.scalafixIvyDeps),
      scoverageVersion = normalizeValue(a.scoverageVersion),
      branchCoverageMin = normalizeValue(a.branchCoverageMin),
      statementCoverageMin = normalizeValue(a.statementCoverageMin),
      children = a.children.map(normalizeModule)
    )
    normalizeModule(crossVersionModules.reduce(combineModule)).withAlias()
  }

  private def normalizeBuild(packages: Seq[PackageSpec]) = {
    val moduleLookup: PartialFunction[ModuleDep, ModuleSpec] =
      packages.flatMap(_.moduleTree).toMap.compose[ModuleDep](_.dir)
    def filterModuleDeps(values: Values[ModuleDep]) = {
      import values.*
      values.copy(
        base.filter(moduleLookup.isDefinedAt),
        cross.map((k, v) => (k, v.filter(moduleLookup.isDefinedAt))).filter(_._2.nonEmpty)
      )
    }
    val platformedMvnDeps = packages.flatMap(_.module.tree).flatMap { module =>
      import module.*
      Seq(mvnDeps, compileMvnDeps, runMvnDeps, scalacPluginMvnDeps)
    }.flatMap(values => values.base ++ values.cross.flatMap(_._2)).filter(_.cross.platformed).toSet
    def toPlatformedMvnDep(dep: MvnDep) = {
      val dep0 = if (dep.cross.platformed) dep
      else dep.copy(cross = dep.cross match {
        case v: CrossVersion.Constant => v.copy(platformed = true)
        case v: CrossVersion.Binary => v.copy(platformed = true)
        case v: CrossVersion.Full => v.copy(platformed = true)
      })
      if (platformedMvnDeps.contains(dep0)) dep0 else dep
    }
    def toPlatformedMvnDeps(deps: Values[MvnDep]) = deps.copy(
      base = deps.base.map(toPlatformedMvnDep),
      cross = deps.cross.map((k, v) => (k, v.map(toPlatformedMvnDep)))
    )
    def recMvnDeps(module: ModuleSpec): Seq[MvnDep] = module.mvnDeps.base ++ module.moduleDeps.base
      .flatMap(dep => recMvnDeps(moduleLookup(dep)))
    packages.map(pkg =>
      pkg.copy(module = pkg.module.recMap { module =>
        import module.*
        var module0 = module.copy(
          moduleDeps = filterModuleDeps(moduleDeps),
          compileModuleDeps = filterModuleDeps(compileModuleDeps),
          runModuleDeps = filterModuleDeps(runModuleDeps),
          mvnDeps = toPlatformedMvnDeps(mvnDeps),
          compileMvnDeps = toPlatformedMvnDeps(compileMvnDeps),
          runMvnDeps = toPlatformedMvnDeps(runMvnDeps),
          scalacPluginMvnDeps = toPlatformedMvnDeps(scalacPluginMvnDeps)
        )
        if (module0.testFramework.base.contains("")) {
          ModuleSpec.testModuleMixin(recMvnDeps(module0)).foreach { mixin =>
            module0 = module0.copy(supertypes = module0.supertypes :+ mixin, testFramework = None)
          }
        }
        module0
      })
    )
  }
}
