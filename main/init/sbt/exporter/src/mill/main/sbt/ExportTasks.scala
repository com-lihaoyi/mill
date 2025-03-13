package mill.main.sbt

import mill.main.buildgen._
import sbt.{Def, _}

object ExportTasks {

  def millInitExport = Def.taskDyn {
    val file = os.Path(ExportKeys.millInitExportFile.value)
    require(os.exists(file), "millInitExportFile must be defined before calling millInitExport")

    val state = Keys.state.value
    val build = Project.structure(state)
    def skip(ref: ProjectRef) =
      build.allProjects.exists(p => p.id == ref.project && p.aggregate.nonEmpty)
    Def.task {
      val models = sbt.std.TaskExtra.joinTasks(
        build.allProjectRefs.flatMap(ref =>
          if (skip(ref)) None else (ref / ExportKeys.millInitProjectModel).get(build.data)
        )
      ).join.value

      val out = os.write.outputStream(file)
      try upickle.default.writeToOutputStream(models, out)
      finally out.close()
    }
  }

  def millInitProjectModel = Def.taskDyn {
    val project = Keys.thisProject.value
    val basePath = os.Path(project.base)

    val state = Keys.state.value
    val allProjectBaseDirs = Project.structure(state).allProjects.iterator
      .map(p => p.id -> subPwd(p.base))
      .toMap

    val libDeps = Keys.libraryDependencies.value
    val scalaJSConfig = libDeps.collectFirst {
      case dep if dep.organization == "org.scala-js" && dep.name.startsWith("scalajs-library_") =>
        IrScalaJSModuleConfig(dep.revision)
    }
    val scalaNativeConfig = libDeps.collectFirst {
      case dep
          if dep.organization == "org.scala-native" &&
            dep.configurations.contains("plugin->default(compile)") =>
        IrScalaNativeModuleConfig(dep.revision)
    }

    def relBase(files: Seq[File]) = files.iterator
      .map(os.Path(_))
      .filter(os.exists)
      .map(_.relativeTo(basePath))
      .toSeq

    val platformed = scalaJSConfig.nonEmpty || scalaNativeConfig.nonEmpty
    val ivyDeps = new IrCompat.ToDeps(libDeps.filterNot(IrCompat.skipDep), platformed)
    val moduleDeps = new ToModuleDeps(project.dependencies, allProjectBaseDirs)
    Def.task {
      val coursierConfig = IrCoursierModuleConfig(
        Keys.resolvers.value.collect(IrCompat.repository)
      )

      val javaConfig = IrJavaModuleConfig(
        javacOptions = Keys.javacOptions.value,
        sources = relBase((Compile / Keys.unmanagedSourceDirectories).value),
        resources = relBase((Compile / Keys.unmanagedResourceDirectories).value),
        ivyDeps = ivyDeps(None), // sbt.Compile is not marked explicitly
        moduleDeps = moduleDeps(Compile),
        compileIvyDeps = ivyDeps(Some(Provided.name)),
        runIvyDeps = ivyDeps(Some(Runtime.name))
      )
      val scalaVersion = Keys.scalaVersion.value
      val scalaConfig = IrScalaModuleConfig(
        scalaVersion = scalaVersion,
        scalacOptions = Keys.scalacOptions.value
      )
      val publishConfig =
        if ((Keys.publish / Keys.skip).value) None
        else Keys.projectInfo.?.value
          .map(IrCompat.toPomSettings)
          .map(IrPublishModuleConfig(_, Keys.version.value))

      val testIvyDeps = ivyDeps(Some(Test.name))
      val testJavaConfig =
        if (testIvyDeps.isEmpty) None
        else Some(IrJavaModuleConfig(
          sources = relBase((Test / Keys.unmanagedSourceDirectories).value),
          resources = relBase((Test / Keys.unmanagedResourceDirectories).value),
          ivyDeps = testIvyDeps
        ))

      SbtProjectModel(
        projectId = project.id,
        moduleName = Keys.moduleName.value,
        baseDir = subPwd(project.base),
        crossVersions = IrCompat.crossVersions(scalaVersion, Keys.crossScalaVersions.value),
        crossPlatformBaseDir = ThirdPartyKeys.crossProjectBaseDirectory.?.value.map(subPwd),
        coursierConfig = coursierConfig,
        javaConfig = javaConfig,
        publishConfig = publishConfig,
        scalaConfig = scalaConfig,
        scalaJSConfig = scalaJSConfig,
        scalaNativeConfig = scalaNativeConfig,
        testJavaConfig = testJavaConfig
      )
    }
  }

  def subPwd(f: File) = os.Path(f).subRelativeTo(os.pwd)

  class ToModuleDeps(
      projectDependencies: Seq[ClasspathDep[ProjectRef]],
      allProjectBaseDirs: Map[String, os.SubPath]
  ) {

    def apply(configuration: Configuration) = projectDependencies.iterator
      .filter(_.configuration.forall(_.contains(configuration.name)))
      .map(_.project.project)
      .map(allProjectBaseDirs)
      .toSeq
  }
}
