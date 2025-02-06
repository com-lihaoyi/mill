package mill.main.sbt

import mainargs.{ParserForClass, main}
import mill.main.buildgen.*
import mill.main.buildgen.BuildGenUtil.escape

/**
 * Converts an SBT build to Mill by generating Mill build file(s).
 * The implementation uses the SBT
 * [[https://www.scala-sbt.org/1.x/docs/Combined+Pages.html#addPluginSbtFile+command addPluginSbtFile command]]
 * to add a plugin and a task to extract the settings for a project using a custom model.
 *
 * The generated output should be considered scaffolding and will likely require edits to complete conversion.
 *
 * ===Capabilities===
 * The conversion
 *  - handles deeply nested modules
 *  - configures dependencies for configurations:
 *    - Compile
 *    - Runtime
 *    - Test
 *  - configures testing frameworks:
 *    - JUnit 4
 *    - JUnit 5
 *    - TestNG
 *    - ScalaTest
 *    - Specs2
 *    - ScalaCheck
 * ===Limitations===
 * The conversion does not support
 *  - custom configurations
 *  - custom tasks
 *  - sources other than Scala on JVM and Java
 */
@mill.api.internal
object SbtBuildGenMain extends BuildGenBase[IrBuild, String] {
  type C = Config

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[Config].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: Config): Unit = {
    val workspace = os.pwd

    println("converting SBT build")

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

    val exitCode = Process(
      Seq(
        sbtExecutable,
        s"-addPluginSbtFile=${writeSbtFile().toString}",
        "millInitGenerateProjectTree"
      ),
      workspace.toIO
    ).!

    println("Exit code from running the `millInitGenerateProjectTree` sbt task: " + exitCode)

    import upickle.default.*
    val projectTree =
      read[ProjectTree](os.read(workspace / "target" / "mill-init-project-tree.json"))
    println("Project tree retrieved: " + projectTree)

    /*
    val connector = GradleConnector.newConnector()

    val args =
      cfg.shared.jvmId.map { id =>
        println(s"resolving Java home for jvmId $id")
        val home = Jvm.resolveJavaHome(id).getOrThrow
        s"-Dorg.gradle.java.home=$home"
      } ++ Seq("--init-script", writeGradleInitScript.toString())

    try {
      println("connecting to Gradle daemon")
      val connection = connector.forProjectDirectory(workspace.toIO).connect()
      try {
        val root = connection.model(classOf[ProjectTree])
          .withArguments(args.asJava)
          .get

        val input = Tree.from(root) { tree =>
          val project = tree.project()
          val dirs = os.Path(project.directory()).subRelativeTo(workspace).segments
          val children = tree.children().asScala.sortBy(_.project().name()).iterator
          (Node(dirs, project), children)
        }

        convertWriteOut(cfg, cfg.shared, input)

        println("converted Gradle build to Mill")
      } finally connection.close()
    } finally connector.disconnect()
     */
  }

  private def writeSbtFile(): os.Path = {
    val file = os.temp.dir() / "mill-init.sbt"
    // TODO copy to a temp file if it doesn't work when packaged in a jar
    val sbtPluginJarUrl =
      getClass.getResource("/sbt-mill-init-generate-project-tree.jar").toExternalForm
    val contents =
      s"""addSbtPlugin("com.lihaoyi" % "mill-main-init-sbt-sbt-mill-init-generate-project-tree" % "0.1.0-SNAPSHOT" from ${escape(
          sbtPluginJarUrl
        )})
         |""".stripMargin
    os.write(file, contents)
    file
  }

  override def getSuperTypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[IrBuild]): Seq[String] =
    ???

  override def getBaseInfo(
      input: Tree[Node[IrBuild]],
      cfg: Config,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo = ???

  override def getPackage(model: IrBuild): (String, String, String) = ???

  override def getArtifactId(model: IrBuild): String = ???

  override def extractIrBuild(
      cfg: Config,
      baseInfo: IrBaseInfo,
      build: Node[IrBuild],
      packages: Map[(String, String, String), String]
  ): IrBuild = ???

  /*
  def getBaseInfo(
      input: Tree[Node[ProjectModel]],
      cfg: Config,
      baseModule: String,
      packagesSize: Int
  ): IrBaseInfo = {
    val project = {
      val projects = input.nodes(Tree.Traversal.BreadthFirst).map(_.value).toSeq
      cfg.baseProject
        .flatMap(name => projects.collectFirst { case m if name == m.name => m })
        .orElse(projects.collectFirst { case m if null != m.maven().pom() => m })
        .orElse(projects.collectFirst { case m if !m.maven().repositories().isEmpty => m })
        .getOrElse(input.node.value)
    }
    if (packagesSize > 1) {
      println(s"settings from ${project.name()} will be shared in base module")
    }
    val supertypes =
      Seq("MavenModule") ++
        Option.when(null != project.maven().pom()) { "PublishModule" }

    val javacOptions = getJavacOptions(project)
    val repos = getRepositories(project)
    val pomSettings = extractPomSettings(project)
    val publishVersion = getPublishVersion(project)
    val publishProperties = getPublishProperties(project, cfg.shared)

    val typedef = IrTrait(
      cfg.shared.jvmId,
      baseModule,
      supertypes,
      javacOptions,
      pomSettings,
      publishVersion,
      publishProperties,
      repos
    )

    IrBaseInfo(javacOptions, repos, pomSettings == null, publishVersion, Seq.empty, typedef)
  }

  override def extractIrBuild(
      cfg: Config,
      baseInfo: IrBaseInfo,
      build: Node[ProjectModel],
      packages: Map[(String, String, String), String]
  ): IrBuild = {
    val project = build.value
    val scopedDeps = extractScopedDeps(project, packages, cfg)
    val version = getPublishVersion(project)
    IrBuild(
      scopedDeps = scopedDeps,
      testModule = cfg.shared.testModule,
      hasTest = os.exists(getMillSourcePath(project) / "src/test"),
      dirs = build.dirs,
      repositories = getRepositories(project).diff(baseInfo.repositories),
      javacOptions = getJavacOptions(project).diff(baseInfo.javacOptions),
      projectName = getArtifactId(project),
      pomSettings = if (baseInfo.noPom) extractPomSettings(project) else null,
      publishVersion = if (version == baseInfo.publishVersion) null else version,
      packaging = getPomPackaging(project),
      // not available
      pomParentArtifact = null,
      // skipped, requires relatively new API (JavaPluginExtension.getSourceSets)
      resources = Nil,
      testResources = Nil,
      publishProperties = getPublishProperties(project, cfg.shared)
    )
  }

  def getModuleSupertypes(cfg: Config): Seq[String] =
    Seq(cfg.shared.baseModule.getOrElse("MavenModule"))

  def getPackage(project: ProjectModel): (String, String, String) = {
    (project.group(), project.name(), project.version())
  }

  def getArtifactId(model: ProjectModel): String = model.name()

  def getMillSourcePath(model: ProjectModel): Path = os.Path(model.directory())

  def getSuperTypes(cfg: Config, baseInfo: IrBaseInfo, build: Node[ProjectModel]): Seq[String] = {
    Seq("RootModule") ++
      Option.when(null != build.value.maven().pom() && baseInfo.noPom) { "PublishModule" } ++
      Option.when(build.dirs.nonEmpty || os.exists(getMillSourcePath(build.value) / "src")) {
        getModuleSupertypes(cfg)
      }.toSeq.flatten
  }

  def groupArtifactVersion(dep: JavaModel.Dep): (String, String, String) =
    (dep.group(), dep.name(), dep.version())

  def getJavacOptions(project: ProjectModel): Seq[String] = {
    val _java = project._java()
    if (null == _java) Seq.empty
    else _java.javacOptions().asScala.toSeq
  }

  def getRepositories(project: ProjectModel): Seq[String] =
    project.maven().repositories().asScala.toSeq.sorted.map(uri =>
      s"coursier.maven.MavenRepository(${escape(uri.toString)})"
    )

  def getPomPackaging(project: ProjectModel): String = {
    val pom = project.maven().pom()
    if (null == pom) null else pom.packaging()
  }

  def getPublishProperties(project: ProjectModel, cfg: BuildGenUtil.Config): Seq[(String, String)] =
    if (cfg.publishProperties.value) {
      val pom = project.maven().pom()
      if (null == pom) Seq.empty
      else pom.properties().iterator().asScala
        .map(prop => (prop.key(), prop.value()))
        .toSeq
    } else Seq.empty

  def getPublishVersion(project: ProjectModel): String =
    project.version() match {
      case "" | "unspecified" => null
      case version => version
    }

  def interpIvy(dep: JavaModel.Dep): String = {
    BuildGenUtil.renderIvyString(dep.group(), dep.name(), dep.version())
  }

  def extractPomSettings(project: ProjectModel): IrPom = {
    val pom = project.maven.pom()
    if (null == pom) null
    else {
      IrPom(
        pom.description(),
        project.group(), // Mill uses group for POM org
        pom.url(),
        licenses = pom.licenses().asScala
          .map(lic => IrLicense(lic.name(), lic.name(), lic.url()))
          .toSeq,
        versionControl = Option(pom.scm()).fold(IrVersionControl(null, null, null, null))(scm =>
          IrVersionControl(scm.url(), scm.connection(), scm.devConnection(), scm.tag())
        ),
        developers = pom.devs().asScala
          .map(dev => IrDeveloper(dev.id(), dev.name(), dev.url(), dev.org(), dev.orgUrl()))
          .toSeq
      )
    }
  }

  def extractScopedDeps(
      project: ProjectModel,
      packages: PartialFunction[(String, String, String), String],
      cfg: Config
  ): IrScopedDeps = {
    var sd = IrScopedDeps()
    val hasTest = os.exists(os.Path(project.directory()) / "src/test")
    val _java = project._java()
    if (null != _java) {
      val ivyDep: JavaModel.Dep => String =
        cfg.shared.depsObject.fold(interpIvy(_)) { objName => dep =>
          val depName = s"`${dep.group()}:${dep.name()}`"
          sd = sd.copy(namedIvyDeps = sd.namedIvyDeps :+ (depName, interpIvy(dep)))
          s"$objName.$depName"
        }

      def appendIvyDepPackage(
          deps: IterableOnce[JavaModel.Dep],
          onPackage: String => IrScopedDeps,
          onIvy: (String, (String, String, String)) => IrScopedDeps
      ): Unit = {
        for (dep <- deps.iterator) {
          val id = groupArtifactVersion(dep)
          if (packages.isDefinedAt(id)) sd = onPackage(packages(id))
          else {
            val ivy = ivyDep(dep)
            sd = onIvy(ivy, id)
          }
        }
      }
      _java.configs().forEach { config =>
        import JavaPlugin.*

        val conf = config.name()
        conf match {
          case IMPLEMENTATION_CONFIGURATION_NAME | API_CONFIGURATION_NAME =>
            appendIvyDepPackage(
              config.deps.asScala,
              onPackage = v => sd.copy(mainModuleDeps = sd.mainModuleDeps + v),
              onIvy = (v, id) =>
                if (isBom(id)) sd.copy(mainBomIvyDeps = sd.mainBomIvyDeps + v)
                else sd.copy(mainIvyDeps = sd.mainIvyDeps + v)
            )

          case COMPILE_ONLY_CONFIGURATION_NAME | COMPILE_ONLY_API_CONFIGURATION_NAME =>

            appendIvyDepPackage(
              config.deps.asScala,
              onPackage = v => sd.copy(mainCompileModuleDeps = sd.mainCompileModuleDeps + v),
              onIvy = (v, id) => sd.copy(mainCompileIvyDeps = sd.mainCompileIvyDeps + v)
            )

          case RUNTIME_ONLY_CONFIGURATION_NAME =>
            appendIvyDepPackage(
              config.deps.asScala,
              onPackage = v => sd.copy(mainRunModuleDeps = sd.mainRunModuleDeps + v),
              onIvy = (v, id) => sd.copy(mainRunIvyDeps = sd.mainRunIvyDeps + v)
            )

          case TEST_IMPLEMENTATION_CONFIGURATION_NAME =>

            appendIvyDepPackage(
              config.deps.asScala,
              onPackage = v => sd.copy(testModuleDeps = sd.testModuleDeps + v),
              onIvy = (v, id) =>
                if (isBom(id)) sd.copy(testBomIvyDeps = sd.testBomIvyDeps + v)
                else sd.copy(testIvyDeps = sd.testIvyDeps + v)
            )
            config.deps.forEach { dep =>
              if (hasTest && sd.testModule.isEmpty) {
                sd = sd.copy(testModule = testModulesByGroup.get(dep.group()))
              }
            }

          case TEST_COMPILE_ONLY_CONFIGURATION_NAME =>
            appendIvyDepPackage(
              config.deps.asScala,
              onPackage = v => sd.copy(testCompileModuleDeps = sd.testCompileModuleDeps + v),
              onIvy = (v, id) => sd.copy(testCompileIvyDeps = sd.testCompileIvyDeps + v)
            )

          case name =>
            config.deps.forEach { dep =>
              val id = groupArtifactVersion(dep)
              println(s"ignoring $name dependency $id")
            }
        }
      }
    }
    sd
  }
   */

  @main
  @mill.api.internal
  case class Config(
      shared: BuildGenUtil.Config
  )
}
