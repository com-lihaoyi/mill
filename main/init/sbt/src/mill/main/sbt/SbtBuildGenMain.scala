package mill.main.sbt

import mainargs.{ParserForClass, arg, main}
import mill.constants.Util
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.IrDependencyType.*
import mill.scalalib.CrossVersion as MillCrossVersion
import os.Path

import scala.collection.MapView
import scala.collection.immutable.SortedSet

/**
 * Converts an sbt build to Mill by generating Mill build file(s).
 * The implementation uses the sbt
 * [[https://www.scala-sbt.org/1.x/docs/Combined+Pages.html#addPluginSbtFile+command addPluginSbtFile command]]
 * to add a plugin and a task to extract the settings for a project using a custom model.
 *
 * The generated output should be considered scaffolding and will likely require edits to complete conversion.
 *
 * ===Capabilities===
 * The conversion
 *  - handles deeply nested modules
 *  - captures publish settings
 *  - configures dependencies for configurations:
 *    - no configuration
 *    - Compile
 *    - Test
 *    - Runtime
 *    - Provided
 *    - Optional
 *  - configures testing frameworks (@see [[mill.scalalib.TestModule]]):
 *    - Java:
 *      - JUnit 4
 *      - JUnit 5
 *      - TestNG
 *    - Scala:
 *      - ScalaTest
 *      - Specs2
 *      - µTest
 *      - MUnit
 *      - Weaver
 *      - ZIOTest
 * ===Limitations===
 * The conversion does not support:
 *  - custom dependency configurations
 *  - custom settings including custom tasks
 *  - sources other than Scala on JVM and Java, such as Scala.js and Scala Native
 *  - cross builds
 */
@mill.api.internal
object SbtBuildGenMain
    extends BuildGenBase[Project, String, (BuildInfo, Tree[Node[Option[Project]]])] {
  override type C = Config
  override type OM = Option[Project]

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[Config].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: Config): Unit = {
    val workspace = os.pwd

    println("converting sbt build")

    def systemSbtExists(sbt: String) =
      // The return code is somehow 1 instead of 0.
      os.call((sbt, "--help"), check = false).exitCode == 1

    val isWindows = Util.isWindows
    val sbtExecutable = if (isWindows) {
      val systemSbt = "sbt.bat"
      if (systemSbtExists(systemSbt))
        systemSbt
      else
        throw new RuntimeException(s"No system-wide `$systemSbt` found")
    } else {
      val systemSbt = "sbt"
      // resolve the sbt executable
      // https://raw.githubusercontent.com/paulp/sbt-extras/master/sbt
      if (os.exists(workspace / "sbt"))
        "./sbt"
      else if (os.exists(workspace / "sbtx"))
        "./sbtx"
      else if (systemSbtExists(systemSbt))
        systemSbt
      else
        throw new RuntimeException(
          s"No sbt executable (`./sbt`, `./sbtx`, or system-wide `$systemSbt`) found"
        )
    }

    println("Running the added `millInitExportBuild` sbt task to export the build")

    val exitCode = (
      if (isWindows) {
        /*
        `-addPluginSbtFile` somehow doesn't work on Windows, therefore, the ".sbt" file is put directly in the sbt "project" directory.
        The error message:
        ```text
        [error] Expected ':'
        [error] Expected '='
        [error] Expected whitespace character
        [error] -addPluginSbtFile
        [error]                  ^
        ```
         */
        val sbtFile = writeTempSbtFileInSbtProjectDirectory(workspace)
        val commandResult = os.call(
          (sbtExecutable, "millInitExportBuild"),
          cwd = workspace,
          stdout = os.Inherit
        )
        os.remove(sbtFile)
        commandResult
      } else
        os.call(
          (sbtExecutable, s"-addPluginSbtFile=${writeSbtFile().toString}", "millInitExportBuild"),
          cwd = workspace,
          stdout = os.Inherit
        )
    )
    .exitCode

    // println("Exit code from running the `millInitExportBuild` sbt task: " + exitCode)
    if (exitCode != 0)
      println(
        "The sbt command to run the `millInitExportBuild` sbt task has likely failed, please update the project's sbt version to the latest or our tested version v1.10.7, and try again."
      )

    val buildExportPickled = os.read(workspace / "target" / "mill-init-build-export.json")
    // TODO This is mainly for debugging purposes. Comment out or uncomment this line as needed.
    // println("sbt build export retrieved: " + buildExportPickled)
    import upickle.default.*
    val buildExport = read[BuildExport](buildExportPickled)

    import scala.math.Ordering.Implicits.*
    // Types have to be specified explicitly here for the code to be resolved correctly in IDEA.
    val projectNodes =
      buildExport.projects.view
        .map(project =>
          Node(os.Path(project.projectDirectory).subRelativeTo(workspace).segments, project)
        )
        // The projects are ordered differently in different `sbt millInitExportBuild` runs and on different OSs, which is strange.
        .sortBy(_.dirs)

    val projectNodeTree = projectNodes.foldLeft(Tree(Node(Seq.empty, None)))(merge)

    convertWriteOut(cfg, cfg.shared, (buildExport.defaultBuildInfo, projectNodeTree))

    println("converted sbt build to Mill")
  }

  /**
   * @return the temp directory the jar is in and the sbt file contents.
   */
  private def copyExportBuildAssemblyJarOutAndGetSbtFileContents(): (os.Path, String) = {
    val tempDir = os.temp.dir()
    // This doesn't work in integration tests when Mill is packaged.
    /*
    val sbtPluginJarUrl =
      getClass.getResource("/sbt-mill-init-export-build-assembly.jar").toExternalForm
     */
    val sbtPluginJarName = "sbt-mill-init-export-build-assembly.jar"
    val sbtPluginJarStream = getClass.getResourceAsStream(s"/$sbtPluginJarName")
    val sbtPluginJarPath = tempDir / sbtPluginJarName
    os.write(sbtPluginJarPath, sbtPluginJarStream)
    val contents =
      s"""addSbtPlugin("com.lihaoyi" % "mill-main-init-sbt-sbt-mill-init-export-build" % "dummy-version" from ${
          escape(sbtPluginJarPath.wrapped.toUri.toString)
        })
         |""".stripMargin
    (tempDir, contents)
  }

  private def writeSbtFile(): os.Path = {
    val (tempDir, contents) = copyExportBuildAssemblyJarOutAndGetSbtFileContents()
    val sbtFile = tempDir / "mill-init.sbt"
    os.write(sbtFile, contents)
    sbtFile
  }

  private def writeTempSbtFileInSbtProjectDirectory(workspace: os.Path) =
    os.temp(
      copyExportBuildAssemblyJarOutAndGetSbtFileContents()._2,
      workspace / "project",
      suffix = ".sbt"
    )

  extension (om: Option[Project]) override def toOption(): Option[Project] = om

  override def getModuleTree(
      input: (BuildInfo, Tree[Node[Option[Project]]])
  ): Tree[Node[Option[Project]]] =
    input._2

  private def sbtSupertypes = Seq("SbtModule", "PublishModule") // always publish

  override def getBaseInfo(
      input: (BuildInfo, Tree[Node[Option[Project]]]),
      cfg: Config,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo = {
    val buildInfo = cfg.baseProject.fold(input._1)(name =>
      // TODO This can simplified if `buildExport.projects` is passed here.
      input._2.nodes().collectFirst(Function.unlift(_.value.flatMap(project =>
        Option.when(project.name == name)(project)
      ))).get.buildInfo
    )

    import buildInfo.*
    val javacOptions = getJavacOptions(buildInfo)
    val repositories = getRepositories(buildInfo)
    val pomSettings = extractPomSettings(buildPublicationInfo)
    val publishVersion = getPublishVersion(buildInfo)

    val typedef = IrTrait(
      cfg.shared.jvmId, // There doesn't seem to be a Java version setting in sbt though. See https://stackoverflow.com/a/76456295/5082913.
      baseModule,
      sbtSupertypes,
      javacOptions,
      scalaVersion,
      scalacOptions,
      pomSettings,
      publishVersion,
      Seq.empty, // not available in sbt as it seems
      repositories
    )

    IrBaseInfo(typedef)
  }

  override def extractIrBuild(
      cfg: Config,
      // baseInfo: IrBaseInfo,
      build: Node[Project],
      packages: Map[(String, String, String), String]
  ): IrBuild = {
    val project = build.value
    val buildInfo = project.buildInfo
    val configurationDeps = extractConfigurationDeps(project, packages, cfg)
    val version = getPublishVersion(buildInfo)
    IrBuild(
      scopedDeps = configurationDeps,
      testModule = cfg.shared.testModule,
      testModuleMainType = "SbtTests",
      hasTest = os.exists(getMillSourcePath(project) / "src/test"),
      dirs = build.dirs,
      repositories = getRepositories(buildInfo),
      javacOptions = getJavacOptions(buildInfo),
      scalaVersion = buildInfo.scalaVersion,
      scalacOptions = buildInfo.scalacOptions,
      projectName = project.name,
      pomSettings = extractPomSettings(buildInfo.buildPublicationInfo),
      publishVersion = version,
      packaging = null, // not available in sbt as it seems
      pomParentArtifact = null, // not available
      resources = Nil,
      testResources = Nil,
      publishProperties = Nil // not available in sbt as it seems
    )
  }

  override def extraImports: Seq[String] = Seq("mill.scalalib.SbtModule")

  def getModuleSupertypes(cfg: Config): Seq[String] =
    cfg.shared.baseModule.fold(sbtSupertypes)(Seq(_))

  def getPackage(project: Project): (String, String, String) = {
    val buildPublicationInfo = project.buildInfo.buildPublicationInfo
    (buildPublicationInfo.organization.orNull, project.name, buildPublicationInfo.version.orNull)
  }

  def getArtifactId(project: Project): String = project.name

  def getMillSourcePath(project: Project): Path = os.Path(project.projectDirectory)

  override def getSupertypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[Project]): Seq[String] =
    Seq("RootModule") ++ getModuleSupertypes(cfg)

  def groupArtifactVersion(dep: Dependency): (String, String, String) =
    (dep.organization, dep.name, dep.revision)

  def getJavacOptions(buildInfo: BuildInfo): Seq[String] =
    buildInfo.javacOptions.getOrElse(Seq.empty)

  def getRepositories(buildInfo: BuildInfo): Seq[String] =
    buildInfo.resolvers.getOrElse(Seq.empty).map(resolver =>
      s"coursier.maven.MavenRepository(${escape(resolver.root)})"
    )

  def getPublishVersion(buildInfo: BuildInfo): String | Null =
    buildInfo.buildPublicationInfo.version.orNull

  // originally named `ivyInterp` in the Maven and module
  def renderIvy(dependency: Dependency): String = {
    import dependency.*
    renderIvyString(
      organization,
      name,
      crossVersion match {
        case CrossVersion.Disabled => None
        case CrossVersion.Binary => Some(MillCrossVersion.Binary(false))
        case CrossVersion.Full => Some(MillCrossVersion.Full(false))
        case CrossVersion.Constant(value) => Some(MillCrossVersion.Constant(value, false))
      },
      version = revision,
      tpe = tpe.orNull,
      classifier = classifier.orNull,
      excludes = excludes
    )
  }

  def extractPomSettings(buildPublicationInfo: BuildPublicationInfo): IrPom = {
    import buildPublicationInfo.*
    // always publish
    /*
    if (
      Seq(
        description,
        homepage,
        licenses,
        organizationName,
        organizationHomepage,
        developers,
        scmInfo
      ).forall(_.isEmpty)
    )
      null
    else
     */
    IrPom(
      description.getOrElse(""),
      organization.getOrElse(""),
      homepage.fold("")(_.getOrElse("")),
      licenses.getOrElse(Seq.empty).map(license => IrLicense(license._1, license._1, license._2)),
      scmInfo.flatten.fold(IrVersionControl(null, null, null, null))(scmInfo => {
        import scmInfo.*
        IrVersionControl(browseUrl, connection, devConnection.orNull, null)
      }),
      developers.getOrElse(Seq.empty).map { developer =>
        import developer.*
        IrDeveloper(id, name, url, null, null)
      }
    )
  }

  private def isScalaStandardLibrary(dep: Dependency) =
    Seq("ch.epfl.lamp", "org.scala-lang").contains(dep.organization) &&
      Seq("scala-library", "dotty-library", "scala3-library").contains(dep.name)

  def extractConfigurationDeps(
      project: Project,
      packages: PartialFunction[(String, String, String), String],
      cfg: Config
  ): IrScopedDeps = {
    // refactored to a functional approach from the original imperative code in Maven and Gradle

    val allDepsByConfiguration = project.allDependencies
      // .view // This makes the types hard to deal with here thus commented out.
      .filterNot(isScalaStandardLibrary)
      .flatMap(dep =>
        (dep.configurations match {
          case None => Some(Default)
          case Some(configuration) => configuration match {
              case "compile" => Some(Default)
              case "test" => Some(Test)
              case "runtime" => Some(Run)
              case "provided" | "optional" => Some(Compile)
              case other =>
                println(
                  s"Dependency $dep with an unknown configuration ${escape(other)} is dropped."
                )
                None
            }
        })
          .map(tpe => (dep, tpe))
      )
      .groupBy(_._2)
      .view
      .mapValues(_.map(_._1))
      // Types have to be specified explicitly here for the code to be resolved correctly in IDEA.
      .asInstanceOf[MapView[IrDependencyType, Seq[Dependency]]]

    case class Deps[I, M](ivy: Seq[I], module: Seq[M])

    // Types have to be specified explicitly here for the code to be resolved correctly in IDEA.
    val ivyAndModuleDepsByConfiguration: Map[IrDependencyType, Deps[Dependency, String]] =
      allDepsByConfiguration.mapValues(deps => {
        val tuple2 = deps.partitionMap(dep => {
          val id = groupArtifactVersion(dep)
          if (packages.isDefinedAt(id)) Right(packages(id))
          else Left(dep)
        })
        Deps(tuple2._1, tuple2._2)
      }).toMap

    val testIvyAndModuleDeps = ivyAndModuleDepsByConfiguration.get(Test)
    val testIvyDeps = testIvyAndModuleDeps.map(_.ivy)
    val hasTest = os.exists(os.Path(project.projectDirectory) / "src/test")
    val testModule = Option.when(hasTest)(
      testIvyDeps.flatMap(_.collectFirst(Function.unlift(dep =>
        testModulesByGroup.get(dep.organization)
      )))
    ).flatten

    cfg.shared.depsObject.fold({
      val default = ivyAndModuleDepsByConfiguration.get(Default)
      val compile = ivyAndModuleDepsByConfiguration.get(Compile)
      val run = ivyAndModuleDepsByConfiguration.get(Run)
      val test = testIvyAndModuleDeps
      IrScopedDeps(
        Seq.empty,
        SortedSet.empty,
        // Using `fold` here causes issues with type inference.
        SortedSet.from(default.map(_.ivy.iterator.map(renderIvy)).getOrElse(Iterator.empty)),
        SortedSet.from(default.map(_.module).getOrElse(Seq.empty)),
        SortedSet.from(compile.map(_.ivy.iterator.map(renderIvy)).getOrElse(Iterator.empty)),
        SortedSet.from(compile.map(_.module).getOrElse(Seq.empty)),
        SortedSet.from(run.map(_.ivy.iterator.map(renderIvy)).getOrElse(Iterator.empty)),
        SortedSet.from(run.map(_.module).getOrElse(Seq.empty)),
        testModule,
        SortedSet.empty,
        SortedSet.from(testIvyDeps.map(_.iterator.map(renderIvy)).getOrElse(Iterator.empty)),
        SortedSet.from(test.map(_.module).getOrElse(Seq.empty)),
        SortedSet.empty,
        SortedSet.empty
      )
    })(objectName => {
      // Types have to be specified explicitly here for the code to be resolved correctly in IDEA.
      val extractedIvyAndModuleDepsByConfiguration
          : Map[IrDependencyType, Deps[((String, String), String), String]] =
        ivyAndModuleDepsByConfiguration.view.mapValues({
          case Deps(ivy, module) =>
            Deps(
              ivy.map(dep => {
                val depName = s"`${dep.organization}:${dep.name}`"
                ((depName, renderIvy(dep)), s"$objectName.$depName")
              }),
              module
            )
        }).toMap

      val default = extractedIvyAndModuleDepsByConfiguration.get(Default)
      val compile = extractedIvyAndModuleDepsByConfiguration.get(Compile)
      val run = extractedIvyAndModuleDepsByConfiguration.get(Run)
      val test = extractedIvyAndModuleDepsByConfiguration.get(Test)
      IrScopedDeps(
        extractedIvyAndModuleDepsByConfiguration.values.flatMap(_.ivy.iterator.map(_._1)).toSeq,
        SortedSet.empty,
        SortedSet.from(default.map(_.ivy.iterator.map(_._2)).getOrElse(Iterator.empty)),
        SortedSet.from(default.map(_.module).getOrElse(Seq.empty)),
        SortedSet.from(compile.map(_.ivy.iterator.map(_._2)).getOrElse(Iterator.empty)),
        SortedSet.from(compile.map(_.module).getOrElse(Seq.empty)),
        SortedSet.from(run.map(_.ivy.iterator.map(_._2)).getOrElse(Iterator.empty)),
        SortedSet.from(run.map(_.module).getOrElse(Seq.empty)),
        testModule,
        SortedSet.empty,
        SortedSet.from(test.map(_.ivy.iterator.map(_._2)).getOrElse(Iterator.empty)),
        SortedSet.from(test.map(_.module).getOrElse(Seq.empty)),
        SortedSet.empty,
        SortedSet.empty
      )
    })
  }

  @main
  @mill.api.internal
  case class Config(
      shared: BuildGenUtil.BasicConfig,
      @arg(
        doc = "name of the sbt project to extract settings for --base-module, " +
          "if not specified, settings are extracted from `ThisBuild`",
        short = 'g'
      )
      baseProject: Option[String] = None
  )
}
