package mill.main.sbt

import _root_.sbt._
import mill.main.buildgen.BuildConventions._
import mill.main.buildgen._

import scala.util.Using

/**
 * @see [[https://github.com/JetBrains/sbt-structure/#sideloading Sideloading]]
 */
object ExportSbtBuildScript extends (State => State) {

  // ((moduleDir, isCrossPlatform), ModuleRepr)
  type SbtModuleRepr = ((Seq[String], Boolean), ModuleRepr)

  // directory for exporting files
  val millInitExportArgs = settingKey[File]("")
  val millInitExportSbtModules = taskKey[Unit]("")
  val millInitExportSbtModule = taskKey[Unit]("")
  // https://github.com/portable-scala/sbt-crossproject/
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")

  def apply(state: State) = {
    val extracted = Project.extract(state)
    import extracted._
    val globalSettings =
      Project.transform(_ => GlobalScope, Seq(millInitExportSbtModules := exportSbtModules.value))
    val projectSettings = structure.allProjectPairs.flatMap {
      case (project, ref) if project.aggregate.isEmpty =>
        Project.transform(
          Scope.resolveScope(Scope(Select(ref), Zero, Zero, Zero), ref.build, rootProject),
          Seq(millInitExportSbtModule := exportSbtModule.value)
        )
      case _ => Nil
    }
    val sessionSettings = session.appendRaw(globalSettings ++ projectSettings)
    BuiltinCommands.reapply(sessionSettings, structure, state)
  }

  /**
   * Runs [[exportSbtModule]] for all projects. This task is required to ensure projects not
   * aggregated by the root project are exported.
   */
  def exportSbtModules = Def.taskDyn {
    val structure = Project.structure(Keys.state.value)
    Def.task {
      std.TaskExtra.joinTasks(structure.allProjectRefs.flatMap(ref =>
        (ref / millInitExportSbtModule).get(structure.data)
      )).join.value
    }
  }

  /**
   * Exports the module configuration (and cross-platform metadata) for a project to a JSON file.
   * @note The exported configuration is specific to the current Scala version in the SBT shell.
   */
  def exportSbtModule = Def.taskDyn {
    val project = Keys.thisProject.value
    val scalaVersion = Keys.scalaVersion.value
    val outDir = millInitExportArgs.value
    val outFile = os.Path(outDir) / s"${project.id}-$scalaVersion.json"
    // duplicate invocation occurs when crossScalaVersions is a subset of all versions in the build
    Def.task(if (!os.exists(outFile)) {
      val depsWithConfigs = Keys.libraryDependencies.value.collect {
        case dep if !skipDep(dep) => (dep, dep.configurations.toSeq.flatMap(_.split(';')))
      }
      def mvnDeps(configsPredicate: Seq[String] => Boolean) = depsWithConfigs.iterator.collect {
        case (dep, configs) if configsPredicate(configs) => toMvnDep(dep)
      }.toSeq

      val isCrossVersion = Keys.crossScalaVersions.value.length > 1
      val projectDepsWithConfigs = project.dependencies
        .map(dep => dep -> dep.configuration.toSeq.flatMap(_.split(";")))
      val structure = Project.structure(Keys.state.value)
      def moduleDeps(configsPredicate: Seq[String] => Boolean) =
        projectDepsWithConfigs.collect {
          case (dep, configs) if configsPredicate(configs) =>
            for {
              depBaseDir <- (dep.project / Keys.baseDirectory).get(structure.data)
              depSegments = toSegments(os.Path(depBaseDir))
              depCrossScalaVersions =
                (dep.project / Keys.crossScalaVersions).get(structure.data).getOrElse(Nil)
              crossArgs = if (depCrossScalaVersions.length > 1)
                Map((depSegments.length - 1, if (isCrossVersion) "()" else "(scalaVersion())"))
              else Map.empty[Int, String]
            } yield ModuleConfig.ModuleDep(depSegments, crossArgs)
        }.flatten

      val baseDir = os.Path(project.base)
      val segments = toSegments(baseDir)
      val moduleDir = crossProjectBaseDirectory.?.value.fold(
        // crossProjectBaseDirectory was added in v1.3.0
        if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) baseDir / os.up else baseDir
      )(os.Path(_))
      val isCrossPlatform = baseDir != moduleDir

      val coursierModuleConfig = Keys.resolvers.value.collect {
        case r: MavenRepository if !Seq(Resolver.mavenCentral, Resolver.mavenLocal).contains(r) =>
          r.root
      } match {
        case Nil => None
        case repositories => Some(CoursierModuleConfig(repositories))
      }
      val javaHomeModuleConfig = findJavaHomeModuleConfig(javacOptions = Keys.javacOptions.value)
      val javacOptions = Keys.javacOptions.value
      val javaModuleConfig = JavaModuleConfig(
        mvnDeps = mvnDeps(cs => cs.isEmpty || cs.contains("compile")),
        compileMvnDeps = mvnDeps(_.exists(c => c == "provided" || c == "optional")),
        runMvnDeps = mvnDeps(_.contains("runtime")),
        moduleDeps = moduleDeps(cs =>
          cs.isEmpty || cs.exists(c => c == "compile" || c.startsWith("compile->"))
        ),
        compileModuleDeps = moduleDeps(_.exists(c =>
          c == "provided" || c == "optional" ||
            c.startsWith("provided->") || c.startsWith("optional->")
        )),
        runModuleDeps = moduleDeps(_.exists(c => c == "runtime" || c.startsWith("runtime->"))),
        javacOptions = javacOptions,
        artifactName = overrideArtifactName(Keys.moduleName.value, segments)
      )
      val publishModuleConfig = if ((Keys.publish / Keys.skip).value || !Keys.publishArtifact.value)
        None
      else Some(PublishModuleConfig(
        pomSettings = toPomSettings(Keys.projectInfo.value, Keys.organization.value),
        publishVersion = Keys.version.value,
        versionScheme = Keys.versionScheme.value
      ))
      val scalaModuleConfig = ScalaModuleConfig(
        scalaVersion = if (isCrossVersion) null else scalaVersion,
        scalacOptions = Keys.scalacOptions.value.filterNot(skipScalacOption),
        scalacPluginMvnDeps = mvnDeps(_.contains("plugin->default(compile)"))
      )
      val scalaJSModuleConfig = Keys.libraryDependencies.value.collectFirst {
        case dep
            if dep.organization == "org.scala-js" && dep.name.startsWith("scalajs-library") =>
          ScalaJSModuleConfig(dep.revision)
      }
      val scalaNativeModuleConfig = Keys.libraryDependencies.value.collectFirst {
        case dep
            if dep.organization == "org.scala-native" &&
              dep.configurations.contains("plugin->default(compile)") =>
          ScalaNativeModuleConfig(dep.revision)
      }
      val sbtPlatformModuleConfig = if (isCrossPlatform) Some(SbtPlatformModuleConfig(
        // only sbtcrossproject.CrossType.Full requires additional configuration
        sourcesRootFolders = if (os.isDir(moduleDir / "shared")) Seq("shared") else Nil
      ))
      else None
      val configs = Seq(scalaJSModuleConfig, scalaNativeModuleConfig).flatten ++
        Seq(scalaModuleConfig, javaModuleConfig) ++
        Seq(
          publishModuleConfig,
          javaHomeModuleConfig,
          coursierModuleConfig,
          sbtPlatformModuleConfig
        ).flatten
      val useVersionRanges = isCrossVersion && os.walk.stream(moduleDir).exists(path =>
        os.isDir(path) && path.last.matches("""^scala-\d+\.\d*(-.*|\+)$""")
      )
      val (supertypes, mixins) = mainHierarchy(isCrossVersion, useVersionRanges, configs)

      val testModule = {
        val testMvnDeps = mvnDeps(_.contains("test"))
        findTestModuleMixin(testMvnDeps).map { testModuleMixin =>
          val (testSupertypes, testMixins) =
            testHierarchy(supertypes ++ mixins, testModuleMixin)
          val testConfigs = Seq(
            JavaModuleConfig(
              mvnDeps = testMvnDeps,
              moduleDeps = moduleDeps(_.exists(c => c == "test" || c == "test->compile")) ++
                moduleDeps(_.contains("test->test")).map(dep => dep.copy(dep.segments :+ "test")),
              compileModuleDeps =
                moduleDeps(_.exists(c => c == "test->provided" || c == "test->optional")),
              runModuleDeps = moduleDeps(_.contains("test->runtime"))
            )
          )
          TestModuleRepr(
            supertypes = testSupertypes,
            mixins = testMixins,
            configs = testConfigs,
            crossConfigs = if (isCrossVersion) Seq((scalaVersion, testConfigs)) else Nil,
            testParallelism = false,
            testSandboxWorkingDir = false
          )
        }
      }

      val sbtModule = (
        (moduleDir.subRelativeTo(os.pwd).segments, isCrossPlatform),
        ModuleRepr(
          segments = segments,
          supertypes = supertypes,
          mixins = mixins,
          configs = configs,
          crossConfigs = if (isCrossVersion) Seq((scalaVersion, configs)) else Nil,
          testModule = testModule
        )
      )
      Using.resource(os.write.outputStream(outFile))(
        upickle.default.writeToOutputStream(sbtModule, _)
      )
    })
  }

  def mainHierarchy(
      isCrossVersion: Boolean,
      useVersionRanges: Boolean,
      configs: Seq[ModuleConfig]
  ) = {
    var isCrossPlatform = false
    val supertypes = Seq.newBuilder[String]
    val mixins = Seq.newBuilder[String]
    configs.foreach {
      case _: ScalaJSModuleConfig => supertypes += "ScalaJSModule"
      case _: ScalaNativeModuleConfig => supertypes += "ScalaNativeModule"
      case _: PublishModuleConfig => supertypes += "PublishModule"
      case _: SbtPlatformModuleConfig =>
        isCrossPlatform = true
        supertypes += "SbtPlatformModule"
      case _ =>
    }
    if (isCrossVersion) {
      supertypes += "CrossScalaModule" // added for sharing cross configs in base traits
      mixins += (if (isCrossPlatform) "CrossSbtPlatformModule" else "CrossSbtModule")
      if (useVersionRanges) mixins += "CrossScalaVersionRanges"
    } else supertypes += "SbtModule"
    (supertypes.result(), mixins.result())
  }

  def testHierarchy(mainHierarchy: Seq[String], testModuleMixin: String) = {
    val supertypes = Seq.newBuilder[String]
    val mixins = Seq.newBuilder[String]
    mainHierarchy.foreach {
      case "ScalaJSModule" => supertypes += "ScalaJSTests"
      case "ScalaNativeModule" => supertypes += "ScalaNativeTests"
      case "SbtModule" => supertypes += "SbtTests"
      case "SbtPlatformModule" => supertypes += "SbtPlatformTests"
      case "CrossSbtModule" => mixins += "CrossSbtTests"
      case "CrossSbtPlatformModule" => mixins += "CrossSbtPlatformTests"
      case _ =>
    }
    mixins += testModuleMixin
    (supertypes.result(), mixins.result())
  }

  def skipDep(dep: ModuleID) =
    (dep.organization == "org.scala-lang" &&
      Seq("scala-library", "scala3-library").exists(dep.name.startsWith)) ||
      (dep.organization == "ch.epfl.lamp" && dep.name.startsWith("dotty")) ||
      (dep.organization == "org.scala-js" && !dep.name.startsWith("scalajs-dom")) ||
      dep.organization == "org.scala-native"

  def skipScalacOption(s: String) =
    s.startsWith("-P") || s.startsWith("-Xplugin") || s.startsWith("-scalajs")

  def toSegments(baseDir: os.Path) =
    // cleanup `CrossType.Pure` module names by dropping the "." prefix
    baseDir.relativeTo(os.pwd).segments.map(_.stripPrefix("."))

  def toMvnDep(dep: ModuleID) = {
    import ModuleConfig.{CrossVersion => CV}
    import dep._
    import librarymanagement.{For2_13Use3, For3Use2_13, Patch}
    val artifact = explicitArtifacts.find(_.name == name)
    ModuleConfig.MvnDep(
      organization = organization,
      name = name,
      version = Option(revision),
      classifier = artifact.flatMap(_.classifier),
      `type` = artifact.map(_.`type`),
      excludes = exclusions.map(x => (x.organization, x.name)),
      cross = crossVersion match {
        case v: Binary => CV.Binary(v.prefix.nonEmpty)
        case v: Full => CV.Full(v.prefix.nonEmpty)
        case _: Patch => CV.Full(platformed = false)
        case v: For2_13Use3 => CV.Constant("_3", v.prefix.nonEmpty)
        case v: For3Use2_13 => CV.Constant("_2.13", v.prefix.nonEmpty)
        case _ => CV.Constant("", platformed = false)
      }
    )
  }

  def toPomSettings(moduleInfo: ModuleInfo, groupId: String) = {
    import moduleInfo._
    ModuleConfig.PomSettings(
      description = description,
      organization = groupId,
      url = homepage.fold[String](null)(_.toExternalForm),
      licenses = licenses.map(toLicense),
      versionControl = toVersionControl(scmInfo),
      developers = developers.map(toDeveloper)
    )
  }

  def toLicense(license: (String, URL)) = {
    val (name, url) = license
    ModuleConfig.License(
      name = name,
      url = url.toExternalForm,
      // default IvyPublisher hardcodes "repo"
      distribution = "repo"
    )
  }

  def toVersionControl(scmInfo: Option[ScmInfo]) =
    scmInfo.fold(ModuleConfig.VersionControl()) { scmInfo =>
      import scmInfo._
      ModuleConfig.VersionControl(
        Some(browseUrl.toExternalForm),
        Some(connection),
        devConnection
      )
    }

  def toDeveloper(developer: Developer) = {
    import developer._
    ModuleConfig.Developer(id, name, url.toExternalForm)
  }
}
