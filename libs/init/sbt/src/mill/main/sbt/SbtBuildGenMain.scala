package mill.main.sbt

import mill.constants.Util.isWindows
import mill.main.buildgen.*
import pprint.Util.literalize

import scala.util.Using

@mainargs.main
case class SbtBuildGenMainArgs(
    @mainargs.arg(short = 'D')
    depsObjectName: String = "Deps",
    @mainargs.arg(short = 't')
    testModuleName: String = "test",
    sbtCmd: Option[String],
    sbtOptions: mainargs.Leftover[String]
)

object SbtBuildGenMain {

  def main(args: Array[String]): Unit = {
    println("converting sbt build")

    val args0 = mainargs.ParserForClass[SbtBuildGenMainArgs].constructOrExit(args.toSeq)
    import args0.{getClass as _, *}

    val jar =
      Using.resource(getClass.getResourceAsStream(BuildInfo.exportscriptAssemblyResource))(os.temp(
        _,
        suffix = ".jar"
      ))
    val exportDir = os.temp.dir()
    // https://github.com/JetBrains/sbt-structure/#sideloading
    val script = Seq(
      s"set SettingKey[(File, String)](\"millInitExportArgs\") in Global := (file(${literalize(exportDir.toString())}), ${literalize(testModuleName)})",
      s"apply -cp ${literalize(jar.toString())} mill.main.sbt.ExportSbtBuildScript",
      s"+millInitExportModule"
    )

    val cmd = sbtCmd.getOrElse:
      Either.cond(
        !isWindows,
        Seq("sbt", ".sbtx").collectFirst:
          case exe if os.exists(os.pwd / exe) => s"./$exe"
        .toLeft("sbt"),
        "sbt.bat"
      ).flatten.fold(
        cmd =>
          if (os.call((cmd, "--help"), check = false).exitCode == 1)
            sys.error(s"no system-wide $cmd found")
          else cmd,
        identity
      )
    os.proc(cmd, sbtOptions.value, script).call(stdout = os.Inherit, stderr = os.Inherit)

    // ((moduleDir, isCrossPlatform), module)
    type SbtModuleRepr = ((Seq[String], Boolean), ModuleRepr)
    val sbtModules =
      os.list.stream(exportDir).map(path =>
        upickle.default.read[SbtModuleRepr](path.toNIO)
      ).toSeq.distinct
    if (sbtModules.isEmpty) {
      println(s"no modules found using $cmd")
      return
    }

    def toModule(crossVersionModules: Seq[ModuleRepr]): ModuleRepr =
      if (crossVersionModules.tail.isEmpty) crossVersionModules.head
      else
        val module = crossVersionModules.iterator.reduce: (m1, m2) =>
          m1.copy(
            configs = ModuleConfig.abstracted(m1.configs, m2.configs),
            crossConfigs = m1.crossConfigs ++ m2.crossConfigs,
            testModule = (m1.testModule, m2.testModule) match
              case (Some(m1), Some(m2)) =>
                Some(m1.copy(
                  configs = ModuleConfig.abstracted(m1.configs, m2.configs),
                  crossConfigs = m1.crossConfigs ++ m2.crossConfigs
                ))
              case (m1, m2) => m1.orElse(m2)
          )
        module.copy(
          crossConfigs =
            module.crossConfigs.map((k, v) => (k, ModuleConfig.inherited(v, module.configs))),
          testModule = module.testModule.map(module =>
            module.copy(
              crossConfigs =
                module.crossConfigs.map((k, v) => (k, ModuleConfig.inherited(v, module.configs)))
            )
          )
        )
    end toModule
    val packages = sbtModules.groupMap(_._1)(_._2).iterator.map:
      case ((_, false), crossVersionModules) => Tree(toModule(crossVersionModules))
      case ((moduleDir, _), crossPlatformModules) => Tree(
          root = ModuleRepr(moduleDir),
          children = crossPlatformModules.groupBy(_.segments).iterator
            .map((_, crossVersionModules) => Tree(toModule(crossVersionModules)))
            .toSeq
        )
    .toSeq

    val build = BuildRepr(packages).withDepsObject(DepsObject(depsObjectName))

    SbtBuildWriter.writeFiles(build)
  }
}
