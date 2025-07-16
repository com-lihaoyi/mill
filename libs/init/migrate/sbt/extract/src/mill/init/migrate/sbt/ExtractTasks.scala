package mill.init.migrate
package sbt

import _root_.sbt._
import _root_.sbt.std.TaskExtra
import mill.init.migrate.ModuleDefaults.scalaVersionTask

import scala.util.Using

object ExtractTasks {

  def extract = Def.taskDyn {
    val out = os.Path(ExtractKeys.millInitExtractArgs.value._1)
    val state = Keys.state.value
    val structure = Project.structure(state)
    def skip(ref: ProjectRef) =
      structure.allProjects.exists(p => p.id == ref.project && p.aggregate.nonEmpty)

    Def.task {
      val modules = TaskExtra.joinTasks(structure.allProjectRefs.flatMap(ref =>
        if (skip(ref)) None
        else (ref / ExtractKeys.millInitMetaModule).get(structure.data)
      )).join.value
      Using.resource(os.write.outputStream(out))(upickle.default.writeToOutputStream(modules, _))
    }
  }

  def module = Def.taskDyn {
    val project = Keys.thisProject.value
    val dependencies = Keys.libraryDependencies.value
    val dependencies0 = dependencies.filterNot(d =>
      d.organization == "org.scala-lang"
        || (d.organization == "org.scala-js" && d.name != "scalajs-dom")
        || d.organization == "org.scala-native"
    )
    def mvnDeps(p: Option[String] => Boolean) = dependencies0.collect {
      case d if p(d.configurations) => dep(d)
    }
    val isCrossVersion = Keys.crossScalaVersions.value.length > 1
    val data = Project.structure(Keys.state.value).data
    val testModuleName = ExtractKeys.millInitExtractArgs.value._2
    def moduleDeps(p: Option[String] => Boolean, test: Boolean = false) =
      project.dependencies.collect {
        case d if p(d.configuration) =>
          var segments = os.Path(
            (d.project / Keys.baseDirectory).get(data).get
          ).subRelativeTo(os.pwd).segments
          val argsByIndex =
            if ((d.project / Keys.crossScalaVersions).get(data).get.length > 1)
              Map((
                segments.length - 1,
                if (isCrossVersion) Nil else Seq(s"$scalaVersionTask()")
              ))
            else Map.empty[Int, Seq[String]]
          segments ++= (if (test) Seq(testModuleName) else Nil)
          ModuleDep(segments, argsByIndex)
      }

    Def.task {
      val coursierModule = CoursierModuleData(
        repositories = Keys.resolvers.value.flatMap(repository)
      )
      val runModule = RunModuleData(
        forkArgs = Keys.javaOptions.value,
        forkEnv = Keys.envVars.value
      )
      val crossPlatformBaseDir = PluginKeys.crossProjectBaseDirectory.?.value.map(os.Path(_))
      val baseDir = os.Path(project.base)
      val moduleDir = crossPlatformBaseDir.getOrElse(baseDir)
      val segmentsRelativeToModuleDir: PartialFunction[File, Seq[String]] = {
        case file if file.exists() => os.Path(file).subRelativeTo(moduleDir).segments
      }
      val javaModule = JavaModuleData(
        mvnDeps = mvnDeps(_.isEmpty),
        compileMvnDeps = mvnDeps(_.contains(Provided.name)),
        runMvnDeps = mvnDeps(_.contains(Runtime.name)),
        javacOptions = Keys.javacOptions.value,
        moduleDeps = moduleDeps(_.forall(_.contains("compile->compile"))),
        compileModuleDeps = moduleDeps(_.contains(Provided.name)),
        runModuleDeps = moduleDeps(_.contains(Runtime.name)),
        sourceFolders = (Compile / Keys.unmanagedSourceDirectories).value
          .collect(segmentsRelativeToModuleDir),
        resources = (Compile / Keys.unmanagedResourceDirectories).value
          .collect(segmentsRelativeToModuleDir)
      )
      val publishModule =
        if ((Keys.publish / Keys.skip).value) None
        else Keys.projectInfo.?.value.map(projectInfo =>
          PublishModuleData(
            pomSettings = Some(pomSettings(projectInfo)),
            versionScheme = Keys.versionScheme.value,
            publishVersion = Some(Keys.version.value)
          )
        )
      val scalaModule =
        ScalaModuleData(
          scalaVersion = if (isCrossVersion) None else Some(Keys.scalaVersion.value),
          scalacOptions = Keys.scalacOptions.value,
          scalaDocOptions = (Compile / Keys.doc / Keys.scalacOptions).?.value.getOrElse(Nil)
        )
      val scalaJSModule = dependencies.collectFirst {
        case d if d.organization == "org.scala-js" && d.name.startsWith("scalajs-library_") =>
          ScalaJSModuleData(
            scalaJSVersion = Some(d.revision)
          )
      }
      val scalaNativeModule = dependencies.collectFirst {
        case d
            if d.organization == "org.scala-native"
              && d.configurations.contains("plugin->default(compile)") =>
          ScalaNativeModuleData(
            scalaNativeVersion = Some(d.revision)
          )
      }

      MetaModule(
        moduleDir = baseDir.subRelativeTo(os.pwd).segments,
        metadata = SbtModuleMetadata(
          crossScalaVersions = Keys.crossScalaVersions.value,
          crossPlatformBaseDir = crossPlatformBaseDir.map(_.subRelativeTo(os.pwd).segments),
          testModule = None // TODO
        ),
        data = ModuleData(
          coursierModule = Some(coursierModule),
          runModule = Some(runModule),
          javaModule = Some(javaModule),
          publishModule = publishModule,
          scalaModule = Some(scalaModule),
          scalaJSModule = scalaJSModule,
          scalaNativeModule = scalaNativeModule
        )
      )
    }
  }

  def dep(moduleID: ModuleID) = {
    import moduleID._
    val attrs = explicitArtifacts.find(_.name == name)
    DepData(
      organization,
      name,
      Option(revision),
      attrs.map(_.`type`),
      attrs.flatMap(_.classifier),
      exclusions.map(x => (x.organization, x.name)),
      crossVersion match {
        case v: CrossVersion.Full => CrossVersionData.Full(v.prefix.nonEmpty)
        case v: CrossVersion.Binary => CrossVersionData.Binary(v.prefix.nonEmpty)
        case v: CrossVersion.Constant => CrossVersionData.Constant(v.value)
        case _ => CrossVersionData.Constant()
      },
      withDottyCompat = crossVersion match {
        case _: librarymanagement.For3Use2_13 => true
        case _ => false
      }
    )
  }

  def license(license: (String, URL)) = {
    val (name, url) = license
    LicenseData(
      id = Option(name),
      name = Option(name),
      url = Option(url).map(_.toExternalForm)
    )
  }

  def versionControl(scmInfo: ScmInfo) = {
    import scmInfo._
    VersionControlData(
      browsableRepository = Some(browseUrl.toExternalForm),
      connection = Some(connection),
      developerConnection = devConnection,
      tag = None
    )
  }

  def developer(developer: Developer) = {
    import developer._
    DeveloperData(
      id = Option(id),
      name = Option(name),
      url = Option(url).map(_.toExternalForm)
    )
  }

  def pomSettings(moduleInfo: ModuleInfo) = {
    import moduleInfo._
    PomSettingsData(
      description = Option(description),
      organization = Option(organizationName),
      url = homepage.map(_.toExternalForm),
      licenses = licenses.map(license),
      versionControl = scmInfo.fold(VersionControlData())(versionControl),
      developers = developers.map(developer)
    )
  }

  // https://github.com/sbt/sbt/blob/2293bddfefe382a48c1672df4e009bd7c5df32f4/main/src/main/scala/sbt/coursierint/CoursierRepositoriesTasks.scala#L31
  def repository(resolver: Resolver) = resolver match {
    case r: MavenRepository => Some(r.root)
    case r: URLRepository =>
      import r.patterns._
      artifactPatterns.headOption.orElse(ivyPatterns.headOption)
    case r =>
      println(s"repository $r could not be extracted, please add it manually")
      None
  }
}
