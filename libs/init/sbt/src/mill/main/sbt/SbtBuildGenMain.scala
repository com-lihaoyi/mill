package mill.main.sbt

import mainargs.ParserForClass
import mill.constants.Util.isWindows
import mill.main.buildgen.*
import mill.main.buildgen.ModuleConfig.*
import mill.main.sbt.BuildInfo.*
import pprint.Util.literalize

import scala.util.Using

/**
 * Application that imports an SBT build to Mill.
 */
object SbtBuildGenMain {

  /**
   * @see [[SbtBuildGenArgs Command line arguments]]
   */
  def main(args: Array[String]): Unit = {
    println("converting sbt build")

    val args0 = summon[ParserForClass[SbtBuildGenArgs]].constructOrExit(args.toSeq)
    import args0.*

    val sbtCmd = customSbt.getOrElse(defaultSbt.fold(sys.error, identity))
    addExportPlugin()
    val exportDir = os.temp.dir()
    try {
      // Run task with cross-build prefix to export data for all cross Scala versions.
      os.proc(sbtCmd, s"-DmillInitExportDir=$exportDir", "+millInitExportBuild")
        .call(stdout = os.Inherit)
    } catch {
      case e: os.SubprocessException => throw RuntimeException(
          "The sbt command to run the `millInitExportBuild` sbt task has failed, " +
            s"please check out the following solutions and try again:\n" +
            s"1. check whether your existing sbt build works properly;\n" +
            s"2. make sure there are no other sbt processes running;\n" +
            s"3. clear your build output and cache;\n" +
            s"4. update the project's sbt version to the latest or our tested version v$sbtVersion;\n" +
            "5. check whether you have the appropriate Java version.\n",
          e
        )
    }

    // For cross projects, multiple specs are exported with settings for each Scala version.
    type ExportedPackageSpec = (
        // A flag that is set for cross-platform projects and the package location.
        // When the flag is set, the location is the shared root directory for member projects.
        // Otherwise, the location is the project base directory.
        (Boolean, Seq[String]),
        ModuleSpec
    )
    val exportedBuild = os.list.stream(exportDir)
      .map(path => upickle.default.read[ExportedPackageSpec](path.toNIO))
      .toSeq

    // segregate the exported build into packages
    val packages = exportedBuild.groupMap(_._1)(_._2).map {
      // package with cross-platform members
      case ((true, crossRootDir), modules) =>
        // segregate module specs for each member
        val nestedModules = modules.groupBy(_.name).map {
          // non-cross version member
          case (_, Seq(module)) => module
          // cross version member
          case (_, partialSpecs) => unifyCrossVersionConfigs(partialSpecs)
        }.toSeq
        val rootModule = ModuleSpec(
          name = crossRootDir.lastOption.getOrElse(os.pwd.last),
          nestedModules = normalizeJvmPlatformDeps(nestedModules)
        )
        PackageSpec(crossRootDir, rootModule)
      // package with non-cross module
      case ((_, moduleDir), Seq(module)) =>
        PackageSpec(moduleDir, module)
      // package with cross version module
      case ((_, moduleDir), partialSpecs) =>
        PackageSpec(moduleDir, unifyCrossVersionConfigs(partialSpecs))
    }.toSeq

    var build = BuildSpec.fill(packages).copy(millJvmOpts = sbtJvmOpts)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withMetaBuild
    BuildWriter(build, renderCrossValueInTask = "scalaVersion()").writeFiles()
  }

  def defaultSbt = {
    def systemSbtExists(cmd: String) =
      // The return code is somehow 1 instead of 0.
      os.call((cmd, "--help"), check = false).exitCode == 1
    if (isWindows) {
      val cmd = "sbt.bat"
      Either.cond(systemSbtExists(cmd), cmd, s"No system wide $cmd found")
    } else {
      val exes = Seq("sbt", "sbtx")
      val cmd = "sbt"
      exes.collectFirst {
        case exe if os.isFile(os.pwd / exe) => s"./$exe"
      }.orElse {
        Option.when(systemSbtExists(cmd))(cmd)
      }.toRight(
        s"No sbt executable (${exes.mkString("./", ", ./", "")}), or system-wide $cmd found"
      )
    }
  }

  def addExportPlugin() = {
    val pluginJar = Using.resource(getClass.getResourceAsStream(exportpluginAssemblyResource))(
      os.temp(_, suffix = ".jar")
    )
    val scriptDir = os.pwd / "project"
    if (!os.exists(scriptDir)) os.makeDir(scriptDir)
    os.temp(
      s"""addSbtPlugin("com.lihaoyi" % "mill-libs-init-sbt-exportplugin" % "dummy-version" from ${
          literalize(pluginJar.wrapped.toUri.toString)
        })""",
      dir = scriptDir,
      suffix = ".sbt"
    )
  }

  def sbtJvmOpts = {
    val file = os.pwd / ".jvmopts"
    if (os.isFile(file)) os.read.lines.stream(file)
      .map(_.trim)
      .filter(s => s.nonEmpty && !s.startsWith("#"))
      .flatMap(_.split("\\s+"))
      .toSeq
    else Nil
  }

  /**
   * Returns the full module specification by combining Scala version specific configurations.
   */
  def unifyCrossVersionConfigs(partials: Seq[ModuleSpec]) = {
    def combineSpecs(part1: ModuleSpec, part2: ModuleSpec): ModuleSpec = part1.copy(
      configs = abstractedConfigs(part1.configs, part2.configs),
      crossConfigs = part1.crossConfigs ++ part2.crossConfigs,
      nestedModules = part1.nestedModules.zip(part2.nestedModules).map(combineSpecs)
    )
    def normalizeCrossConfigs(spec: ModuleSpec) = spec.transform { module =>
      module.copy(crossConfigs =
        module.crossConfigs.map((k, v) => (k, inheritedConfigs(v, module.configs)))
      )
    }

    normalizeCrossConfigs(partials.reduce(combineSpecs))
  }

  /**
   * A transformation that sets the platformed flag for JVM cross-platform dependencies. This
   * prevents double entries when defining constants for dependencies.
   */
  def normalizeJvmPlatformDeps(members: Seq[ModuleSpec]) = {
    val (jvmMembers, nonJvmMembers) = members.partition(_.name == "jvm")
    val platformedDeps = nonJvmMembers
      .flatMap(_.sequence)
      .flatMap(module => module.configs ++ module.crossConfigs.flatMap(_._2))
      .flatMap {
        case c: JavaModule => c.mvnDeps ++ c.compileMvnDeps ++ c.runMvnDeps
        case c: ScalaModule => c.scalacPluginMvnDeps
        case _ => Nil
      }
      .filter(_.cross.platformed)
      .toSet

    def updateDep(dep: MvnDep) = {
      val dep0 = if (dep.cross.platformed) dep
      else dep.copy(cross = dep.cross match {
        case v: CrossVersion.Constant => v.copy(platformed = true)
        case v: CrossVersion.Binary => v.copy(platformed = true)
        case v: CrossVersion.Full => v.copy(platformed = true)
      })
      if (platformedDeps.contains(dep0)) dep0 else dep
    }
    def updateConfig(config: ModuleConfig) = config match {
      case c: JavaModule => c.copy(
          mvnDeps = c.mvnDeps.map(updateDep),
          compileMvnDeps = c.compileMvnDeps.map(updateDep),
          runMvnDeps = c.runMvnDeps.map(updateDep)
        )
      case c: ScalaModule => c.copy(
          scalacPluginMvnDeps = c.scalacPluginMvnDeps.map(updateDep)
        )
      case _ => config
    }
    def updateSpec(spec: ModuleSpec) = spec.transform { module =>
      module.copy(
        configs = module.configs.map(updateConfig),
        crossConfigs = module.crossConfigs.map((k, v) => (k, v.map(updateConfig)))
      )
    }

    jvmMembers.map(updateSpec) ++ nonJvmMembers
  }
}

@mainargs.main
case class SbtBuildGenArgs(
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "disable generating meta-build files")
    noMeta: mainargs.Flag,
    @mainargs.arg(doc = "path to sbt executable")
    customSbt: Option[String]
)
object SbtBuildGenArgs {
  given ParserForClass[SbtBuildGenArgs] = ParserForClass.apply
}
