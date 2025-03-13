package mill.main.sbt

import mainargs.*
import mill.constants.Util
import mill.main.buildgen.*
import mill.util.Jvm
import pprint.Util.literalize

object SbtBuildGenMain:

  def main(args0: Array[String]): Unit =
    given SbtBuildGenArgs = ParserForClass[SbtBuildGenArgs].constructOrExit(args0.toSeq)
    println("converting SBT build")
    val assembly = extractExporterAssembly()
    val projects = importSbtProjects(assembly)
    val packages = toMetaTree(projects)
    BuildTreeWriter(packages).write()

  def extractExporterAssembly() =
    val in = getClass.getResourceAsStream(BuildInfo.exporterAssemblyResource)
    require(null != in, "exporter assembly must be on the classpath")
    try os.temp(in, suffix = ".jar")
    finally in.close()

  def importSbtProjects(exporterAssembly: os.Path)(using args: SbtBuildGenArgs) =
    import args.*
    def systemSbt = if Util.isWindows then "sbt.bat" else "sbt"
    val sbt = customSbt.getOrElse(systemSbt)
    val env = jvmIdSbt.map(id => Map("JAVA_HOME" -> Jvm.resolveJavaHome(id).get.toString)).orNull
    val stdout = if noLogSbt.value then os.Pipe else os.Inherit
    val out = os.temp()
    os.proc(
      sbt,
      sbtOptions.value,
      s"set SettingKey[File](\"millInitExportFile\") in Global := file(${literalize(out.toString)})",
      s"apply -cp ${literalize(exporterAssembly.toString)} mill.main.sbt.ApplyExport",
      "millInitExport"
    ).call(env = env, stdout = stdout)
    upickle.default.read[Seq[SbtProjectModel]](out.toNIO)

  def toMetaTree(projects: Seq[SbtProjectModel]) =
    // group cross-platform projects with the "actual" base dir
    val projectGroups = projects.groupBy: project =>
      project.crossPlatformBaseDir.getOrElse(project.baseDir)

    Tree.from(os.sub): nodeBaseDir =>
      val node = toMetaPackage(nodeBaseDir, projectGroups.getOrElse(nodeBaseDir, Nil))
      val children =
        val children0 = projectGroups.keysIterator
          .filter(_.segments0.length == nodeBaseDir.segments0.length + 1)
        if children0.isEmpty then
          projectGroups.keysIterator
            .collect: // introduce empty `RootModule`(s) to reach deeply nested projects
              case groupBaseDir if groupBaseDir.segments0.length > nodeBaseDir.segments0.length =>
                nodeBaseDir / groupBaseDir.segments0.head
        else children0
      (node, children)
  end toMetaTree

  def toMetaPackage(baseDir: os.SubPath, projectGroup: Seq[SbtProjectModel]) =
    val imports =
      Seq( // TODO derive
        "import coursier.maven.MavenRepository",
        "import mill._",
        "import mill.scalalib._",
        "import mill.scalalib.publish._",
        "import mill.scalajslib._",
        "import mill.scalanativelib._"
      )
    projectGroup match
      case Seq(project) => // not a cross-platform group
        MetaPackage(
          baseDir = baseDir,
          imports = imports,
          module = toMetaModule(project)
        )
      case _ =>
        val name = baseDir.lastOpt.getOrElse("package")
        val nestedModules = projectGroup.map(toMetaModule)
        val module = MetaModule(
          name = name,
          supertypes = Nil,
          nestedModules = nestedModules
        )
        MetaPackage(
          baseDir = baseDir,
          imports = imports,
          module = module
        )
  end toMetaPackage

  def toMetaModule(project: SbtProjectModel) =
    import project.*
    val name = baseDir.lastOpt.getOrElse(project.moduleName)
    val supertypes =
      val supertype0 =
        if scalaJSConfig.nonEmpty then "ScalaJSModule"
        else if scalaNativeConfig.nonEmpty then "ScalaNativeModule"
        else "ScalaModule"
      Seq(supertype0) ++
        Option.when(crossVersions.nonEmpty)("CrossScalaModule") ++
        Option.when(publishConfig.nonEmpty)("PublishModule")
    val moduleConfigs = Seq.newBuilder[IrModuleConfig]
      .+=(scalaConfig)
      .++=(publishConfig)
      .+=(javaConfig)
      .+=(coursierConfig)
      .++=(scalaJSConfig.orElse(scalaNativeConfig))
      .result()
    MetaModule(
      name = name,
      supertypes = supertypes,
      crossVersions = crossVersions,
      moduleConfigs = moduleConfigs,
      nestedModules = Seq.from(toTestMetaModule(project))
    )
  end toMetaModule

  def toTestMetaModule(project: SbtProjectModel) =
    project.testJavaConfig.flatMap: javaModuleConfig =>
      javaModuleConfig.ivyDeps
        .collectFirst(BuildGenConstants.testRunnerModuleByOrg.compose(_.organization))
        .map: runnerModule =>
          val supertype =
            if project.scalaJSConfig.nonEmpty then "ScalaJSTests"
            else if project.scalaNativeConfig.nonEmpty then "ScalaNativeTests"
            else "ScalaTests"
          MetaModule(
            name = "test",
            supertypes = Seq(supertype, runnerModule),
            moduleConfigs = Seq(javaModuleConfig)
          )
  end toTestMetaModule

@main case class SbtBuildGenArgs(
    @arg(doc = "distribution and version of custom JVM to run SBT")
    jvmIdSbt: Option[String], // TODO is overkill?
    @arg(doc = "hide SBT output")
    noLogSbt: Flag,
    @arg(doc = "custom SBT command")
    customSbt: Option[String],
    @arg(doc = "SBT command line options")
    sbtOptions: Leftover[String]
)
