package mill.init.importer
package sbt

import _root_.sbt._
import _root_.sbt.std.TaskExtra
import mill.init.importer.{
  CoursierModuleIR,
  CrossVersionIR,
  DepIR,
  DeveloperIR,
  JavaModuleIR,
  LicenseIR,
  PomSettingsIR,
  PublishModuleIR,
  RunModuleIR,
  ScalaJSModuleIR,
  ScalaModuleIR,
  ScalaNativeModuleIR,
  VersionControlIR
}

import scala.util.Using

object ExtractTasks {

  def millInitExtract = Def.taskDyn {
    val state = Keys.state.value
    val build = Project.structure(state)
    def skip(ref: ProjectRef) =
      build.allProjects.exists(p => p.id == ref.project && p.aggregate.nonEmpty)
    Def.task {
      val file = os.Path(ExtractKeys.millInitExtractFile.value)
      val exportedProjects = TaskExtra.joinTasks(build.allProjectRefs.flatMap(ref =>
        if (skip(ref)) None else (ref / ExtractKeys.millInitSbtProjectIR).get(build.data)
      )).join.value
      Using(os.write.outputStream(file))(upickle.default.writeToOutputStream(exportedProjects, _))
        .get
    }
  }

  def millInitSbtProjectIR = Def.task {
    val project = Keys.thisProject.value
    val state = Keys.state.value
    val structure = Project.structure(state)
    val moduleDirs = structure.allProjects.iterator
      .map(project => project.id -> subPwd(project.base))
      .toMap
    def moduleDeps(p: Option[String] => Boolean) = project.dependencies.collect {
      case pr if p(pr.configuration) => moduleDirs(pr.project.project)
    }

    val dependencies = Keys.libraryDependencies.value
    val dependencies0 = dependencies.filterNot(d =>
      d.organization == "org.scala-lang"
        || (d.organization == "org.scala-js" && d.name != "scalajs-dom")
        || d.organization == "org.scala-native"
    )
    def mvnDeps(p: Option[String] => Boolean) = dependencies0.collect {
      case d if p(d.configurations) => depIR(d)
    }

    val coursierModuleIR = CoursierModuleIR(
      repositories = Keys.resolvers.value.flatMap(repository)
    )
    val runModuleIR = RunModuleIR(
      forkArgs = (Keys.javaOptions).value,
      forkEnv = (Keys.envVars).value
    )
    val javaModuleIR = {
      JavaModuleIR(
        mvnDeps = mvnDeps(_.isEmpty),
        compileMvnDeps = mvnDeps(_.contains(Provided.name)),
        runMvnDeps = mvnDeps(_.contains(Runtime.name)),
        javacOptions = Keys.javacOptions.value,
        moduleDeps = moduleDeps(_.isEmpty),
        compileModuleDeps = moduleDeps(_.contains(Provided.name)),
        runModuleDeps = moduleDeps(_.contains(Runtime.name)),
        // TODO bomModuleDeps = ???,
        sources = (Compile / Keys.unmanagedSourceDirectories).value.map(subPwd),
        resources = (Compile / Keys.unmanagedResourceDirectories).value.map(subPwd),
        docResources = (Compile / Keys.doc / Keys.unmanagedResourceDirectories).value.map(subPwd)
      )
    }
    val publishModuleIR =
      if ((Keys.publish / Keys.skip).value) None
      else Keys.projectInfo.?.value.map { projectInfo =>
        import projectInfo._
        val irPomSettings = {
          def irLicense(l: (String, URL)) = {
            val (name, url) = l
            LicenseIR(
              id = name,
              name = name,
              url = url.toExternalForm
            )
          }
          val irVersionControl =
            scmInfo.fold(VersionControlIR()) { scmInfo =>
              import scmInfo._
              VersionControlIR(
                browsableRepository = Some(browseUrl.toExternalForm),
                connection = Some(connection),
                developerConnection = devConnection,
                tag = None
              )
            }
          def irDeveloper(developer: Developer) = {
            import developer._
            DeveloperIR(
              id = id, // TODO
              name = name,
              url = url.toExternalForm
            )
          }
          PomSettingsIR(
            description = Option(description),
            organization = Option(organizationName),
            url = homepage.map(_.toExternalForm),
            licenses = licenses.map(irLicense),
            versionControl = irVersionControl,
            developers = developers.map(irDeveloper)
          )
        }
        PublishModuleIR(
          publishVersion = Keys.version.value,
          pomSettings = irPomSettings,
          pomPackagingType = None, // TODO sbt-bom?
          pomParentProject = None, // TODO Keys.pomExtra?
          versionScheme = Keys.versionScheme.value,
          publishProperties = Map() // TODO Keys.pomExtra?
        )
      }
    val scalaModuleIR =
      ScalaModuleIR( // TODO
        scalaVersion = Keys.scalaVersion.value,
        scalacOptions = Keys.scalacOptions.value
      )
    val scalaJSModuleIR = dependencies.collectFirst {
      case d if d.organization == "org.scala-js" && d.name.startsWith("scalajs-library_") =>
        ScalaJSModuleIR( // TODO
          scalaJSVersion = d.revision
        )
    }
    val scalaNativeModuleIR = dependencies.collectFirst {
      case d
          if d.organization == "org.scala-native"
            && d.configurations.contains("plugin->default(compile)") =>
        ScalaNativeModuleIR( // TODO
          scalaNativeVersion = d.revision
        )
    }

    SbtProjectIR(
      projectId = project.id,
      moduleName = Keys.moduleName.value,
      baseDir = subPwd(project.base),
      crossPlatformBaseDir = crossProjectBaseDirectory.?.value.map(subPwd),
      crossScalaVersions = Keys.crossScalaVersions.value,
      mainModuleIRs =
        Seq(coursierModuleIR, runModuleIR, javaModuleIR, scalaModuleIR)
          ++ publishModuleIR
          ++ scalaJSModuleIR
          ++ scalaNativeModuleIR,
      testModuleIRs = Nil // TODO
    )
  }

  def repository(resolver: Resolver) = {
    resolver match {
      case r: MavenRepository => Some(r.root)
      case _ => None // TODO
    }
  }

  def crossVersionIR(crossVersion: CrossVersion) = {
    import _root_.sbt.librarymanagement.{Constant, For2_13Use3, For3Use2_13}
    crossVersion match {
      case v: Binary => CrossVersionIR.Binary(platformed = v.prefix.nonEmpty)
      case v: Full => CrossVersionIR.Full(platformed = v.prefix.nonEmpty)
      case v: For3Use2_13 => CrossVersionIR.Constant("2.13", platformed = v.prefix.nonEmpty)
      case v: For2_13Use3 => CrossVersionIR.Constant("3", platformed = v.prefix.nonEmpty)
      case v: Constant => CrossVersionIR.Constant(v.value)
      case _ => CrossVersionIR.Constant()
    }
  }

  def depIR(moduleID: ModuleID) = {
    import moduleID._
    val artifact = explicitArtifacts.find(_.name == name)
    DepIR(
      organization = organization,
      name = name,
      version = Option(revision),
      crossVersion = crossVersionIR(crossVersion),
      `type` = artifact.map(_.`type`),
      classifier = artifact.flatMap(_.classifier),
      exclusions = exclusions.map(rule => rule.organization -> rule.name)
    )
  }

  def subPwd(file: File) = os.Path(file).subRelativeTo(os.pwd)

  // https://github.com/portable-scala/sbt-crossproject/blob/7fbbf6be90e012b6f765647b113846b845719218/sbt-crossproject/src/main/scala/sbtcrossproject/CrossPlugin.scala#L68
  val crossProjectBaseDirectory = settingKey[File]("base directory of the current cross project")
}
