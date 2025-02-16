package mill.main.sbt

import mainargs.{ParserForClass, main}
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.IrDependencyType.*
import os.Path

import scala.collection.View
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
 *  - configures dependencies for configurations:
 *    - no configuration
 *    - Compile
 *    - Provided
 *    - Optional
 *    - Runtime
 *    - Test
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
 * The conversion does not support
 *  - custom configurations
 *  - custom tasks
 *  - sources other than Scala on JVM and Java
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

    import scala.sys.process.*

    // resolve the sbt executable
    // https://raw.githubusercontent.com/paulp/sbt-extras/master/sbt
    val sbtExecutable = if (os.exists(workspace / "sbt"))
      "./sbt"
    else if (os.exists(workspace / "sbtx"))
      "./sbtx"
    else if (
      // The return code is somehow 1 instead of 0.
      Seq("sbt", "--help").!(ProcessLogger(_ => ())) == 1
    )
      "sbt"
    else
      throw new RuntimeException(
        "No sbt executable (`./sbt`, `./sbtx`, or system-wide `sbt`) found"
      )

    println("Running sbt task to generate the project tree")

    Process(
      Seq(
        sbtExecutable,
        s"-addPluginSbtFile=${writeSbtFile().toString}",
        "millInitExportBuild"
      ),
      workspace.toIO
    ).!

    // println("Exit code from running the `millInitExportBuild` sbt task: " + exitCode)

    val buildExportPickled = os.read(workspace / "target" / "mill-init-build-export.json")
    // TODO This is mainly for debugging purposes. Comment out this line if it's unnecessary.
    println("sbt build export retrieved: " + buildExportPickled)
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

    val input = Tree.from(projectNodesByParentDirs(None).head) { node =>
      val dirs = node.dirs
      val children = projectNodesByParentDirs.getOrElse(Some(dirs), Seq.empty)
      (node, children)
    }

    convertWriteOut(cfg, cfg.shared, (buildExport.defaultBuildInfo, input))

    println("converted sbt build to Mill")
  }

  private def writeSbtFile(): os.Path = {
    val file = os.temp.dir() / "mill-init.sbt"
    // TODO copy to a temp file if it doesn't work when packaged in a jar
    val sbtPluginJarUrl =
      getClass.getResource("/sbt-mill-init-export-build-assembly.jar").toExternalForm
    val contents =
      s"""addSbtPlugin("com.lihaoyi" % "mill-main-init-sbt-sbt-mill-init-export-build" % "dummy-version" from ${escape(
          sbtPluginJarUrl
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

    val javacOptions = getJavacOptions(buildInfo)
    val scalacOptions = buildInfo.scalacOptions
    val repositories = getRepositories(buildInfo)
    val pomSettings = extractPomSettings(buildInfo.buildPublicationInfo)
    val publishVersion = getPublishVersion(buildInfo)

    val typedef = IrTrait(
      None, // There doesn't seem to be a Java version setting in sbt. See https://stackoverflow.com/a/76456295/5082913.
      baseModule,
      sbtSupertypes,
      javacOptions,
      scalacOptions,
      pomSettings,
      publishVersion,
      null, // not available in sbt as it seems
      repositories
    )

    IrBaseInfo(
      javacOptions,
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

  def getModuleSupertypes(cfg: Config): Seq[String] =
    cfg.shared.baseModule.fold(sbtSupertypes)(Seq(_))

  def getPackage(project: Project): (String, String, String) = {
    val buildPublicationInfo = project.buildInfo.buildPublicationInfo
    (buildPublicationInfo.organization.orNull, project.name, buildPublicationInfo.version.orNull)
  }

  def getArtifactId(project: Project): String = project.name

  def getMillSourcePath(project: Project): Path = os.Path(project.projectDirectory)

  def getSuperTypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[Project]): Seq[String] =
    Seq("RootModule") ++
      Option.when(build.dirs.nonEmpty || os.exists(getMillSourcePath(build.value) / "src")) {
        getModuleSupertypes(cfg)
      }.iterator.toSeq.flatten

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
    // type, classifier, and exclusions are not processed yet
    import dependency.*
    s"ivy\"$organization:$name${if (crossVersion) "::" else ":"}$revision\""
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
      organizationHomepage.fold("")(_.getOrElse("")),
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

  def extractConfigurationDeps(
      project: Project,
      packages: PartialFunction[(String, String, String), String],
      cfg: Config
  ): IrScopedDeps = {
    // refactored to a functional approach from the original imperative code in Maven and Gradle

    val allDepsByConfiguration = project.allDependencies.groupBy(_.configurations match {
      case None => Default
      case Some(configuration) => configuration match {
          case "compile" => Default
          case "test" => Test
          case "runtime" => Run
          case "provided" | "optional" => Compile
        }
    })

    case class Deps[I, M](ivy: Seq[I], module: Seq[M])

    // Types have to be specified explicitly here for the code to be resolved correctly in IDEA.
    val ivyAndModuleDepsByConfiguration: Map[IrDependencyType, Deps[Dependency, String]] =
      allDepsByConfiguration.view.mapValues(deps => {
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
