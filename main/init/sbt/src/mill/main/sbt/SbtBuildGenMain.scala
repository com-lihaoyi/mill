package mill.main.sbt

import mainargs.{ParserForClass, main}
import mill.constants.Util
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.IrDependencyType.*
import os.Path

import scala.collection.immutable.SortedSet
import scala.collection.{MapView, View}

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
object SbtBuildGenMain extends BuildGenBase[Project, String, (BuildInfo, Tree[Node[Project]])] {
  type C = Config

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[Config].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: Config): Unit = {
    val workspace = os.pwd

    println("converting sbt build")

    val systemSbt = if (Util.isWindows) "sbt.bat" else "sbt"

    // resolve the sbt executable
    // https://raw.githubusercontent.com/paulp/sbt-extras/master/sbt
    val sbtExecutable = if (os.exists(workspace / "sbt"))
      "./sbt"
    else if (os.exists(workspace / "sbtx"))
      "./sbtx"
    else if (
      // The return code is somehow 1 instead of 0.
      os.call((systemSbt, "--help"), check = false).exitCode == 1
    )
      systemSbt
    else
      throw new RuntimeException(
        s"No sbt executable (`./sbt`, `./sbtx`, or system-wide `$systemSbt`) found"
      )

    println("Running the added `millInitExportBuild` sbt task to export the build")

    val exitCode = os.call(
      (sbtExecutable, s"-addPluginSbtFile=${writeSbtFile().toString}", "millInitExportBuild"),
      cwd = workspace,
      stdout = os.Inherit
    ).exitCode

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

    // Types have to be specified explicitly here for the code to be resolved correctly in IDEA.
    val projectNodesByParentDirs: Map[Option[Seq[String]], View[Node[Project]]] =
      buildExport.projects.view
        .map(project =>
          Node(os.Path(project.projectDirectory).subRelativeTo(workspace).segments, project)
        )
        .groupBy(node => {
          val dirs = node.dirs
          Option.when(dirs.nonEmpty)(dirs.dropRight(1))
        })

    /*
    TODO This does not support converting child projects nested in a parent directory which is not a project yet.
     Supporting this may involve refactoring `Node[Project]` into `Node[Option[Project]]`
     or adding a subproject as a direct child with its ancestor directory project (I am not sure whether this works).
     */
    val input = Tree.from(projectNodesByParentDirs(None).head) { node =>
      val dirs = node.dirs
      val children = projectNodesByParentDirs.getOrElse(Some(dirs), Seq.empty)
      (node, children)
    }

    convertWriteOut(cfg, cfg.shared, (buildExport.defaultBuildInfo, input))

    println("converted sbt build to Mill")
  }

  private def writeSbtFile(): os.Path = {
    val tempDir = os.temp.dir()
    // This doesn't work in integration tests when Mill is packaged.
    /*
    val sbtPluginJarUrl =
      getClass.getResource("/sbt-mill-init-export-build-assembly.jar").toExternalForm
     */
    val file = tempDir / "mill-init.sbt"
    val sbtPluginJarName = "sbt-mill-init-export-build-assembly.jar"
    val sbtPluginJarStream = getClass.getResourceAsStream(s"/$sbtPluginJarName")
    val sbtPluginJarPath = tempDir / sbtPluginJarName
    os.write(sbtPluginJarPath, sbtPluginJarStream)
    val contents =
      s"""addSbtPlugin("com.lihaoyi" % "mill-main-init-sbt-sbt-mill-init-export-build" % "dummy-version" from ${escape(
          sbtPluginJarPath.wrapped.toUri.toString
        )})
         |""".stripMargin
    os.write(file, contents)
    file
  }

  override def getProjectTree(input: (BuildInfo, Tree[Node[Project]])): Tree[Node[Project]] =
    input._2

  def sbtSupertypes = Seq("SbtModule", "PublishModule") // always publish

  def getBaseInfo(
      input: (BuildInfo, Tree[Node[Project]]),
      cfg: Config,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo = {
    val buildInfo = input._1

    import buildInfo.*
    val javacOptions = getJavacOptions(buildInfo)
    val repositories = getRepositories(buildInfo)
    val pomSettings = extractPomSettings(buildPublicationInfo)
    val publishVersion = getPublishVersion(buildInfo)

    val typedef = IrTrait(
      None, // There doesn't seem to be a Java version setting in sbt. See https://stackoverflow.com/a/76456295/5082913.
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

    IrBaseInfo(
      javacOptions,
      scalaVersion,
      scalacOptions,
      repositories,
      noPom = false, // always publish
      publishVersion,
      Seq.empty,
      typedef
    )
  }

  override def extractIrBuild(
      cfg: Config,
      baseInfo: IrBaseInfo,
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
      hasTest = os.exists(getMillSourcePath(project) / "src/test"),
      dirs = build.dirs,
      repositories = getRepositories(buildInfo).diff(baseInfo.repositories),
      javacOptions = getJavacOptions(buildInfo).diff(baseInfo.javacOptions),
      scalaVersion =
        if (buildInfo.scalaVersion != baseInfo.scalaVersion) buildInfo.scalaVersion else None,
      scalacOptions = buildInfo.scalacOptions.map(scalacOptions =>
        baseInfo.scalacOptions.fold(scalacOptions)(baseScalacOptions =>
          scalacOptions.diff(baseScalacOptions)
        )
      ),
      projectName = project.name,
      pomSettings = takeIrPomIfNeeded(baseInfo, extractPomSettings(buildInfo.buildPublicationInfo)),
      publishVersion = if (version == baseInfo.publishVersion) null else version,
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

  def getSuperTypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[Project]): Seq[String] =
    Seq("RootModule") ++ getModuleSupertypes(cfg)

  def groupArtifactVersion(dep: Dependency): (String, String, String) =
    (dep.organization, dep.name, dep.revision)

  def getJavacOptions(buildInfo: BuildInfo): Seq[String] =
    buildInfo.javacOptions.getOrElse(Seq.empty)

  def getRepositories(buildInfo: BuildInfo): Seq[String] =
    buildInfo.resolvers.getOrElse(Seq.empty).map(resolver =>
      s"coursier.maven.MavenRepository(${escape(resolver.root)})"
    )

  def getPublishVersion(buildInfo: BuildInfo): String =
    buildInfo.buildPublicationInfo.version.orNull

  // originally named `ivyInterp` in the Maven and module
  def renderIvy(dependency: Dependency): String = {
    /*
    TODO `type, `classifier`, and `exclusions` are not processed yet.
     Processing them involves extracting information from `ModuleID.explicitArtifacts`
     which is a `Vector` of `sbt.librarymanagement.Artifact` and `sbt.librarymanagement.InclExclRule`s.
     */
    import dependency.*
    s"ivy\"$organization${if (crossVersion) "::" else ":"}$name:$revision\""
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
      shared: BuildGenUtil.BasicConfig
  )
}
