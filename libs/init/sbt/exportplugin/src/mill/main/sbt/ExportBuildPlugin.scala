package mill.main.sbt

import _root_.sbt._
import mill.main.buildgen.ModuleConfig._
import mill.main.buildgen._

import scala.util.Using

object ExportBuildPlugin extends AutoPlugin {
  object autoImport {
    val millInitExportBuild = taskKey[Unit]("exports data for all projects")
  }
  import autoImport._

  val millInitExportDir = settingKey[os.Path]("directory to export data to")
  val millInitExportProject = taskKey[Unit]("exports data for a project")

  // settings derived from third-party plugin settings
  val millScalaJSModuleKind = settingKey[Option[String]]("")

  // duplicated third-party plugin settings
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")

  override def requires = plugins.JvmPlugin
  override def trigger = allRequirements
  override def globalSettings = Seq(
    millInitExportBuild := exportBuild.value,
    millInitExportDir := os.Path(System.getProperty("millInitExportDir"))
  )
  override def projectSettings = Seq(
    millScalaJSModuleKind := scalaJSModuleKind.value,
    millInitExportProject := exportProject.value
  )

  // https://github.com/scalacenter/bloop/blob/f6dff064ed96698c6d35daef43fe06e6cca74526/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L327
  private def scalaJSModuleKind = Def.settingDyn {
    try {
      val class0 = Class.forName("org.scalajs.linker.interface.StandardConfig")
      val method = class0.getMethod("moduleKind")
      Def.setting {
        proxyForSetting("scalaJSLinkerConfig", class0).value.map(method.invoke(_).toString)
      }
    } catch {
      case _: ClassNotFoundException => Def.setting(None)
      case _: NoSuchMethodException => Def.setting(None)
    }
  }

  // https://github.com/scalacenter/bloop/blob/f6dff064ed96698c6d35daef43fe06e6cca74526/integrations/sbt-bloop/src/main/scala/bloop/integrations/sbt/SbtBloop.scala#L305
  private def proxyForSetting(id: String, rt: Class[_]) = {
    val manifest = new Manifest[AnyRef] { def runtimeClass = rt }
    val anyRefWriter = implicitly[util.OptJsonWriter[AnyRef]]
    SettingKey(id)(manifest, anyRefWriter).?
  }

  // this is required to export projects that are not aggregated by the root project
  private def exportBuild = Def.taskDyn {
    val structure = Project.structure(Keys.state.value)
    Def.task {
      val _ = std.TaskExtra.joinTasks(structure.allProjectPairs.flatMap {
        case (project, ref) =>
          if (project.aggregate.isEmpty) (ref / millInitExportProject).get(structure.data)
          else None
      }).join.value
    }
  }

  private def exportProject = Def.taskDyn {
    val project = Keys.thisProject.value
    val scalaVersion = Keys.scalaVersion.value
    val outDir = millInitExportDir.value
    val outFile = outDir / s"${project.id}-$scalaVersion.json"
    // ignore duplicate invocations triggered by cross-build execution
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
              depIsCrossVersion = depCrossScalaVersions.length > 1
              if !depIsCrossVersion || depCrossScalaVersions.contains(scalaVersion)
              crossArgs = if (depIsCrossVersion)
                Map((depSegments.length - 1, if (isCrossVersion) Nil else Seq("scalaVersion()")))
              else Map.empty[Int, Seq[String]]
            } yield ModuleDep(depSegments, crossArgs)
        }.flatten

      val baseDir = os.Path(project.base)
      val moduleDir = crossProjectBaseDirectory.?.value.fold(
        // crossProjectBaseDirectory was added in v1.3.0
        if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) baseDir / os.up else baseDir
      )(os.Path(_))
      val isCrossPlatform = baseDir != moduleDir

      def hasAutoPlugin(label: String) = project.autoPlugins.exists(_.label == label)

      val mainCoursierModule = Keys.resolvers.value
        .diff(Seq(Resolver.mavenCentral, Resolver.mavenLocal))
        .collect {
          case r: MavenRepository => r.root
        } match {
        case Nil => None
        case repositories => Some(CoursierModule(repositories))
      }
      val mainJavaHomeModule = JavaHomeModule.find(
        javacOptions = Keys.javacOptions.value,
        scalacOptions = Keys.scalacOptions.value
      )
      val mainJavaModule = JavaModule(
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
        javacOptions = Keys.javacOptions.value,
        artifactName = Keys.moduleName.value
      )
      val mainPublishModule = if ((Keys.publish / Keys.skip).value || !Keys.publishArtifact.value)
        None
      else {
        Some(PublishModule(
          pomSettings = toPomSettings(Keys.projectInfo.value, Keys.organization.value),
          publishVersion = Keys.version.value,
          versionScheme = Keys.versionScheme.value.orNull
        ))
      }
      val mainScalaModule = ScalaModule(
        scalaVersion = if (isCrossVersion) null else scalaVersion,
        scalacOptions = Keys.scalacOptions.value.filterNot(skipScalacOption),
        scalacPluginMvnDeps = mvnDeps(_.contains("plugin->default(compile)"))
      )
      val mainScalaJSModule = if (hasAutoPlugin("org.scalajs.sbtplugin.ScalaJSPlugin")) {
        Keys.libraryDependencies.value.collectFirst {
          case dep
              if dep.organization == "org.scala-js" && dep.name.startsWith("scalajs-library") =>
            ScalaJSModule(
              scalaJSVersion = dep.revision,
              moduleKind = ScalaJSModule.moduleKindOverride(scalaJSModuleKind.value.orNull)
            )
        }
      } else None
      val mainScalaNativeModule =
        if (hasAutoPlugin("scala.scalanative.sbtplugin.ScalaNativePlugin")) {
          Keys.libraryDependencies.value.collectFirst {
            case dep
                if dep.organization == "org.scala-native" &&
                  dep.configurations.contains("plugin->default(compile)") =>
              ScalaNativeModule(dep.revision)
          }
        } else None
      val mainSbtPlatformModule = if (isCrossPlatform) {
        Some(SbtPlatformModule(
          // only sbtcrossproject.CrossType.Full requires additional configuration
          sourcesRootFolders = if (os.isDir(moduleDir / "shared")) Seq("shared") else Nil
        ))
      } else None
      val mainConfigs = Seq(
        mainScalaJSModule,
        mainScalaNativeModule
      ).flatten ++ Seq(
        mainJavaModule,
        mainScalaModule
      ) ++ Seq(
        mainJavaHomeModule,
        mainSbtPlatformModule,
        mainPublishModule,
        mainCoursierModule
      ).flatten
      val useVersionRanges = isCrossVersion && os.walk.stream(moduleDir).exists(path =>
        os.isDir(path) && path.last.matches("""^scala-\d+\.\d*(-.*|\+)$""")
      )
      val (mainSupertypes, mainMixins) =
        mainHierarchy(isCrossPlatform, isCrossVersion, useVersionRanges, mainConfigs)

      val testModule = TestModule.mixin(mvnDeps(_.contains("test"))).map { testModuleMixin =>
        val (testSupertypes, testMixins) =
          testHierarchy(mainSupertypes ++ mainMixins, testModuleMixin)
        // provided dependencies are included in run scope to reproduce SBT behavior
        val testJavaModule = JavaModule(
          mvnDeps = mvnDeps(_.contains("test")),
          compileMvnDeps = mvnDeps(_.contains("provided")),
          runMvnDeps = mvnDeps(_.contains("provided")),
          moduleDeps = moduleDeps(_.exists(c => c == "test" || c == "test->compile")) ++
            moduleDeps(_.contains("test->test")).map(dep => dep.copy(dep.segments :+ "test")),
          compileModuleDeps =
            moduleDeps(_.exists(c => c == "provided" || c == "test->provided")),
          runModuleDeps = moduleDeps(_.exists(c => c == "provided" || c == "test->runtime"))
        )
        val testScalaJSModule = if (mainScalaJSModule.isEmpty) None else Some(ScalaJSModule())
        val testScalaNativeModule =
          if (mainScalaJSModule.isEmpty) None else Some(ScalaNativeModule())
        val testConfigs = Seq(
          testJavaModule,
          // reproduce SBT behavior
          TestModule(
            testParallelism = "false",
            testSandboxWorkingDir = "false"
          )
        ) ++ Seq(
          testScalaJSModule,
          testScalaNativeModule
        ).flatten
        ModuleSpec(
          name = "test",
          supertypes = testSupertypes,
          mixins = testMixins,
          configs = testConfigs,
          crossConfigs = if (isCrossVersion) Seq((scalaVersion, testConfigs)) else Nil
        )
      }

      val mainModule = ModuleSpec(
        name = toSegment(baseDir.last),
        supertypes = mainSupertypes,
        mixins = mainMixins,
        configs = mainConfigs,
        crossConfigs = if (isCrossVersion) Seq((scalaVersion, mainConfigs)) else Nil,
        nestedModules = testModule.toSeq
      )

      val segments = toSegments(moduleDir)
      val moduleType =
        if (isCrossPlatform) SbtModuleType.Platform(segments) else SbtModuleType.Default(segments)
      val exportedData = SbtModuleSpec(moduleType, mainModule)
      Using.resource(os.write.outputStream(outFile))(
        upickle.default.writeToOutputStream(exportedData, _)
      )
    })
  }

  private def mainHierarchy(
      isCrossPlatform: Boolean,
      isCrossVersion: Boolean,
      useVersionRanges: Boolean,
      configs: Seq[ModuleConfig]
  ) = {
    val supertypes = Seq.newBuilder[String]
    val mixins = Seq.newBuilder[String]
    supertypes ++= configs.collect {
      case _: ScalaJSModule => "ScalaJSModule"
      case _: ScalaNativeModule => "ScalaNativeModule"
      case _: PublishModule => "PublishModule"
      case _: SbtPlatformModule => "SbtPlatformModule"
    }
    if (isCrossVersion) {
      mixins += (if (isCrossPlatform) "CrossSbtPlatformModule" else "CrossSbtModule")
      if (useVersionRanges) mixins += "CrossScalaVersionRanges"
    } else {
      supertypes += "SbtModule"
    }
    (supertypes.result(), mixins.result())
  }

  private def testHierarchy(mainHierarchy: Seq[String], testModuleMixin: String) = {
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

  private def skipDep(dep: ModuleID) = {
    import dep._
    (organization == "org.scala-lang" && (
      name.startsWith("scala-library") ||
        name.startsWith("scala3-library")
    )) ||
    (organization == "ch.epfl.lamp" && name.startsWith("dotty")) ||
    (organization == "org.scala-js" && !name.startsWith("scalajs-dom")) ||
    organization == "org.scala-native"
  }

  private def skipScalacOption(s: String) =
    s.startsWith("-P") || s.startsWith("-Xplugin") || s.startsWith("-scalajs")

  private def toSegment(s: String) =
    // cleanup `CrossType.Pure` module names
    s.stripPrefix(".")

  private def toSegments(baseDir: os.Path) =
    baseDir.relativeTo(os.pwd).segments.map(toSegment)

  private def toMvnDep(dep: ModuleID) = {
    import ModuleConfig.{CrossVersion => CV}
    import dep._
    import librarymanagement.{For2_13Use3, For3Use2_13, Patch}
    val artifact = explicitArtifacts.find(_.name == name)
    MvnDep(
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

  private def toPomSettings(moduleInfo: ModuleInfo, groupId: String) = {
    import moduleInfo._
    PomSettings(
      description = Option(description),
      organization = Option(groupId),
      url = homepage.map(_.toExternalForm),
      licenses = licenses.map(toLicense),
      versionControl = toVersionControl(scmInfo),
      developers = developers.map(toDeveloper)
    )
  }

  private def toLicense(license: (String, URL)) = {
    val (name, url) = license
    ModuleConfig.License(
      name = name,
      url = url.toExternalForm,
      distribution = "repo" // sbt hardcodes "repo"
    )
  }

  private def toVersionControl(scmInfo: Option[ScmInfo]) =
    scmInfo.fold(VersionControl()) { scmInfo =>
      import scmInfo._
      VersionControl(
        Some(browseUrl.toExternalForm),
        Some(connection),
        devConnection
      )
    }

  private def toDeveloper(developer: librarymanagement.Developer) = {
    import developer._
    ModuleConfig.Developer(id, name, url.toExternalForm)
  }
}
