package mill.init.migrate.sbt

import _root_.sbt._
import _root_.sbt.std.TaskExtra
import mill.init.migrate._

import scala.util.Using

// https://github.com/JetBrains/sbt-structure/#sideloading
object ExportSbtBuildScript extends (State => State) {

  val millInitExportArgs = settingKey[(File, String)]("")
  val millInitExport = taskKey[Unit]("")
  val millInitModule = taskKey[SbtModule]("")
  // https://github.com/portable-scala/sbt-crossproject/
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")

  def apply(state: State) = {
    val extracted = Project.extract(state)
    import extracted._
    val sessionSettings = inScope(GlobalScope)(millInitExport := exportModules.value) ++
      structure.allProjectRefs.flatMap(ref =>
        Project.transform(
          Scope.resolveScope(Scope(Select(ref), Zero, Zero, Zero), ref.build, rootProject),
          Seq(millInitModule := module.value)
        )
      )
    BuiltinCommands.reapply(session.appendRaw(sessionSettings), structure, state)
  }

  def exportModules = Def.taskDyn {
    val out = os.Path(millInitExportArgs.value._1)
    val state = Keys.state.value
    val structure = Project.structure(state)
    def skip(ref: ProjectRef) =
      structure.allProjects.exists(p => p.id == ref.project && p.aggregate.nonEmpty)

    Def.task {
      val modules = TaskExtra.joinTasks(structure.allProjectRefs.flatMap(ref =>
        if (skip(ref)) None
        else (ref / millInitModule).get(structure.data)
      )).join.value
      Using.resource(os.write.outputStream(out))(upickle.default.writeToOutputStream(modules, _))
    }
  }

  def module = Def.taskDyn {
    val testModuleName = millInitExportArgs.value._2
    val project = Keys.thisProject.value
    val mvnDependencies = Keys.libraryDependencies.value.filterNot(dep =>
      (dep.organization == "org.scala-lang" &&
        Seq("scala-library", "scala3-library").exists(dep.name.startsWith)) ||
        (dep.organization == "org.scala-js" && !dep.name.startsWith("scalajs-dom")) ||
        dep.organization == "org.scala-native" ||
        (dep.organization == "ch.epfl.lamp" && dep.name.startsWith("dotty"))
    )
    def mvnDeps(configurationsPredicate: Option[String] => Boolean) =
      mvnDependencies.collect {
        case dep if configurationsPredicate(dep.configurations) =>
          import dep._
          val bin = crossVersion match {
            case _: librarymanagement.Full => ":::"
            case _: librarymanagement.Binary => "::"
            case _ => ":"
          }
          val platform = crossVersion match {
            case v: librarymanagement.Full if v.prefix.nonEmpty => "::"
            case v: librarymanagement.Binary if v.prefix.nonEmpty => "::"
            case v: librarymanagement.For2_13Use3 if v.prefix.nonEmpty => "_3" + "::"
            case _: librarymanagement.For2_13Use3 => "_3:"
            case v: librarymanagement.For3Use2_13 if v.prefix.nonEmpty => "_2.13" + "::"
            case _: librarymanagement.For3Use2_13 => "_2.13:"
            case v: librarymanagement.Constant if v.value.nonEmpty => "_" + v.value + ":"
            case _ => ":"
          }
          val artifact = explicitArtifacts.find(_.name == name)
          val classifier = artifact.flatMap(_.classifier).fold("")(";classifier=" + _)
          val typ = artifact.map(_.`type`).filter(_ == "jar").fold("")(";type=" + _)
          val excludes = exclusions.map(x => s";exclude=${x.organization}:${x.name}").mkString
          s"$organization$bin$name$platform$revision$classifier$typ$excludes"
      }

    val isCrossScalaVersion = Keys.crossScalaVersions.value.length > 1
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
              Map((depSegments.length - 1, if (isCrossScalaVersion) "()" else "(scalaVersion())"))
            else Map.empty[Int, String]
          } yield JavaModuleConfig.ModuleDep(depSegments, crossArgs)
      }.flatten

    val baseDir = os.Path(project.base)
    // sbt-crossproject
    val crossPlatformBaseDir = crossProjectBaseDirectory.?.value.map(os.Path(_))
      .orElse( // crossProjectBaseDirectory was added in v1.3.0
        if (baseDir.last.matches("""^[.]?(js|jvm|native)$""")) Some(baseDir / os.up) else None)
    val moduleDir = crossPlatformBaseDir.getOrElse(baseDir)
    val mandatoryScalacOptions = Seq("-scalajs")

    Def.task {
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
          scalaVersion = if (isCrossScalaVersion) "" else Keys.scalaVersion.value,
          scalacOptions =
            Keys.scalacOptions.value.filterNot(_.startsWith("-P")).diff(mandatoryScalacOptions),
          scalacPluginMvnDeps = mvnDeps(_.contains("plugin->default(compile)"))
        ),
        JavaModuleConfig(
          mvnDeps = mvnDeps(_.forall(_ == "compile")),
          compileMvnDeps = mvnDeps(_.exists(Seq("provided", "optional").contains)),
          runMvnDeps = mvnDeps(_.contains("runtime")),
          moduleDeps = moduleDeps(cs =>
            cs.isEmpty || cs.contains("compile") || cs.exists(_.startsWith("compile->"))
          ),
          compileModuleDeps = moduleDeps(cs =>
            Seq("provided", "optional").exists(cs.contains) || Seq(
              "provided->",
              "optional->"
            ).exists(s => cs.exists(_.startsWith(s)))
          ),
          runModuleDeps =
            moduleDeps(cs => cs.contains("runtime") || cs.exists(_.startsWith("runtime->"))),
          javacOptions = Keys.javacOptions.value
        ),
        CoursierModuleConfig(
          repositories = Keys.resolvers.value.flatMap {
            case r: MavenRepository => Some(r.root)
            case r: URLRepository =>
              import r.patterns._
              artifactPatterns.headOption.orElse(ivyPatterns.headOption)
            case r =>
              println(s"skipping repository $r")
              None
          }
        )
      ) ++ Seq(
        if ((Keys.publish / Keys.skip).value) None
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
      val testConfigs =
        if (
          (Test / Keys.unmanagedSourceDirectories).value.exists(_.exists()) ||
          (Test / Keys.unmanagedResourceDirectories).value.exists(_.exists())
        )
          Seq(JavaModuleConfig(
            mvnDeps = mvnDeps(_.contains("test")),
            moduleDeps = moduleDeps(cs => Seq("test", "test->compile").exists(cs.contains)) ++
              moduleDeps(_.contains("test->test")).map(dep =>
                dep.copy(dep.segments :+ testModuleName)
              ),
            compileModuleDeps =
              moduleDeps(cs => Seq("test->provided", "test->optional").exists(cs.contains)),
            runModuleDeps = moduleDeps(_.contains("test->runtime"))
          ))
        else Nil

      SbtModule(
        moduleDir = moduleDir.subRelativeTo(os.pwd).segments,
        baseDir = baseDir.subRelativeTo(os.pwd).segments,
        platformCrossType = crossPlatformBaseDir.map { dir =>
          if (os.exists(dir / "shared")) "Full"
          else if (os.exists(dir / "src")) "Pure"
          else "Dummy"
        },
        crossScalaVersions = Keys.crossScalaVersions.value,
        useVersionRanges =
          isCrossScalaVersion && os.walk.stream(moduleDir).exists(path =>
            os.isDir(path) && path.last.matches("""^scala-\d+\.\d*(-.*|\+)$""")
          ),
        mainConfigs = mainConfigs,
        testModuleName = testModuleName,
        testModuleBase = mvnDependencies.iterator.map(
          _.organization
        ).collectFirst(ModuleTypedef.testSupertypeByDepOrg),
        testConfigs = testConfigs
      )
    }
  }
}
