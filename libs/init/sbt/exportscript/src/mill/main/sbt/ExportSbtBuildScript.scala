package mill.main.sbt

import _root_.sbt._
import mill.main.buildgen._

import scala.util.Using

// https://github.com/JetBrains/sbt-structure/#sideloading
object ExportSbtBuildScript extends (State => State) {

  val millInitExportArgs = settingKey[(File, String)]("")
  val millInitExportModule = taskKey[Unit]("")
  // https://github.com/portable-scala/sbt-crossproject/
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")

  def apply(state: State) = {
    val extracted = Project.extract(state)
    import extracted._
    val sessionSettings = structure.allProjectRefs.flatMap(ref =>
      Project.transform(
        Scope.resolveScope(Scope(Select(ref), Zero, Zero, Zero), ref.build, rootProject),
        Seq(millInitExportModule := exportModule.value)
      )
    )
    BuiltinCommands.reapply(session.appendRaw(sessionSettings), structure, state)
  }

  def exportModule = Def.taskDyn {
    val project = Keys.thisProject.value
    Def.task {
      if (project.aggregate.isEmpty) {
        val (exportDir, testModuleName) = millInitExportArgs.value
        val mvnDependencies = Keys.libraryDependencies.value.collect {
          case dep
              if !((dep.organization == "org.scala-lang" &&
                Seq("scala-library", "scala3-library").exists(dep.name.startsWith)) ||
                (dep.organization == "org.scala-js" && !dep.name.startsWith("scalajs-dom")) ||
                dep.organization == "org.scala-native" ||
                (dep.organization == "ch.epfl.lamp" && dep.name.startsWith("dotty"))) =>
            (dep, dep.configurations.toSeq.flatMap(_.split(';')))
        }
        def mvnDep(dep: ModuleID) = {
          import dep._
          val bin = crossVersion match {
            case _: librarymanagement.Full => ":::"
            case _: librarymanagement.Patch => ":::"
            case _: librarymanagement.Binary => "::"
            case _ => ":"
          }
          val platform = crossVersion match {
            case v: librarymanagement.Full if v.prefix.nonEmpty => "::"
            case v: librarymanagement.Binary if v.prefix.nonEmpty => "::"
            case v: librarymanagement.For2_13Use3 if v.prefix.nonEmpty => "_3::"
            case _: librarymanagement.For2_13Use3 => "_3:"
            case v: librarymanagement.Constant if v.value.nonEmpty => "_" + v.value + ":"
            case _ => ":"
          }
          val artifact = explicitArtifacts.find(_.name == name)
          val classifier = artifact.flatMap(_.classifier).fold("")(";classifier=" + _)
          val typ = artifact.map(_.`type`).filter(_ == "jar").fold("")(";type=" + _)
          val excludes = exclusions.map(x => s";exclude=${x.organization}:${x.name}").mkString
          val suffix = crossVersion match {
            case _: librarymanagement.For3Use2_13 => ".withDottyCompat(scalaVersion())"
            case _ => ""
          }
          s"""mvn"$organization$bin$name$platform$revision$classifier$typ$excludes"$suffix"""
        }
        def mvnDeps(configurationsPredicate: Seq[String] => Boolean) =
          mvnDependencies.collect {
            case (dep, configs) if configurationsPredicate(configs) => mvnDep(dep)
          }

        val isCrossVersion = Keys.crossScalaVersions.value.length > 1
        val data = Project.structure(Keys.state.value).data
        val projectDependencies =
          project.dependencies.map(dep => dep -> dep.configuration.toSeq.flatMap(_.split(";")))

        def moduleDeps(configurationPredicate: Seq[String] => Boolean) =
          projectDependencies.collect {
            case (dep, configs) if configurationPredicate(configs) =>
              for {
                depBaseDir <- (dep.project / Keys.baseDirectory).get(data)
                depSegments = os.Path(depBaseDir).subRelativeTo(os.pwd).segments
                depIsCrossScalaVersion =
                  (dep.project / Keys.crossScalaVersions).get(data).getOrElse(Nil).length > 1
                crossArgs = if (depIsCrossScalaVersion)
                  Map((
                    depSegments.length - 1,
                    if (isCrossVersion) "()" else "(scalaVersion())"
                  ))
                else Map.empty[Int, String]
              } yield JavaModuleConfig.ModuleDep(depSegments, crossArgs)
          }.flatten

        val baseDir = os.Path(project.base)
        // sbt-crossproject
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

        val scalaVersion = Keys.scalaVersion.value
        val mainConfigs = Seq(
          Keys.libraryDependencies.value.collectFirst {
            case dep
                if dep.organization == "org.scala-js" && dep.name.startsWith(
                  "scalajs-library"
                ) => ScalaJSModuleConfig(dep.revision)
          },
          Keys.libraryDependencies.value.collectFirst {
            case dep
                if dep.organization == "org.scala-native" && dep.configurations.contains(
                  "plugin->default(compile)"
                ) => ScalaNativeModuleConfig(dep.revision)
          }
        ).flatten ++ Seq(
          ScalaModuleConfig(
            scalaVersion = if (isCrossVersion) null else scalaVersion,
            scalacOptions = Keys.scalacOptions.value.filterNot(option =>
              option.startsWith("-P") || option.startsWith("-scalajs")
            ),
            scalacPluginMvnDeps = mvnDeps(_.contains("plugin->default(compile)"))
          ),
          JavaModuleConfig(
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
            javacOptions = Keys.javacOptions.value
          ),
          CoursierModuleConfig(
            repositories = Keys.resolvers.value.flatMap {
              case r: MavenRepository if !r.localIfFile => Some(r.root)
              case r: URLRepository =>
                import r.patterns._
                artifactPatterns.headOption.orElse(ivyPatterns.headOption)
              case r =>
                None
            }
          )
        ) ++ Seq(
          if ((Keys.publish / Keys.skip).value || !Keys.publishArtifact.value) None
          else Some(PublishModuleConfig(
            pomSettings = PublishModuleConfig.PomSettings(
              description = Keys.description.value,
              organization = Keys.organization.value,
              url = Keys.homepage.value.fold("")(_.toExternalForm),
              licenses = Keys.licenses.value.map {
                case (name, url) => PublishModuleConfig.License(name, name, url.toExternalForm)
              },
              versionControl = Keys.scmInfo.value.fold(PublishModuleConfig.VersionControl()) {
                scmInfo =>
                  import scmInfo._
                  PublishModuleConfig.VersionControl(
                    Some(browseUrl.toExternalForm),
                    Some(connection),
                    devConnection
                  )
              },
              developers = Keys.developers.value.map { developer =>
                import developer._
                PublishModuleConfig.Developer(id, name, url.toExternalForm)
              }
            ),
            publishVersion = Keys.version.value,
            versionScheme = Keys.versionScheme.value
          ))
        ).flatten
        val useVersionRanges =
          isCrossVersion && os.walk.stream(moduleDir).exists(path =>
            os.isDir(path) && path.last.matches("""^scala-\d+\.\d*(-.*|\+)$""")
          )
        val mainSupertypes =
          supertypes(sbtPlatformSupertype, isCrossVersion, useVersionRanges, mainConfigs)

        val testModule =
          if (
            (Test / Keys.unmanagedSourceDirectories).value.exists(_.exists()) ||
            (Test / Keys.unmanagedResourceDirectories).value.exists(_.exists())
          ) {
            val (mandatoryDependencies, testDependencies) = mvnDependencies.collect {
              case (dep, configs) if configs.contains("test") => dep
            }.partition(testSupertypeByModuleID.isDefinedAt)
            val testFramework = mandatoryDependencies.collect(testSupertypeByModuleID)
            if (testFramework.isEmpty) {
              println(s"skipping test module for ${project.id}")
              None
            } else {
              val testConfigs = Seq(JavaModuleConfig(
                mandatoryMvnDeps = mandatoryDependencies.map(mvnDep),
                mvnDeps = testDependencies.map(mvnDep),
                moduleDeps = moduleDeps(cs => Seq("test", "test->compile").exists(cs.contains)) ++
                  moduleDeps(_.contains("test->test")).map(d =>
                    d.copy(d.segments :+ testModuleName)
                  ),
                compileModuleDeps =
                  moduleDeps(cs => Seq("test->provided", "test->optional").exists(cs.contains)),
                runModuleDeps = moduleDeps(_.contains("test->runtime"))
              ))
              Some(TestModuleRepr(
                name = testModuleName,
                supertypes = testSupertypes(
                  mainSupertypes,
                  testFramework
                ),
                configs = testConfigs,
                crossConfigs = if (isCrossVersion)
                  Seq((scalaVersion, testConfigs.collect(crossConfigSelector)))
                else Nil
              ))
            }
          } else None

        val sbtModule = (
          (moduleDir.subRelativeTo(os.pwd).segments, isCrossPlatform),
          ModuleRepr(
            segments = baseDir.subRelativeTo(os.pwd).segments,
            supertypes = mainSupertypes,
            configs = mainConfigs,
            crossConfigs = if (isCrossVersion)
              Seq((scalaVersion, mainConfigs.collect(crossConfigSelector)))
            else Nil,
            testModule = testModule
          )
        )

        Using.resource(os.write.outputStream(os.temp(
          dir = os.Path(exportDir),
          deleteOnExit = false
        )))(upickle.default.writeToOutputStream(sbtModule, _))
      }
    }
  }

  def supertypes(
      sbtPlatformSupertype: Option[String],
      isCrossVersion: Boolean,
      useVersionRanges: Boolean,
      configs: Seq[ModuleConfig]
  ) = configs.collect {
    case _: ScalaJSModuleConfig => "ScalaJSModule"
    case _: ScalaNativeModuleConfig => "ScalaNativeModule"
  } ++
    (if (configs.exists(_.isInstanceOf[PublishModuleConfig])) Seq("PublishModule") else Nil) ++
    ((sbtPlatformSupertype, isCrossVersion, useVersionRanges) match {
      case (Some(s), true, true) => Seq(s, "CrossSbtPlatformModule", "CrossScalaVersionRanges")
      case (Some(s), true, false) => Seq(s, "CrossSbtPlatformModule")
      case (Some(s), _, _) => Seq(s)
      case (_, true, true) => Seq("CrossSbtModule", "CrossScalaVersionRanges")
      case (_, true, false) => Seq("CrossSbtModule")
      case _ => Seq("SbtModule")
    })

  def testSupertypes(mainSupertypes: Seq[String], frameworkSupertype: Seq[String]) =
    mainSupertypes.collectFirst {
      case "ScalaJSModule" => "ScalaJSTests"
      case "ScalaNativeModule" => "ScalaNativeTests"
    }.toSeq ++ Seq(mainSupertypes.collectFirst {
      case "SbtModule" => "SbtTests"
      case "CrossSbtModule" => "CrossSbtTests"
      case "CrossSbtPlatformModule" => "CrossSbtPlatformTests"
    }.getOrElse("SbtPlatformTests")) ++ (
      if (Seq("TestModule.ScalaTest", "TestModule.ScalaCheck").forall(frameworkSupertype.contains))
        Seq("TestModule.ScalaTest")
      else frameworkSupertype.take(1)
    )

  val crossConfigSelector: PartialFunction[ModuleConfig, ModuleConfig] = {
    case config: JavaModuleConfig => config
    case config: ScalaModuleConfig => config
  }

  val testSupertypeByModuleID: PartialFunction[ModuleID, String] = {
    case dep if TestModuleRepr.supertypeByDep.isDefinedAt((dep.organization, dep.name)) =>
      TestModuleRepr.supertypeByDep((dep.organization, dep.name))
  }
  val testSettingsByModuleID: PartialFunction[ModuleID, (String, String)] = {
    case dep
        if TestModuleRepr.settingsByDep.isDefinedAt((
          dep.organization,
          dep.name,
          dep.revision
        )) =>
      TestModuleRepr.settingsByDep((dep.organization, dep.name, dep.revision))
  }
}
