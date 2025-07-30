package mill.eclipse

import mill.api.daemon.internal.eclipse.ResolvedModule
import mill.api.daemon.{Segment, Segments}
import mill.api.daemon.internal.{
  EvaluatorApi,
  ExecutionResultsApi,
  JavaModuleApi,
  ModuleApi,
  TaskApi,
  TestModuleApi
}

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable

class GenEclipseImpl(private val evaluators: Seq[EvaluatorApi]) {
  import GenEclipseImpl._

  /** Used to have distinct log messages for this generator */
  private def log(message: String): Unit = println(s"[Eclipse JDT Project generator] $message")

  /**
   *  This aggregates the Java Modules with their direct children (Test Modules only) that will be
   *  one Eclipse JDT Project in the end
   *
   *  @param evaluator to be used to iterate over its Mill Modules
   *  @return a map of aggregated Java Module DTO objects by their path
   */
  private def getAggregatedJavaModules(evaluator: EvaluatorApi): Map[Path, JavaModuleDto] = {
    val aggregatedJavaModules = mutable.Map.empty[Path, JavaModuleDto]

    val rootModule = evaluator.rootModule
    rootModule match {
      case api: JavaModuleApi =>
        aggregatedJavaModules +=
          (rootModule.moduleDirJava ->
            JavaModuleDto(evaluator, api, mutable.Set.empty[JavaModuleApi]))
      case _ =>
    }
    iterateModuleChildren(
      aggregatedJavaModules,
      evaluator,
      rootModule.moduleDirJava,
      rootModule.moduleDirectChildren
    )

    aggregatedJavaModules.toMap
  }

  /**
   *  This resolves the aggregated Java Modules by resolving itself and the potential source set
   *  Test Modules. This makes sure that all of them are always resolved together and if a failure
   *  occours, all of it fails.
   *
   *  @param aggregatedJavaModules base for resolving the separate Mill Modules
   *  @return a map of Java Resolved Module DTO objects by their path
   */
  private def getResolvedJavaModules(aggregatedJavaModules: Map[Path, JavaModuleDto])
      : Map[Path, JavaResolvedModuleDto] = {
    val resolvedJavaModules = mutable.Map.empty[Path, JavaResolvedModuleDto]

    for ((path, dto) <- aggregatedJavaModules) {
      val evaluator = dto.evaluatorApi
      val moduleTask = dto.module.genEclipseInternal().genEclipseModuleInformation()

      val resolvedModule = evaluator.executeApi(Seq(moduleTask)).executionResults match {
        case r if r.transitiveFailingApi.nonEmpty =>
          throw GenEclipseException(
            s"Failure during resolving modules: ${ExecutionResultsApi.formatFailing(r)}"
          )
        case r => r.values.head.value.asInstanceOf[ResolvedModule]
      }

      val sourceSetModuleTasks = mutable.Set.empty[TaskApi[ResolvedModule]]
      dto.sourceSetModules.foreach(module =>
        sourceSetModuleTasks += module.genEclipseInternal().genEclipseModuleInformation()
      )

      val sourceSetResolvedModules = {
        evaluator.executeApi(sourceSetModuleTasks.toSeq).executionResults match {
          case r if r.transitiveFailingApi.nonEmpty =>
            throw GenEclipseException(
              s"Failure during resolving modules: ${ExecutionResultsApi.formatFailing(r)}"
            )
          case r => r.values.map(_.value).asInstanceOf[Seq[ResolvedModule]]
        }
      }

      resolvedJavaModules +=
        (path -> JavaResolvedModuleDto(resolvedModule, sourceSetResolvedModules))
    }

    resolvedJavaModules.toMap
  }

  /**
   *  This converts the DTO into an actual model for an Eclipse JDT Project.
   *
   *  @param resolvedJavaModules base for converting to a project model
   *  @return a map of Eclipse JDT Project model objects by their project directory path
   */
  private def getEclipseProjects(resolvedJavaModules: Map[Path, JavaResolvedModuleDto])
      : Map[Path, EclipseJdtProject] = {
    val eclipseProjects = mutable.Map.empty[Path, EclipseJdtProject]

    // TODO: Somehow get the Java source and target version!?
    var javaRunningVersion = scala.util.Properties.javaVersion.split("\\.").head
    if (javaRunningVersion.toInt < 9) javaRunningVersion = s"1.$javaRunningVersion"

    val javaSourceVersion = javaRunningVersion
    val javaTargetVersion = javaRunningVersion

    for ((path, value) <- resolvedJavaModules) {
      val projectModule = value.resolvedModule
      val projectName = moduleName(projectModule.segments)
      val isMainTestModule = isTestModule(projectModule.module)

      // By default source folders are inside the Eclipse JDT Project and therefore we create a
      // source folder entry to the classpath file. If the source folder is located outside of the
      // project directory (e.g. in case of generated sources), we have to create a linked resource
      // inside the project file and use that linked resources name inside the classpath file.
      val linkedResources = mutable.Set.empty[LinkedResource]
      val sourceFolders = mutable.Set.empty[SourceFolder]

      for (source <- projectModule.allSources) {
        if (!source.toString.startsWith(path.toString)) {
          val directoryName = source.getFileName.toString

          linkedResources += LinkedResource(source, directoryName)
          sourceFolders +=
            SourceFolder(
              directoryName,
              directoryName,
              isMainTestModule,
              true
            )
        } else if (Files.exists(source)) {
          val relativePath = path.relativize(source).toString
          sourceFolders +=
            SourceFolder(
              relativePath,
              null,
              isMainTestModule,
              false
            )
        }
      }

      val dependentProjectPaths = mutable.Set.empty[Path]
      dependentProjectPaths ++= projectModule.allModuleDependencies

      val dependentLibraryPaths = mutable.Set.empty[Path]
      dependentLibraryPaths ++= projectModule.allLibraryDependencies

      for (sourceSetModule <- value.sourceSetResolvedModules) {
        val sourceSetProjectName = moduleName(sourceSetModule.segments)
        val isSourceSetTestModule = isTestModule(sourceSetModule.module)

        for (source <- sourceSetModule.allSources) {
          if (!source.toString.startsWith(path.toString)) {
            val directoryName = source.getFileName.toString

            linkedResources += LinkedResource(source, directoryName)
            sourceFolders +=
              SourceFolder(
                directoryName,
                sourceSetProjectName.stripPrefix(projectName + "."),
                isSourceSetTestModule,
                true
              )
          } else if (Files.exists(source)) {
            val relativePath = path.relativize(source).toString
            sourceFolders +=
              SourceFolder(
                relativePath,
                sourceSetProjectName.stripPrefix(projectName + "."),
                isSourceSetTestModule,
                false
              )
          }
        }

        // Remove the depentent projects that are "linking to themselves". This means the Test
        // Modules will always link to its direct parent Java Module as a dependency. Since this
        // will be one Eclipse project (production and test code) from multiple modules, we can get
        // rid of these dependencies.
        dependentProjectPaths ++= sourceSetModule.allModuleDependencies
          .filter(dependentModulePath => dependentModulePath != path)

        dependentLibraryPaths ++= sourceSetModule.allLibraryDependencies
      }

      eclipseProjects +=
        (path ->
          EclipseJdtProject(
            projectName,
            javaSourceVersion,
            javaTargetVersion,
            linkedResources.toSeq,
            sourceFolders.toSeq,
            dependentProjectPaths.toSeq,
            dependentLibraryPaths.toSeq.map(path => convertDependencyToLibrary(path))
          ))
    }

    eclipseProjects.toMap
  }

  def run(): Unit = {
    // Gather all the Java modules and their possible direct children that will be added to the
    // same Eclipse project. This is necessary to narrow down the focus to Java modules
    log("Gather and aggregate all Java Modules with their Test Modules ...")

    val aggregatedJavaModules: Map[Path, JavaModuleDto] = getAggregatedJavaModules(evaluators.head)
    if (aggregatedJavaModules.isEmpty) {
      log("No Java Modules found in build, stopping here!")
      return
    }

    // Resolve all the aggregated Java modules and their possible direct children. This is done via
    // the th "GenEclipseModule" that incorporate the Java Modules.
    log("Resolving all aggregated Java Modules ...")

    val resolvedJavaModules: Map[Path, JavaResolvedModuleDto] =
      getResolvedJavaModules(aggregatedJavaModules)

    // Create the actual Eclipse JDT project object that will then be used to write the Eclipse
    // project specific files on disk.
    log("Creating all the Eclipse JDT Projects ...")

    val eclipseProjects: Map[Path, EclipseJdtProject] = getEclipseProjects(resolvedJavaModules)

    // Write all the Java project files on disk, based on the "dependentProjectPaths" get the name
    // of the encapsulating project - the one that was a Mill Module "containing" other (test) Mill
    // Modules.
    val pp = new scala.xml.PrettyPrinter(999, 2)

    for ((projectDir, eclipseProject) <- eclipseProjects) {
      log("Writing Eclipse JDT Project on disk:")
      log(" Name: " + eclipseProject.projectName)
      log(" Path: " + projectDir.toString)

      val projectFile = os.Path(projectDir) / ".project"
      val classpathFile = os.Path(projectDir) / ".classpath"
      val orgEclipseCoreResourcesPrefsFile =
        os.Path(projectDir) / ".settings" / "org.eclipse.core.resources.prefs"
      val orgEclipseJdtCorePrefsFile =
        os.Path(projectDir) / ".settings" / "org.eclipse.jdt.core.prefs"

      // i) Delete all old files / folders
      os.remove.all(projectFile)
      os.remove.all(classpathFile)
      os.remove.all(orgEclipseCoreResourcesPrefsFile)
      os.remove.all(orgEclipseJdtCorePrefsFile)

      // ii) Get the content to make sure that we only write everything at all on disk and not only
      // parts of it in case of a failure.
      val projectFileContent =
        EclipseJdtUtils.createProjectFileContent(
          eclipseProject.projectName,
          eclipseProject.linkedResources
        )
      val classpathFileContent =
        EclipseJdtUtils.createClasspathFileContent(
          eclipseProject.javaTargetVersion,
          eclipseProject.sourceFolders,
          eclipseProject.dependentProjectPaths.map(projectPath =>
            eclipseProjects(projectPath).projectName
          ),
          eclipseProject.dependentLibraries
        )
      val orgEclipseCoreResourcesPrefsFileContent =
        EclipseJdtUtils.getOrgEclipseCoreResourcesPrefsContent
      val orgEclipseJdtCorePrefsFileContent =
        EclipseJdtUtils.getOrgEclipseJdtCorePrefsContent(
          eclipseProject.javaSourceVersion,
          eclipseProject.javaTargetVersion
        )

      // iii) Write new files to disk
      os.write.over(projectFile, pp.format(projectFileContent), createFolders = true)
      os.write.over(classpathFile, pp.format(classpathFileContent), createFolders = true)
      os.write.over(
        orgEclipseCoreResourcesPrefsFile,
        orgEclipseCoreResourcesPrefsFileContent,
        createFolders = true
      )
      os.write.over(
        orgEclipseJdtCorePrefsFile,
        orgEclipseJdtCorePrefsFileContent,
        createFolders = true
      )
    }
  }
}

object GenEclipseImpl {

  /**
   * Iterete over the children of a module [[ModuleApi]] and either create a new [[JavaModuleDto]]
   * object out of it or add it as a source set module to an existing one.
   *
   * @param javaModuleDtos  where to add new object to / extend an existing one
   * @param evaluator       the evaluator of the children, will be used for adding a new object
   * @param parentModuleDir the parent Java (not test) module dir
   * @param children        to iterate over
   */
  private def iterateModuleChildren(
      javaModuleDtos: mutable.Map[Path, JavaModuleDto],
      evaluator: EvaluatorApi,
      parentModuleDir: Path,
      children: Seq[ModuleApi]
  ): Unit = {
    for (child <- children) {
      var newParentModuleDir = parentModuleDir

      child match {
        case api: JavaModuleApi =>
          val childModuleDirJava = child.moduleDirJava

          if (javaModuleDtos.contains(childModuleDirJava)) {
            javaModuleDtos(childModuleDirJava).addSourceSetModule(api)
          } else if (
            child.isInstanceOf[TestModuleApi] && javaModuleDtos.contains(parentModuleDir)
          ) {
            javaModuleDtos(parentModuleDir).addSourceSetModule(api)
          } else {
            javaModuleDtos +=
              (childModuleDirJava ->
                JavaModuleDto(evaluator, api, mutable.Set.empty[JavaModuleApi]))
            newParentModuleDir = childModuleDirJava
          }
        case _ =>
      }

      // If a test module was added we check its children, if they are also test modules, add them
      // to the next top most Java module (production code).
      iterateModuleChildren(
        javaModuleDtos,
        evaluator,
        newParentModuleDir,
        child.moduleDirectChildren
      )
    }
  }

  /** Checks whether or not a specific module is also a test module containing test sources */
  private def isTestModule(javaModule: JavaModuleApi): Boolean =
    javaModule.isInstanceOf[TestModuleApi]

  /**
   *  When we have a dependency from any module, we want to provide to Eclipse as much information
   *  as possible. This includes the sources and Javadoc Jar archives (if they exist).
   *
   *  As a dependency can also only be a folder of ".class" files, we do this check only for the
   *  dependencies that for sure are Jar archives!
   *
   *  @param dependencyPath path to the provided Mill Module dependency
   *  @return an encapsulating object potentially containing sources and Javadoc as well
   */
  private def convertDependencyToLibrary(dependencyPath: Path): Library = {
    var sourcesJarPath: Path | Null = null
    var javadocJarPath: Path | Null = null

    val jarSuffix = ".jar"
    if (dependencyPath.toString.endsWith(jarSuffix)) {
      val pathWithoutSuffix = dependencyPath.toString.stripSuffix(jarSuffix)

      val possibleSourcesJarPath = Paths.get(pathWithoutSuffix + "-sources" + jarSuffix)
      if (Files.exists(possibleSourcesJarPath)) {
        sourcesJarPath = possibleSourcesJarPath
      }

      val possibleJavadocJarPath = Paths.get(pathWithoutSuffix + "-javadoc" + jarSuffix)
      if (Files.exists(possibleJavadocJarPath)) {
        javadocJarPath = possibleJavadocJarPath
      }
    }

    Library(dependencyPath, sourcesJarPath, javadocJarPath)
  }

  /**
   * Create the module name (to be used by Eclipse) for the module based on it segments.
   *
   * @see [[Module.moduleSegments]]
   */
  private def moduleName(p: Segments): String =
    p.value
      .foldLeft(new StringBuilder()) {
        case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
        case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
        case (sb, Segment.Label(s)) => sb.append(".").append(s)
        case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
      }
      .mkString
      .toLowerCase()

  /**
   *  This is used when iterating all Java modules and pre-aggregating them for the actual Eclipse
   *  JDT projects that will be created from the (later) resolved modules.
   *
   *  @param evaluatorApi used for evaluating the internal Eclipse-related tasks on the modules(s)
   *  @param module the (production code) module that will be the base for the Eclipse project
   *  @param sourceSetModules Mill modules used for direct nested test source sets
   */
  private case class JavaModuleDto(
      evaluatorApi: EvaluatorApi,
      module: JavaModuleApi,
      sourceSetModules: mutable.Set[JavaModuleApi]
  ) {
    def addSourceSetModule(module: JavaModuleApi): Unit = {
      sourceSetModules += module
    }
  }

  /**
   *  This is used once the module tasks for generating Eclipse related [[ResolvedModule]] info is
   *  evaluated.
   *
   *  @param resolvedModule the resolved (production code) module used for the Eclipse JDT project
   *  @param sourceSetResolvedModules resolved Mill modules used for direct nested test source sets
   */
  private case class JavaResolvedModuleDto(
      resolvedModule: ResolvedModule,
      sourceSetResolvedModules: Seq[ResolvedModule]
  )

  /**
   *  A generator specific exception thrown when a Mill Module task for generating Eclipse related
   *  information cannot be evaluated.
   */
  private case class GenEclipseException(msg: String) extends RuntimeException(msg)
}
