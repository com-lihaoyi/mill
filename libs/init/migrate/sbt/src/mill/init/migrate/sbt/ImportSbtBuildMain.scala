package mill.init.migrate
package sbt

import mill.constants.Util.isWindows
import mill.init.migrate.sbt.BuildInfo.exportscriptAssemblyResource
import pprint.Util.literalize

import scala.util.Using

@mainargs.main
case class ImportSbtBuildArgs(
    testModuleName: String = "test",
    depsObjectName: String = "Deps",
    noUnify: mainargs.Flag,
    sbtCmd: Option[String],
    sbtOptions: mainargs.Leftover[String],
    poc: mainargs.Flag // TODO remove
)

object ImportSbtBuildMain {

  def main(args: Array[String]): Unit = {
    val args0 = mainargs.ParserForClass[ImportSbtBuildArgs].constructOrExit(args.toSeq)
    import args0.{getClass as _, *}

    val jar = Using.resource(getClass.getResourceAsStream(exportscriptAssemblyResource))(os.temp(
      _,
      suffix = ".jar"
    ))
    val out = os.temp()
    // https://github.com/JetBrains/sbt-structure/#sideloading
    val script = Seq(
      s"set SettingKey[(File, String)](\"millInitExportArgs\") in Global := (file(${literalize(out.toString())}), ${literalize(testModuleName)})",
      s"apply -cp ${literalize(jar.toString())} mill.init.migrate.sbt.ExportSbtBuildScript",
      s"millInitExport"
    )

    val cmd = sbtCmd.getOrElse:
      (if (isWindows) Left("sbt.bat")
       else Seq("sbt", ".sbtx").collectFirst:
         case exe if os.exists(os.pwd / exe) => s"./$exe"
       .toRight("sbt")) match {
        case Left(cmd) if os.call((cmd, "--help"), check = false).exitCode == 1 => cmd
        case Left(cmd) => sys.error(s"No system-wide $cmd found")
        case Right(cmd) => cmd
      }
    os.proc(cmd, sbtOptions.value, script).call(stdout = os.Inherit, stderr = os.Inherit)

    val sbtModules = upickle.default.read[Seq[SbtModule]](out.toNIO)
    if (sbtModules.isEmpty) {
      println(s"No modules found using $cmd")
      return
    }

    val packages = sbtModules.groupBy(_.moduleDir).map:
      case (dir, Seq(sbtModule: SbtModule)) if dir == sbtModule.baseDir =>
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
    val packageTree0 = if (noUnify.value) packageTree else packageTree.unified

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
    PackageTree.writeFiles(packageTree0, packageWriter)

    println(s"Imported ${sbtModules.length} module(s).")
    if (crossScalaVersionsBySegments.nonEmpty) println(
      """WARNING:
        |For cross Scala version modules, the settings imported correspond to the default Scala version used by SBT.
        |Any version specific settings will have to be adjusted manually for corresponding Mill tasks such as
        | - mvnDeps: libraryDependencies in Compile scope
        | - compileMvnDeps: libraryDependencies in Provided and Optional scopes
        | - runtimeMvnDeps: libraryDependencies in Runtime scope
        | - scalacPluginMvnDeps: libraries added with addCompilerPlugin
        | - scalacOptions
        |""".stripMargin
    )
  }
}
