package mill.main.sbt

import mill.constants.Util.isWindows
import mill.main.buildgen.*
import pprint.Util.literalize

import scala.util.Using

@mainargs.main
case class SbtBuildGenMainArgs(
    @mainargs.arg(short = 'd')
    depsObjectName: String = "Deps",
    @mainargs.arg(short = 't')
    testModuleName: String = "test",
    @mainargs.arg(short = 'B')
    noBaseTraits: mainargs.Flag,
    @mainargs.arg(short = 'M')
    noMerge: mainargs.Flag,
    @mainargs.arg(short = 'c')
    sbtCmd: Option[String],
    sbtOptions: mainargs.Leftover[String]
)

object SbtBuildGenMain {

  def main(args: Array[String]): Unit = {
    println("converting sbt build")

    val args0 = mainargs.ParserForClass[SbtBuildGenMainArgs].constructOrExit(args.toSeq)
    import args0.{getClass as _, *}

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
    val jar =
      Using.resource(getClass.getResourceAsStream(BuildInfo.exportscriptAssemblyResource))(os.temp(
        _,
        suffix = ".jar"
      ))
    val exportDir = os.temp.dir()
    // Run export with "+" to generate a file per project and cross Scala version.
    // NOTE: SBT can generate duplicate files for modules. If a project "foo" defines 3 cross
    // Scala versions and another module "bar" specifies 2 of those cross Scala versions, a
    // duplicate file will be generated for module "bar".
    val script = Seq(
      s"set SettingKey[(File, String)](\"millInitExportArgs\") in Global := (file(${literalize(exportDir.toString())}), ${literalize(testModuleName)})",
      s"apply -cp ${literalize(jar.toString())} mill.main.sbt.ExportSbtBuildScript",
      s"+millInitExportBuild"
    )
    os.proc(cmd, sbtOptions.value, script).call(stdout = os.Inherit, stderr = os.Inherit)

    // ((moduleDir, isCrossPlatform), ModuleRepr)
    type SbtModuleRepr = ((Seq[String], Boolean), ModuleRepr)
    val sbtModules = os.list.stream(exportDir).map(path =>
      upickle.default.read[SbtModuleRepr](path.toNIO)
    ).toSeq.distinct // eliminate any cross version duplicates
    if (sbtModules.isEmpty) {
      sys.error(s"no modules found using $cmd")
    }

    def toModule(crossVersionModules: Seq[ModuleRepr]): ModuleRepr =
      if (crossVersionModules.tail.isEmpty) crossVersionModules.head // not cross version
      else
        // compute the base config for cross versions
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
        // inherit the base config for cross versions
        module.copy(
          crossConfigs = module.crossConfigs.map: (k, v) =>
            (k, ModuleConfig.inherited(v, module.configs))
          .sortBy(_._1),
          testModule = module.testModule.map(module =>
            module.copy(
              crossConfigs = module.crossConfigs.map: (k, v) =>
                (k, ModuleConfig.inherited(v, module.configs))
              .sortBy(_._1)
            )
          )
        )
    end toModule
    val packages = sbtModules.groupMap(_._1)(_._2).iterator.map:
      case ((_, false), crossVersionModules) => Tree(toModule(crossVersionModules))
      case ((moduleDir, _), crossPlatformModules) => Tree(
          ModuleRepr(moduleDir),
          crossPlatformModules.groupBy(_.segments).iterator.map: (_, crossVersionModules) =>
            Tree(toModule(crossVersionModules))
          .toSeq
        )
    .toSeq

    var build = BuildRepr(packages).withDepsObject(DepsObject(depsObjectName))
    if (!noBaseTraits.value) build = build.withBaseTraits
    if (!noMerge.value) build = build.merged
    SbtBuildWriter.writeFiles(build)

    locally {
      val jvmOptsSbt = os.pwd / ".jvmopts"
      val jvmOptsMill = os.pwd / ".mill-jvm-opts"

      if (os.exists(jvmOptsSbt)) {
        println(s"copying ${jvmOptsSbt.last} to ${jvmOptsMill.last}")
        if (os.exists(jvmOptsMill)) {
          val backup = jvmOptsMill / os.up / s"${jvmOptsMill.last}.bak"
          println(s"creating backup ${backup.last}")
          os.move.over(jvmOptsMill, backup)
        }
        var reportOnce = true
        // Since .jvmopts may contain multiple args per line, we warn the user
        // as Mill wants each arg on a separate line
        os.read.lines(jvmOptsSbt).collectFirst {
          // Warn the user once when we find spaces, but ignore comments
          case x if reportOnce && !x.trim().startsWith("#") && x.trim().contains(" ") =>
            reportOnce = false
            println(
              s"${jvmOptsMill.last}: Please check that each arguments is on a separate line!"
            )
        }
        os.copy(jvmOptsSbt, jvmOptsMill)
      }

      val sbtOpts = os.pwd / ".sbtopts"
      if (os.exists(sbtOpts)) {
        var jvmArgs = os.read.lines.stream(sbtOpts).collect:
          case s if s.startsWith("-J") => s.substring("-J".length)
        .toSeq
        if (jvmArgs.nonEmpty && os.exists(jvmOptsMill)) {
          jvmArgs = jvmArgs.diff(os.read.lines(jvmOptsMill))
        }
        if (jvmArgs.nonEmpty) {
          println(s"adding JVM args from ${sbtOpts.last} to ${jvmOptsMill.last}")
          os.write.append(jvmOptsMill, jvmArgs.mkString(System.lineSeparator()))
        }
      }
    }
  }
}
