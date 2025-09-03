package mill.main.sbt

import _root_.sbt._
import mill.main.buildgen._

import scala.util.Using

/**
 * @see [[https://github.com/JetBrains/sbt-structure/#sideloading Sideloading]]
 */
object ExportSbtBuildScript extends (State => State) {

  // ((moduleDir, isCrossPlatform), ModuleRepr)
  type SbtModuleRepr = ((Seq[String], Boolean), ModuleRepr)

  // directory for exporting files and the name for the test module
  val millInitExportArgs = settingKey[(File, String)]("")
  val millInitExportBuild = taskKey[Unit]("")
  val millInitExportModule = taskKey[Unit]("")
  // https://github.com/portable-scala/sbt-crossproject/
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")

  def apply(state: State) = {
    val extracted = Project.extract(state)
    import extracted._
    val sessionSettings =
      Project.transform(_ => GlobalScope, Seq(millInitExportBuild := exportBuild.value)) ++
        structure.allProjectPairs.flatMap {
          case (project, ref) =>
            if (project.aggregate.isEmpty) Project.transform(
              Scope.resolveScope(Scope(Select(ref), Zero, Zero, Zero), ref.build, rootProject),
              Seq(millInitExportModule := exportModule.value)
            )
            else Nil
        }
    BuiltinCommands.reapply(session.appendRaw(sessionSettings), structure, state)
  }

  // This task is required to ensure projects not aggregated by the root project are exported.
  def exportBuild = Def.taskDyn {
    val structure = Project.structure(Keys.state.value)
    Def.task {
      std.TaskExtra.joinTasks(structure.allProjectRefs.flatMap { ref =>
        (ref / millInitExportModule).get(structure.data)
      }).join.value
    }
  }

  // Generates and writes the ADT, for the current Scala version, for a module.
  def exportModule = Def.taskDyn {
    val project = Keys.thisProject.value
    val (outDir, testModuleName) = millInitExportArgs.value
    val data = Project.structure(Keys.state.value).data

    val mvnDependencies = Keys.libraryDependencies.value.collect {
      case dep
          if !((dep.organization == "org.scala-lang" &&
            Seq("scala-library", "scala3-library").exists(dep.name.startsWith)) ||
            (dep.organization == "org.scala-js" && !dep.name.startsWith("scalajs-dom")) ||
            dep.organization == "org.scala-native" ||
            (dep.organization == "ch.epfl.lamp" && dep.name.startsWith("dotty"))) =>
        (dep, dep.configurations.toSeq.flatMap(_.split(';')))
    }
    def mvnDeps(configsPredicate: Seq[String] => Boolean) =
      mvnDependencies.collect {
        case (dep, configs) if configsPredicate(configs) => toMvnDep(dep)
      }

    val isCrossVersion = Keys.crossScalaVersions.value.length > 1
    val projectDependencies =
      project.dependencies.map(dep => dep -> dep.configuration.toSeq.flatMap(_.split(";")))
    def moduleDeps(configsPredicate: Seq[String] => Boolean) =
      projectDependencies.collect {
        case (dep, configs) if configsPredicate(configs) =>
          for {
            depBaseDir <- (dep.project / Keys.baseDirectory).get(data)
            depSegments = os.Path(depBaseDir).subRelativeTo(os.pwd).segments
            depIsCrossScalaVersion =
              (dep.project / Keys.crossScalaVersions).get(data).getOrElse(Nil).length > 1
            crossArgs = if (depIsCrossScalaVersion)
              Map((depSegments.length - 1, if (isCrossVersion) "()" else "(scalaVersion())"))
            else Map.empty[Int, String]
          } yield JavaModuleConfig.ModuleDep(depSegments, crossArgs)
      }.flatten

    val baseDir = os.Path(project.base)
    val crossPlatformBaseDir = crossProjectBaseDirectory.?.value.map(os.Path(_))
      .orElse( // crossProjectBaseDirectory was added in v1.3.0
        if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) Some(baseDir / os.up) else None)
    val moduleDir = crossPlatformBaseDir.getOrElse(baseDir)
    val isCrossPlatform = baseDir != moduleDir
    val sbtPlatformSupertype = if (isCrossPlatform) Some(
      if (os.isDir(baseDir / os.up / "shared")) "SbtPlatformModule.CrossTypeFull"
      else if (os.isDir(baseDir / os.up / "src")) "SbtPlatformModule.CrossTypePure"
      else "SbtPlatformModule.CrossTypeDummy"
    )
    else None

    Def.task {
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
      val scalaModuleConfig = ScalaModuleConfig(
        scalaVersion = if (isCrossVersion) null else Keys.scalaVersion.value,
        // skip options added by plugins
        scalacOptions = Keys.scalacOptions.value.filterNot(option =>
          option.startsWith("-P") || option.startsWith("-scalajs")
        ),
        scalacPluginMvnDeps = mvnDeps(_.contains("plugin->default(compile)"))
      )
      val javaModuleConfig = JavaModuleConfig(
        mvnDeps = mvnDeps(cs => cs.isEmpty || cs.contains("compile")),
        compileMvnDeps = mvnDeps(_.exists(Seq("provided", "optional").contains)),
        runMvnDeps = mvnDeps(_.contains("runtime")),
        moduleDeps = moduleDeps(cs =>
          cs.isEmpty || cs.contains("compile") || cs.exists(_.startsWith("compile->"))
        ),
        compileModuleDeps = moduleDeps(cs =>
          Seq("provided", "optional").exists(cs.contains) ||
            Seq("provided->", "optional->").exists(s => cs.exists(_.startsWith(s)))
        ),
        runModuleDeps =
          moduleDeps(cs => cs.contains("runtime") || cs.exists(_.startsWith("runtime->"))),
        javacOptions = Keys.javacOptions.value.diff(JavaModuleConfig.unsupportedJavacOptions)
      )
      val publishModuleConfig = if ((Keys.publish / Keys.skip).value || !Keys.publishArtifact.value)
        None
      else Some(PublishModuleConfig(
        pomSettings = toPomSettings(Keys.projectInfo.value),
        publishVersion = Keys.version.value,
        versionScheme = Keys.versionScheme.value,
        artifactMetadata = PublishModuleConfig.Artifact(
          Keys.organization.value,
          Keys.moduleName.value,
          Keys.version.value
        )
      ))
      val mainConfigs = Seq(scalaJSModuleConfig, scalaNativeModuleConfig).flatten ++
        Seq(scalaModuleConfig, javaModuleConfig) ++ publishModuleConfig
      val useVersionRanges = isCrossVersion && os.walk.stream(moduleDir).exists(path =>
        os.isDir(path) && path.last.matches("""^scala-\d+\.\d*(-.*|\+)$""")
      )
      val (mainSupertypes, mainMixins) =
        mainHierarchy(sbtPlatformSupertype, isCrossVersion, useVersionRanges, mainConfigs)

      val testModule =
        if (
          (Test / Keys.unmanagedSourceDirectories).value.exists(_.exists()) ||
          (Test / Keys.unmanagedResourceDirectories).value.exists(_.exists())
        ) {
          val testMvnDeps = mvnDeps(_.contains("test"))
          TestModuleRepr.mixinAndMandatoryMvnDeps(testMvnDeps).map {
            case (mixin, mandatoryMvnDeps) =>
              val (testSupertypes, testMixins) = testHierarchy(mainSupertypes ++ mainMixins, mixin)
              val testConfigs = Seq(JavaModuleConfig(
                mandatoryMvnDeps = mandatoryMvnDeps,
                mvnDeps = testMvnDeps.diff(mandatoryMvnDeps),
                moduleDeps = moduleDeps(cs => Seq("test", "test->compile").exists(cs.contains)) ++
                  moduleDeps(_.contains("test->test"))
                    .map(d => d.copy(d.segments :+ testModuleName)),
                compileModuleDeps =
                  moduleDeps(cs => Seq("test->provided", "test->optional").exists(cs.contains)),
                runModuleDeps = moduleDeps(_.contains("test->runtime"))
              ))
              TestModuleRepr(
                name = testModuleName,
                supertypes = testSupertypes,
                mixins = testMixins,
                configs = testConfigs,
                crossConfigs =
                  if (isCrossVersion) Seq((Keys.scalaVersion.value, testConfigs)) else Nil,
                testParallelism = false,
                testSandboxWorkingDir = false
              )
          }
        } else None

      val sbtModule = (
        (moduleDir.subRelativeTo(os.pwd).segments, isCrossPlatform),
        ModuleRepr(
          segments = baseDir.subRelativeTo(os.pwd).segments,
          supertypes = mainSupertypes,
          mixins = mainMixins,
          configs = mainConfigs,
          crossConfigs = if (isCrossVersion) Seq((Keys.scalaVersion.value, mainConfigs)) else Nil,
          testModule = testModule
        )
      )
      Using.resource(os.write.outputStream(os.temp(
        dir = os.Path(outDir),
        deleteOnExit = false
      )))(upickle.default.writeToOutputStream(sbtModule, _))
    }
  }

  def mainHierarchy(
      sbtPlatformSupertype: Option[String],
      isCrossVersion: Boolean,
      useVersionRanges: Boolean,
      configs: Seq[ModuleConfig]
  ) = {
    val supertypes = Seq.newBuilder[String]
    val mixins = Seq.newBuilder[String]
    configs.foreach {
      case _: ScalaJSModuleConfig => supertypes += "ScalaJSModule"
      case _: ScalaNativeModuleConfig => supertypes += "ScalaNativeModule"
      case _: PublishModuleConfig => supertypes += "PublishModule"
      case _ =>
    }
    supertypes ++= sbtPlatformSupertype
    if (isCrossVersion) {
      supertypes += "CrossScalaModule" // added for sharing cross configs in baseTrait
      mixins += (if (sbtPlatformSupertype.isEmpty) "CrossSbtModule" else "CrossSbtPlatformModule")
      if (useVersionRanges) mixins += "CrossScalaVersionRanges"
    } else supertypes += "SbtModule"
    (supertypes.result(), mixins.result())
  }

  def testHierarchy(mainHierarchy: Seq[String], testFramework: String) = {
    val supertypes = Seq.newBuilder[String]
    val mixins = Seq.newBuilder[String]
    mainHierarchy.foreach {
      case "ScalaJSModule" => supertypes += "ScalaJSTests"
      case "ScalaNativeModule" => supertypes += "ScalaNativeTests"
      case "SbtModule" => supertypes += "SbtTests"
      case s if s.startsWith("SbtPlatformModule") => supertypes += "SbtPlatformTests"
      case "CrossSbtModule" => mixins += "CrossSbtTests"
      case "CrossSbtPlatformModule" => mixins += "CrossSbtPlatformTests"
      case _ =>
    }
    mixins += testFramework
    (supertypes.result(), mixins.result())
  }

  def toMvnDep(dep: ModuleID) = {
    import dep._
    val artifact = explicitArtifacts.find(_.name == name)
    JavaModuleConfig.mvnDep(
      org = organization,
      name = name + (crossVersion match {
        case v: librarymanagement.Constant if v.value.nonEmpty => "_" + v.value
        case _: librarymanagement.For2_13Use3 => "_3"
        case _: librarymanagement.For3Use2_13 => "_2.13"
        case _ => ""
      }),
      version = revision,
      classifier = artifact.flatMap(_.classifier),
      typ = artifact.map(_.`type`),
      excludes = exclusions.map(x => (x.organization, x.name)),
      sep1 = crossVersion match {
        case _: librarymanagement.Full => ":::"
        case _: librarymanagement.Patch => ":::"
        case _: librarymanagement.Binary => "::"
        case _ => ":"
      },
      sep2 = crossVersion match {
        case v: librarymanagement.Full if v.prefix.nonEmpty => "::"
        case v: librarymanagement.Binary if v.prefix.nonEmpty => "::"
        case v: librarymanagement.For2_13Use3 if v.prefix.nonEmpty => "::"
        case v: librarymanagement.For3Use2_13 if v.prefix.nonEmpty => "::"
        case _ => ":"
      }
    )
  }

  def toPomSettings(moduleInfo: ModuleInfo) = {
    import moduleInfo._
    PublishModuleConfig.PomSettings(
      description = description,
      organization = organizationName,
      url = homepage.fold[String](null)(_.toExternalForm),
      licenses = licenses.map(toLicense),
      versionControl = toVersionControl(scmInfo),
      developers = developers.map(toDeveloper)
    )
  }

  def toLicense(license: (String, URL)) = {
    val (name, url) = license
    PublishModuleConfig.License(name, name, url.toExternalForm)
  }

  def toVersionControl(scmInfo: Option[ScmInfo]) = scmInfo match {
    case Some(scmInfo) =>
      import scmInfo._
      PublishModuleConfig.VersionControl(
        Some(browseUrl.toExternalForm),
        Some(connection),
        devConnection
      )
    case None => PublishModuleConfig.VersionControl()
  }

  def toDeveloper(developer: Developer) = {
    import developer._
    PublishModuleConfig.Developer(id, name, url.toExternalForm)
  }
}
