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
      customSbt: Option[String],
      @mainargs.arg(doc = "Coursier JVM ID to assign to mill-jvm-version key in the build header")
      millJvmId: String = "system",
      @mainargs.arg(doc = "merge package.mill files in to the root build.mill file")
      merge: mainargs.Flag,
      @mainargs.arg(doc = "disable generating meta-build files")
      noMeta: mainargs.Flag
  ): Unit = {
    println("converting sbt build")

    val sbtCmd = customSbt.getOrElse {
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
    var exportedBuild = os.list.stream(exportDir)
      .map(path => upickle.default.read[SbtModuleSpec](path.toNIO)).toSeq
    exportedBuild = normalizeSbtBuild(exportedBuild)
    val packages = exportedBuild.groupMap(_.sharedBaseDir)(_.module).map {
      case (Left(dir), Seq(module)) => PackageSpec(dir, module)
      case (Left(dir), crossVersionSpecs) => PackageSpec(dir, toCrossModule(crossVersionSpecs))
      case (Right(dir), modules) =>
        val children = modules.groupBy(_.name).map {
          case (_, Seq(module)) => module
          case (_, crossVersionSpecs) => toCrossModule(crossVersionSpecs)
        }.toSeq
        PackageSpec.root(dir, children)
    }.toSeq

    val (depNames, packages0) =
      if (noMeta.value) (Nil, packages) else BuildGen.withNamedDeps(packages)
    val (baseModule, packages1) = Option.when(!noMeta.value)(
      BuildGen.withBaseModule(packages0, "CrossSbtPlatformTests", "CrossSbtPlatformModule")
        .orElse(BuildGen.withBaseModule(
          packages0,
          "CrossSbtTests",
          "CrossSbtModule",
          "CrossSbtPlatformModule"
        ))
        .orElse(BuildGen.withBaseModule(packages0, "SbtPlatformTests", "SbtPlatformModule"))
        .orElse(BuildGen.withBaseModule(packages0, "SbtTests", "SbtModule", "SbtPlatformModule"))
    ).flatten.fold((None, packages0))((base, packages) => (Some(base), packages))
    val millJvmOpts = {
      val file = os.pwd / ".jvmopts"
      if (os.isFile(file)) os.read.lines(file)
        .map(_.trim)
        .filter(s => s.nonEmpty && !s.startsWith("#"))
        .flatMap(_.split("\\s"))
      else Nil
    }
    BuildGen.writeBuildFiles(packages1, millJvmId, merge.value, depNames, baseModule, millJvmOpts)
  }

  private def normalizeSbtBuild(sbtModules: Seq[SbtModuleSpec]) = {
    val platformedDeps = sbtModules.iterator.flatMap(spec => spec.module +: spec.module.test.toSeq)
      .flatMap { module =>
        import module.*
        Seq(mvnDeps, compileMvnDeps, runMvnDeps, scalacPluginMvnDeps)
      }
      .flatMap(values => values.base ++ values.cross.flatMap(_._2))
      .filter(_.cross.platformed).toSet
    def updateDep(dep: MvnDep) = {
      val dep0 = if (dep.cross.platformed) dep
      else dep.copy(cross = dep.cross match {
        case v: CrossVersion.Constant => v.copy(platformed = true)
        case v: CrossVersion.Binary => v.copy(platformed = true)
        case v: CrossVersion.Full => v.copy(platformed = true)
      })
      if (platformedDeps.contains(dep0)) dep0 else dep
    }
    def updateDeps(deps: Values[MvnDep]) = deps.copy(
      base = deps.base.map(updateDep),
      cross = deps.cross.map((k, v) => (k, v.map(updateDep)))
    )
    def updateModule0(module: ModuleSpec) = {
      import module.*
      module.copy(
        mvnDeps = updateDeps(mvnDeps),
        compileMvnDeps = updateDeps(compileMvnDeps),
        runMvnDeps = updateDeps(runMvnDeps),
        scalacPluginMvnDeps = updateDeps(scalacPluginMvnDeps)
      )
    }
    def updateModule(module: ModuleSpec): ModuleSpec = {
      if (module.supertypes.forall(s => s == "ScalaJSModule" || s == "ScalaNativeModule"))
        module
      else updateModule0(module).copy(test = module.test.map(updateModule0))
    }

    sbtModules.map(spec => spec.copy(module = updateModule(spec.module)))
  }

  private def toCrossModule(crossVersionSpecs: Seq[ModuleSpec]) = {
    def combineValue[A](a: Value[A], b: Value[A]) = Value(
      if (a.base == b.base) a.base else None,
      a.cross ++ b.cross
    )
    def combineValues[A](a: Values[A], b: Values[A]) = Values(
      a.base.intersect(b.base),
      a.cross ++ b.cross,
      appendSuper = a.appendSuper && b.appendSuper
    )
    def combineModule0(a: ModuleSpec, b: ModuleSpec) = ModuleSpec(
      name = a.name,
      imports = (a.imports ++ b.imports).distinct,
      supertypes = a.supertypes.intersect(b.supertypes),
      mixins = if (a.mixins == b.mixins) a.mixins else Nil,
      crossKeys = a.crossKeys ++ b.crossKeys,
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
      scalacOptions = combineValues(a.scalacOptions, b.scalacOptions),
      scalacPluginMvnDeps = combineValues(a.scalacPluginMvnDeps, b.scalacPluginMvnDeps),
      scalaJSVersion = combineValue(a.scalaJSVersion, b.scalaJSVersion),
      moduleKind = combineValue(a.moduleKind, b.moduleKind),
      scalaNativeVersion = combineValue(a.scalaNativeVersion, b.scalaNativeVersion),
      sourcesRootFolders = combineValues(a.sourcesRootFolders, b.sourcesRootFolders),
      testParallelism = combineValue(a.testParallelism, b.testParallelism),
      testSandboxWorkingDir = combineValue(a.testSandboxWorkingDir, b.testSandboxWorkingDir)
    )
    def combineModule(a: ModuleSpec, b: ModuleSpec): ModuleSpec = combineModule0(a, b).copy(
      test = a.test.zip(b.test).map(combineModule0)
    )
    def normalizeValue[A](a: Value[A]): Value[A] = a.copy(cross = a.cross.collect {
      case kv @ (_, v) if !a.base.contains(v) => kv
    })
    def normalizeValues[A](a: Values[A]): Values[A] =
      a.copy(cross = a.cross.map((k, v) => (k, v.diff(a.base))).filter(_._2.nonEmpty))
    def normalizeModule0(a: ModuleSpec) = a.copy(
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
      scalacOptions = normalizeValues(a.scalacOptions),
      scalacPluginMvnDeps = normalizeValues(a.scalacPluginMvnDeps),
      scalaJSVersion = normalizeValue(a.scalaJSVersion),
      moduleKind = normalizeValue(a.moduleKind),
      scalaNativeVersion = normalizeValue(a.scalaNativeVersion),
      sourcesRootFolders = normalizeValues(a.sourcesRootFolders),
      testParallelism = normalizeValue(a.testParallelism),
      testSandboxWorkingDir = normalizeValue(a.testSandboxWorkingDir)
    )
    def normalizeModule(a: ModuleSpec): ModuleSpec =
      normalizeModule0(a).copy(test = a.test.map(normalizeModule0))
    normalizeModule(crossVersionSpecs.reduce(combineModule))
  }
}
