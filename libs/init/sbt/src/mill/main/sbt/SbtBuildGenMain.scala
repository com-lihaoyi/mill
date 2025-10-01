package mill.main.sbt

import mainargs.ParserForClass
import mill.constants.Util.isWindows
import mill.main.buildgen.*
import mill.main.sbt.BuildInfo.exportscriptAssemblyResource
import pprint.Util.literalize

import scala.util.Using

/**
 * Converts an SBT build by generating module configurations using a custom "apply" script.
 * @see [[SbtBuildGenArgs Command line arguments]]
 */
object SbtBuildGenMain {

  def main(args: Array[String]): Unit = {
    println("converting sbt build")

    val args0 = summon[ParserForClass[SbtBuildGenArgs]].constructOrExit(args.toSeq)
    import args0.{getClass as _, *}

    val cmd = sbtCmd.getOrElse(
      Either.cond(
        !isWindows,
        Seq("sbt", ".sbtx").collectFirst {
          case exe if os.exists(os.pwd / exe) => s"./$exe"
        }.toLeft("sbt"),
        "sbt.bat"
      ).flatten.fold(
        cmd =>
          if (os.call((cmd, "--help"), check = false).exitCode == 1)
            sys.error(s"no system-wide $cmd found")
          else cmd,
        identity
      )
    )
    val exportScriptJar = Using.resource(
      getClass.getResourceAsStream(exportscriptAssemblyResource)
    )(os.temp(_, suffix = ".jar"))
    val exportDir = os.temp.dir()
    // run task with "+" prefix to export data per project per cross Scala version
    val exportScript = Seq(
      s"set SettingKey[File](\"millInitExportArgs\") in Global := file(${literalize(exportDir.toString())})",
      s"apply -cp ${literalize(exportScriptJar.toString())} mill.main.sbt.ExportSbtBuildScript",
      s"+millInitExportSbtModules"
    )
    os.proc(cmd, exportScript).call(stdout = os.Inherit, stderr = os.Inherit)

    // ((moduleDir, isCrossPlatform), ModuleRepr)
    type SbtModuleRepr = ((Seq[String], Boolean), ModuleRepr)
    val sbtModules = os.list.stream(exportDir)
      .map(path => upickle.default.read[SbtModuleRepr](path.toNIO))
      .toSeq
    if (sbtModules.isEmpty) sys.error(s"no modules found using $cmd")

    def toModule(crossVersionModules: Seq[ModuleRepr]): ModuleRepr =
      if (crossVersionModules.tail.isEmpty) crossVersionModules.head // not cross module
      else {
        val module = crossVersionModules.iterator.reduce((m1, m2) =>
          m1.copy(
            supertypes = m1.supertypes.intersect(m2.supertypes),
            configs = ModuleConfig.abstracted(m1.configs, m2.configs),
            crossConfigs = m1.crossConfigs ++ m2.crossConfigs,
            testModule = (m1.testModule, m2.testModule) match
              case (Some(m1), Some(m2)) =>
                Some(m1.copy(
                  configs = ModuleConfig.abstracted(m1.configs, m2.configs),
                  crossConfigs = m1.crossConfigs ++ m2.crossConfigs
                ))
              case _ => None
          )
        )
        module.copy(
          crossConfigs = module.crossConfigs.map((k, v) =>
            (k, ModuleConfig.inherited(v, module.configs))
          ).sortBy(_._1),
          testModule = module.testModule.map(testModule =>
            testModule.copy(crossConfigs =
              testModule.crossConfigs.map((k, v) =>
                (k, ModuleConfig.inherited(v, testModule.configs))
              ).sortBy(_._1)
            )
          )
        )
      }
    def normalizePlatformDeps(crossPlatformModules: Seq[ModuleRepr]) = {
      // Replicate %%% syntax by "platforming" dependencies for the JVM cross module.
      // Otherwise, such a dependency will manifest as 2 entries in the Deps object.
      val (jvmModules, nonJvmModules) = crossPlatformModules.partition(_.segments.last == "jvm")
      val platformedMvnDeps = nonJvmModules.iterator
        .flatMap(module =>
          module.configs.iterator ++ module.crossConfigs.iterator.flatMap(_._2) ++
            module.testModule.iterator.flatMap(testModule =>
              testModule.configs.iterator ++ testModule.crossConfigs.iterator.flatMap(_._2)
            )
        )
        .flatMap {
          case c: JavaModuleConfig => c.mvnDeps ++ c.compileMvnDeps ++ c.runMvnDeps
          case c: ScalaModuleConfig => c.scalacPluginMvnDeps
          case _ => Nil
        }
        .filter(_.cross.platformed)
        .toSet
      def updateDep(dep: ModuleConfig.MvnDep) = {
        val dep0 = if (dep.cross.platformed) dep
        else dep.copy(cross = dep.cross match {
          case v: ModuleConfig.CrossVersion.Constant => v.copy(platformed = true)
          case v: ModuleConfig.CrossVersion.Binary => v.copy(platformed = true)
          case v: ModuleConfig.CrossVersion.Full => v.copy(platformed = true)
        })
        if (platformedMvnDeps.contains(dep0)) dep0 else dep
      }
      def updateConfig(config: ModuleConfig) = config match {
        case c: JavaModuleConfig => c.copy(
            mvnDeps = c.mvnDeps.map(updateDep),
            compileMvnDeps = c.compileMvnDeps.map(updateDep),
            runMvnDeps = c.runMvnDeps.map(updateDep)
          )
        case c: ScalaModuleConfig => c.copy(
            scalacPluginMvnDeps = c.scalacPluginMvnDeps.map(updateDep)
          )
        case c => c
      }
      jvmModules.map(module =>
        module.copy(
          configs = module.configs.map(updateConfig),
          crossConfigs = module.crossConfigs.map((k, v) => (k, v.map(updateConfig))),
          testModule = module.testModule.map(testModule =>
            testModule.copy(
              configs = testModule.configs.map(updateConfig),
              crossConfigs = testModule.crossConfigs.map((k, v) => (k, v.map(updateConfig)))
            )
          )
        )
      ) ++ nonJvmModules
    }

    val packages = sbtModules.groupMap(_._1)(_._2).iterator.map {
      case ((_, false), crossVersionModules) => Tree(toModule(crossVersionModules))
      case ((moduleDir, _), crossPlatformModules) => Tree(
          root = ModuleRepr(moduleDir),
          children = normalizePlatformDeps(
            crossPlatformModules
              .groupBy(_.segments).iterator
              .map((_, crossVersionModules) => toModule(crossVersionModules)).toSeq
          ).sortBy(os.sub / _.segments).map(Tree(_))
        )
    }.toSeq

    val sbtJvmOpts = {
      def lines(file: os.Path) = if (os.isFile(file))
        os.read.lines.stream(file).map(_.trim).filter(_.nonEmpty).toSeq
      else Nil
      val jvmOpts = lines(os.pwd / ".jvmopts")
        .flatMap(s => if (s.startsWith("#")) Nil else s.split(" "))
      val sbtOptsJ = lines(os.pwd / ".sbtopts")
        .flatMap(s => if (s.startsWith("-J")) s.substring(2).split(" ") else Nil)
      jvmOpts ++ sbtOptsJ
    }

    var build = BuildRepr.fill(packages).copy(millJvmOpts = sbtJvmOpts)
    if (merge.value) build = build.merged
    if (!noMeta.value) build = build.withMetaBuild
    BuildWriter(build, renderCrossValueInTask = "scalaVersion()").writeFiles()
  }
}

@mainargs.main
case class SbtBuildGenArgs(
    @mainargs.arg(doc = "merge generated build files")
    merge: mainargs.Flag,
    @mainargs.arg(doc = "disable generating meta-build files")
    noMeta: mainargs.Flag,
    @mainargs.arg(doc = "path to sbt executable")
    sbtCmd: Option[String]
)
object SbtBuildGenArgs {
  given ParserForClass[SbtBuildGenArgs] = ParserForClass.apply
}
