package mill.main.sbt

import mainargs.{ParserForClass, arg, main}
import mill.constants.Util
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.IrDependencyType.*
import os.Path

import scala.collection.immutable.SortedSet

/**
 * Converts an `sbt` build to Mill by generating Mill build file(s).
 * The implementation uses the `sbt`
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
 *      - ScalaCheck
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
    val sbtExecutable = cfg.customSbt.getOrElse(
      if (isWindows) {
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
    )

    println("Running the added `millInitExportBuild` sbt task to export the build")

    try {
      if (isWindows) {
        /*
        `-addPluginSbtFile` somehow doesn't work on Windows, therefore, the ".sbt" file is put directly in the `sbt` "project" directory.
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
        os.call(
          (sbtExecutable, "millInitExportBuild"),
          cwd = workspace,
          stdout = os.Inherit
        )
        os.remove(sbtFile)
      } else
        os.call(
          (sbtExecutable, s"-addPluginSbtFile=${writeSbtFile().toString}", "millInitExportBuild"),
          cwd = workspace,
          stdout = os.Inherit
        )

    } catch {
      case e: os.SubprocessException => throw RuntimeException(
          "The sbt command to run the `millInitExportBuild` sbt task has failed, " +
            s"please check out the following solutions and try again:\n" +
            s"1. check whether your existing sbt build works properly;\n" +
            s"2. make sure there are no other sbt processes running;\n" +
            s"3. clear your build output and cache;\n" +
            s"4. update the project's sbt version to the latest or our tested version v${Versions.sbtVersion};\n" +
            "5. check whether you have the appropriate Java version.\n",
          e
        )
      case t: Throwable => throw t
    }

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
   * @return the temp directory the jar is in and the `sbt` file contents.
   */
  private def copyExportBuildAssemblyJarOutAndGetSbtFileContents(): (os.Path, String) = {
    val tempDir = os.temp.dir()
    // This doesn't work in integration tests when Mill is packaged.
    /*
    val sbtPluginJarUrl =
      getClass.getResource("/exportplugin-assembly.jar").toExternalForm
     */
    val sbtPluginJarName = "exportplugin-assembly.jar"
    val sbtPluginJarStream = getClass.getResourceAsStream(s"/$sbtPluginJarName")
    val sbtPluginJarPath = tempDir / sbtPluginJarName
    os.write(sbtPluginJarPath, sbtPluginJarStream)
    val contents =
      s"""addSbtPlugin("com.lihaoyi" % "mill-main-init-sbt-exportplugin" % "dummy-version" from ${
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
      cfg.shared.jvmId, // There doesn't seem to be a Java version setting in `sbt` though. See https://stackoverflow.com/a/76456295/5082913.
      baseModule,
      sbtSupertypes,
      javacOptions,
      scalaVersion,
      scalacOptions,
      pomSettings,
      publishVersion,
      Seq.empty, // not available in `sbt` as it seems
      repositories
    )

    IrBaseInfo(typedef)
  }

  /**
   * From the [[Project.projectRefProject]] to the package string built by [[BuildGenUtil.buildModuleFqn]].
   */
  override type ModuleFqnMap = Map[String, String]

  override def getModuleFqnMap(moduleNodes: Seq[Node[Project]]): Map[String, String] =
    buildModuleFqnMap(moduleNodes)(_.projectRefProject)

  override def extractIrBuild(
      cfg: Config,
      // baseInfo: IrBaseInfo,
      build: Node[Project],
      moduleFqnMap: ModuleFqnMap
  ): IrBuild = {
    val project = build.value
    val buildInfo = project.buildInfo
    val configurationDeps = extractConfigurationDeps(project, moduleFqnMap, cfg)
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
      packaging = null, // not available in `sbt` as it seems
      pomParentArtifact = null, // not available
      resources = Nil,
      testResources = Nil,
      publishProperties = Nil // not available in `sbt` as it seems
    )
  }

  override def extraImports: Seq[String] = Seq("mill.scalalib.SbtModule")

  def getModuleSupertypes(cfg: Config): Seq[String] =
    cfg.shared.baseModule.fold(sbtSupertypes)(Seq(_))

  def getArtifactId(project: Project): String = project.name

  def getMillSourcePath(project: Project): Path = os.Path(project.projectDirectory)

  override def getSupertypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[Project]): Seq[String] =
    Seq("RootModule") ++ getModuleSupertypes(cfg)

  def getJavacOptions(buildInfo: BuildInfo): Seq[String] =
    buildInfo.javacOptions.getOrElse(Seq.empty)

  def getRepositories(buildInfo: BuildInfo): Seq[String] =
    buildInfo.resolvers.getOrElse(Seq.empty).map(resolver =>
      s"coursier.maven.MavenRepository(${escape(resolver.root)})"
    )

  def getPublishVersion(buildInfo: BuildInfo): String | Null =
    buildInfo.buildPublicationInfo.version.orNull

  // originally named `ivyInterp` in the Maven and module
  def renderIvy(dependency: LibraryDependency): String = {
    import dependency.*
    renderIvyString(
      organization,
      name,
      crossVersion match {
        case CrossVersion.Disabled => None
        // The formatter doesn't work well for the import `import mill.scalalib.CrossVersion as MillCrossVersion` in IntelliJ IDEA, so FQNs are used here.
        case CrossVersion.Binary => Some(mill.api.CrossVersion.Binary(false))
        case CrossVersion.Full => Some(mill.api.CrossVersion.Full(false))
        case CrossVersion.Constant(value) => Some(mill.api.CrossVersion.Constant(value, false))
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

  private def isScalaStandardLibrary(dep: LibraryDependency) =
    Seq("ch.epfl.lamp", "org.scala-lang").contains(dep.organization) &&
      Seq("scala-library", "dotty-library", "scala3-library").contains(dep.name)

  /**
   * @param toModuleFqn see [[ModuleFqnMap]].
   */
  def extractConfigurationDeps(
      project: Project,
      toModuleFqn: PartialFunction[String, String],
      cfg: Config
  ): IrScopedDeps = {
    // refactored to a functional approach from the original imperative code in Maven and Gradle

    case class DepTypeAndConf(tpe: IrDependencyType, confAfterArrow: Option[String])
    def parseConfigurations(dep: Dependency): Iterator[DepTypeAndConf] =
      dep.configurations match {
        case None => Iterator(DepTypeAndConf(Default, None))
        case Some(configurations) =>
          configurations.split(';').iterator.flatMap(configuration => {
            val splitConfigurations = configuration.split("->")
            (splitConfigurations(0) match {
              case "compile" => Some(Default)
              case "test" => Some(Test)
              case "runtime" => Some(Run)
              case "provided" | "optional" => Some(Compile)
              case other =>
                println(
                  s"Dependency $dep with an unsupported configuration before `->` ${escape(other)} is dropped."
                )
                None
            })
              .map(DepTypeAndConf(_, splitConfigurations.lift(1)))
          })
      }

    val allDependencies = project.allDependencies

    val moduleDepsByType = allDependencies.projectDependencies.flatMap(dep =>
      parseConfigurations(dep).map { case DepTypeAndConf(tpe, confAfterArrow) =>
        tpe -> {
          val dependsOnTestModule = confAfterArrow match {
            case None => false
            case Some(value) =>
              value match {
                case "default" | "compile" | "default(compile)" => false
                case "test" => true
                case conf =>
                  println(
                    s"Unsupported dependency configuration after `->` in project dependency $dep ${escape(conf)} is ignored."
                  )
                  false
              }
          }
          val moduleRef = toModuleFqn(dep.projectRefProject)
          if (dependsOnTestModule) s"$moduleRef.${cfg.shared.testModule}" else moduleRef
        }
      }
    ).groupMap(_._1)(_._2)

    val ivyDepsByType = allDependencies.libraryDependencies
      .iterator
      .filterNot(isScalaStandardLibrary)
      .flatMap(dep =>
        parseConfigurations(dep).map { case DepTypeAndConf(tpe, confAfterArrow) =>
          confAfterArrow match {
            case None => ()
            case Some(value) =>
              value match {
                case "default" | "compile" | "default(compile)" => ()
                case conf =>
                  println(
                    s"Unsupported dependency configuration after `->` in library dependency $dep ${escape(conf)} is ignored."
                  )
              }
          }
          tpe -> dep
        }
      )
      .toSeq
      .groupMap(_._1)(_._2)

    val defaultModuleDeps = moduleDepsByType.getOrElse(Default, Seq.empty)
    val compileModuleDeps = moduleDepsByType.getOrElse(Compile, Seq.empty)
    val runModuleDeps = moduleDepsByType.getOrElse(Run, Seq.empty)
    val testModuleDeps = moduleDepsByType.getOrElse(Test, Seq.empty)

    val testIvyDeps = ivyDepsByType.getOrElse(Test, Seq.empty)

    val hasTest = os.exists(os.Path(project.projectDirectory) / "src/test")
    val testModule = Option.when(hasTest)(testIvyDeps.collectFirst(Function.unlift(dep =>
      testModulesByGroup.get(dep.organization)
    ))).flatten

    cfg.shared.depsObject.fold({
      val defaultIvyDeps = ivyDepsByType.getOrElse(Default, Seq.empty)
      val compileIvyDeps = ivyDepsByType.getOrElse(Compile, Seq.empty)
      val runIvyDeps = ivyDepsByType.getOrElse(Run, Seq.empty)

      IrScopedDeps(
        Seq.empty,
        SortedSet.empty,
        SortedSet.from(defaultIvyDeps.iterator.map(renderIvy)),
        SortedSet.from(defaultModuleDeps),
        SortedSet.from(compileIvyDeps.iterator.map(renderIvy)),
        SortedSet.from(compileModuleDeps),
        SortedSet.from(runIvyDeps.iterator.map(renderIvy)),
        SortedSet.from(runModuleDeps),
        testModule,
        SortedSet.empty,
        SortedSet.from(testIvyDeps.iterator.map(renderIvy)),
        SortedSet.from(testModuleDeps),
        SortedSet.empty,
        SortedSet.empty
      )
    })(objectName => {
      val extractedIvyDeps = ivyDepsByType.view.mapValues(_.map(dep => {
        val depName = s"`${dep.organization}:${dep.name}`"
        ((depName, renderIvy(dep)), s"$objectName.$depName")
      }))

      val extractedDefaultIvyDeps = extractedIvyDeps.getOrElse(Default, Seq.empty)
      val extractedCompileIvyDeps = extractedIvyDeps.getOrElse(Compile, Seq.empty)
      val extractedRunIvyDeps = extractedIvyDeps.getOrElse(Run, Seq.empty)
      val extractedTestIvyDeps = extractedIvyDeps.getOrElse(Test, Seq.empty)

      IrScopedDeps(
        extractedIvyDeps.values.flatMap(_.map(_._1)).toSeq,
        SortedSet.empty,
        SortedSet.from(extractedDefaultIvyDeps.iterator.map(_._2)),
        SortedSet.from(defaultModuleDeps),
        SortedSet.from(extractedCompileIvyDeps.iterator.map(_._2)),
        SortedSet.from(compileModuleDeps),
        SortedSet.from(extractedRunIvyDeps.iterator.map(_._2)),
        SortedSet.from(runModuleDeps),
        testModule,
        SortedSet.empty,
        SortedSet.from(extractedTestIvyDeps.iterator.map(_._2)),
        SortedSet.from(testModuleDeps),
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
      baseProject: Option[String] = None,
      @arg(doc = "the custom sbt executable location")
      customSbt: Option[String] = None
  )
}
