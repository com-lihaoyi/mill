package mill.main.gradle

import mainargs.{Flag, ParserForClass, arg, main}
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.{
  BuildGenUtil,
  BuildObject,
  IrBuild,
  IrPom,
  IrScopedDeps,
  IrTrait,
  Node,
  Tree
}
import mill.util.Jvm
import org.gradle.api.plugins.JavaPlugin
import org.gradle.tooling.GradleConnector

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.jdk.CollectionConverters.*

/**
 * Converts a Gradle build to Mill by generating Mill build file(s).
 *
 * The generated output should be considered scaffolding and will likely require edits to complete conversion.
 *
 * ===Capabilities===
 * The conversion
 *  - handles deeply nested modules
 *  - configures dependencies for configurations:
 *    - implementation / api
 *    - compileOnly / compileOnlyApi
 *    - runtimeOnly
 *    - testImplementation
 *    - testCompileOnly
 *  - configures testing frameworks:
 *    - JUnit 4
 *    - JUnit 5
 *    - TestNG
 *
 * ===Limitations===
 * The conversion does not support
 *  - custom dependency configurations
 *  - custom tasks
 *  - non-Java sources
 */
@mill.api.internal
object BuildGen {

  def main(args: Array[String]): Unit = {
    val cfg = ParserForClass[BuildGenConfig].constructOrExit(args.toSeq)
    run(cfg)
  }

  private def run(cfg: BuildGenConfig): Unit = {
    val workspace = os.pwd

    println("converting Gradle build")
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

        val output = convert(input, cfg)
        writeBuildObject(if (cfg.merge.value) compactBuildTree(output) else output)

        println("converted Gradle build to Mill")
      } finally connection.close()
    } finally connector.disconnect()
  }

  private def writeGradleInitScript: os.Path = {
    val file = os.temp.dir() / "init.gradle"
    val classpath =
      os.Path(classOf[ProjectTreePlugin].getProtectionDomain.getCodeSource.getLocation.toURI)
    val plugin = classOf[ProjectTreePlugin].getName
    val contents =
      s"""initscript {
         |    dependencies {
         |        classpath files(${escape(classpath.toString()) /* escape for Windows */})
         |    }
         |}
         |
         |allprojects {
         |    apply plugin: $plugin
         |}
         |""".stripMargin
    os.write(file, contents)
    file
  }

  private def convert(
      input: Tree[Node[ProjectModel]],
      cfg: BuildGenConfig
  ): Tree[Node[BuildObject]] = {
    val packages = // for resolving moduleDeps
      buildPackages(input)(project => (project.group(), project.name(), project.version()))

    val (
      baseJavacOptions,
      baseRepos,
      baseNoPom,
      basePublishVersion,
      basePublishProperties,
      baseModuleTypedef
    ) =
      cfg.shared.baseModule match {
        case Some(baseModule) =>
          val project = {
            val projects = input.nodes(Tree.Traversal.BreadthFirst).map(_.value).toSeq
            cfg.baseProject
              .flatMap(name => projects.collectFirst { case m if name == m.name => m })
              .orElse(projects.collectFirst { case m if null != m.maven().pom() => m })
              .orElse(projects.collectFirst { case m if !m.maven().repositories().isEmpty => m })
              .getOrElse(input.node.value)
          }
          if (packages.size > 1) {
            println(s"settings from ${project.name()} will be shared in base module")
          }
          val supertypes =
            Seq("MavenModule") ++
              Option.when(null != project.maven().pom()) { "PublishModule" }

          val javacOptions = getJavacOptions(project)
          val repos = getRepositories(project)
          val pomSettings = extractPomSettings(project)
          val publishVersion = getPublishVersion(project)

          val typedef = IrTrait(
            cfg.shared.jvmId,
            baseModule,
            supertypes,
            javacOptions,
            pomSettings,
            publishVersion,
            Nil
          )

          (javacOptions, repos, pomSettings == null, publishVersion, Seq.empty, typedef)
        case None =>
          (Seq.empty, Seq.empty, true, "", Seq.empty, null)
      }

    val moduleSupertype = cfg.shared.baseModule.getOrElse("MavenModule")

    input.map { case build @ Node(dirs, project) =>
      val name = project.name()
      println(s"converting module $name")

      val millSourcePath = os.Path(project.directory())
      val pom = project.maven().pom()

      val hasPom = null != pom
      val hasSource = os.exists(millSourcePath / "src")
      val hasTest = os.exists(millSourcePath / "src/test")
      val isNested = dirs.nonEmpty

      val supertypes =
        Seq("RootModule") ++
          Option.when(hasPom && baseNoPom) { "PublishModule" } ++
          Option.when(isNested || hasSource) { moduleSupertype }

      val scopedDeps = extractScopedDeps(project, packages, cfg)

      val inner = IrBuild(
        scopedDeps,
        cfg.shared.testModule,
        hasTest,
        dirs,
        repos = getRepositories(project).diff(baseRepos),
        javacOptions = {
          val options = getJavacOptions(project).diff(baseJavacOptions)
          if (options == baseJavacOptions) Seq.empty else options
        },
        project.name(),
        pomSettings = if (baseNoPom) extractPomSettings(project) else null,
        publishVersion = {
          val version = getPublishVersion(project)
          if (version == basePublishVersion) null else version
        },
        packaging = null,
        pomParentArtifact = null,
        resources = Nil,
        testResources = Nil,
        publishProperties = Nil
      )

      build.copy(value =
        BuildObject(
          renderImports(cfg.shared.baseModule, isNested, packages.size).to(SortedSet),
          scopedDeps.companions,
          supertypes,
          BuildGenUtil.renderIrBuild(inner),
          if (isNested || baseModuleTypedef == null) ""
          else BuildGenUtil.renderIrTrait(baseModuleTypedef)
        )
      )
    }
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
      val licenses = pom.licenses().iterator().asScala
        .map(lic => mrenderLicense(lic.name(), lic.name(), lic.url()))
      val versionControl = Option(pom.scm()).fold(renderVersionControl())(scm =>
        renderVersionControl(scm.url(), scm.connection(), scm.devConnection(), scm.tag())
      )
      val developers = pom.devs().iterator().asScala
        .map(dev => renderDeveloper(dev.id(), dev.name(), dev.url(), dev.org(), dev.orgUrl()))

      IrPom(
        pom.description(),
        project.group(), // Mill uses group for POM org
        pom.url(),
        licenses,
        versionControl,
        developers
      )
    }
  }

  def extractScopedDeps(
      project: ProjectModel,
      packages: PartialFunction[(String, String, String), String],
      cfg: BuildGenConfig
  ) = {
    var sd = IrScopedDeps()
    val hasTest = os.exists(os.Path(project.directory()) / "src/test")
    val _java = project._java()
    if (null != _java) {
      val ivyDep: JavaModel.Dep => String =
        cfg.shared.depsObject.fold(interpIvy(_)) { objName => dep =>
          val depName = s"`${dep.group()}:${dep.name()}`"
          sd = sd.copy(namedIvyDeps = sd.namedIvyDeps :+ ((depName, interpIvy(dep))))
          s"$objName.$depName"
        }
      def appendIvyDepPackage(
          deps: IterableOnce[JavaModel.Dep],
          onPackage: String => IrScopedDeps,
          onIvy: (String, (String, String, String)) => IrScopedDeps
      ) = {
        for (dep <- deps) {
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
    sd.copy(companions =
      cfg.shared.depsObject.fold(SortedMap.empty[String, BuildObject.Constants])(name =>
        SortedMap((name, SortedMap(sd.namedIvyDeps.toSeq *)))
      )
    )
  }
}

@main
@mill.api.internal
case class BuildGenConfig(
    shared: BuildGenUtil.Config,
    @arg(doc = "name of Gradle project to extract settings for --base-module", short = 'g')
    baseProject: Option[String] = None,
    @arg(doc = "merge build files generated for a multi-module build", short = 'm')
    merge: Flag = Flag()
)
