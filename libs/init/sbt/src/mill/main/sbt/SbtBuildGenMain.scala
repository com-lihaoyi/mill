package mill.main.sbt

import mill.constants.Util.isWindows
import mill.main.buildgen.*
import pprint.Util.literalize

import scala.util.Using

@mainargs.main
case class SbtBuildGenMainArgs(
    testModuleName: String = "test",
    depsObjectName: String = "Deps",
    noMerge: mainargs.Flag,
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
    val out = os.temp()
    // https://github.com/JetBrains/sbt-structure/#sideloading
    val script = Seq(
      s"set SettingKey[(File, String)](\"millInitExportArgs\") in Global := (file(${literalize(out.toString())}), ${literalize(testModuleName)})",
      s"apply -cp ${literalize(jar.toString())} mill.main.sbt.ExportSbtBuildScript",
      s"millInitExport"
    )

    val cmd = sbtCmd.getOrElse:
      (if (isWindows) Left("sbt.bat")
       else Seq("sbt", ".sbtx").collectFirst:
         case exe if os.exists(os.pwd / exe) => s"./$exe"
       .toRight("sbt")) match {
        case Left(cmd) if os.call((cmd, "--help"), check = false).exitCode == 1 => cmd
        case Left(cmd) => sys.error(s"no system-wide $cmd found")
        case Right(cmd) => cmd
      }
    os.proc(cmd, sbtOptions.value, script).call(stdout = os.Inherit, stderr = os.Inherit)

    val sbtModules = upickle.default.read[Seq[SbtModuleRepr]](out.toNIO)
    if (sbtModules.isEmpty) {
      println(s"no modules found using $cmd")
      return
    }

    val packages = sbtModules.groupBy(_.moduleDir).map:
      case (dir, Seq(sbtModule: SbtModuleRepr)) if dir == sbtModule.baseDir =>
        import sbtModule.*
        PackageRepr(segments = moduleDir, modules = Tree(module))
      case (crossPlatformDir, sbtModules) =>
        val modules = Tree(
          root = ModuleRepr(),
          children = sbtModules.map: sbtModule =>
            Tree(sbtModule.module)
        )
        PackageRepr(segments = crossPlatformDir, modules = modules)
    .toSeq

    val packageTree = PackageTree.fill(packages)
    val packageTree0 = if (noMerge.value) packageTree else packageTree.merged

    val platformCrossTypeBySegments = sbtModules.iterator.collect {
      case module if module.platformCrossType.nonEmpty =>
        module.baseDir -> module.platformCrossType.get
    }.toMap
    val crossScalaVersionsBySegments = sbtModules.iterator.collect {
      case module if module.crossScalaVersions.length > 1 =>
        module.baseDir -> module.crossScalaVersions
    }.toMap
    val packageWriter = SbtPackageWriter(
      platformCrossTypeBySegments,
      crossScalaVersionsBySegments,
      depsObjectName,
      packageTree0.namesByDep
    )
    packageTree0.writeFiles(packageWriter)

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
    }
  }
}
