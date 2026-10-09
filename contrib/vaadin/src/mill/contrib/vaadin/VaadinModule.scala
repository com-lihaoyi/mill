package mill.contrib.vaadin

import coursier.Repository
import coursier.core.Resolution
import coursier.params.ResolutionParams
import mill.*
import mill.api.{ModuleRef, PathRef}
import mill.contrib.vaadin.api.{FrontendBuildConfig, VaadinLogger, VaadinWorker}
import mill.javalib.*
import mill.util.{Jvm, Version}
import mill.contrib.vaadin.internal.BuildInfo

/**
 * Builds [[https://vaadin.com Vaadin Flow]] applications.
 *
 * Mix this trait into the Java/Kotlin/Scala module hosting the Vaadin
 * application. `run`/`runBackground` of this module start Vaadin's development
 * mode, pointing Vaadin at the project folder. Development-only dependencies
 * (`com.vaadin:vaadin-dev`, Spring Boot devtools, ...) go into its `runMvnDeps`.
 *
 * The production build is a sub-module extending [[VaadinProdModule]]: it adds
 * the production frontend bundle ([[VaadinProdModule.vaadinFrontendBuild]]) to
 * the application, leaves out this module's `runMvnDeps`, and provides the usual
 * `run`, `assembly` or, with [[mill.javalib.repackage.RepackageModule]],
 * `repackagedJar` tasks for production.
 *
 * The frontend build runs Vaadin's `flow-plugin-base` in an isolated worker
 * classloader, in the Flow version found on the application's classpath. Flow
 * [[BuildInfo.flowPluginBaseVersion]] or newer is required.
 */
trait VaadinModule extends JavaModule { outer =>

  /**
   * Vaadin's frontend directory: custom frontend sources (themes, styles,
   * TypeScript views), plus the `generated` folder Vaadin writes into it.
   *
   * Defaults to `frontend` next to `src` and `resources` in Mill's layout, and
   * to Vaadin's own convention `src/main/frontend` in a [[MavenModule]]. Used by
   * the production build as well as by development mode.
   */
  def vaadinFrontendDir: T[PathRef] = this match {
    case _: MavenModule => Task.Source("src/main/frontend")
    case _ => Task.Source("frontend")
  }

  /**
   * npm/Vite configuration read by the frontend build, declared as inputs so
   * that edits invalidate the cached bundle. `package.json` is also updated by
   * the build itself, which may cause one extra rebuild after it changed.
   * (`vite.generated.ts` is left out: the build regenerates it.)
   */
  def vaadinFrontendConfig: T[Seq[PathRef]] = Task.Sources(
    "package.json",
    "package-lock.json",
    "vite.config.ts",
    "tsconfig.json",
    "types.d.ts"
  )

  /**
   * Vaadin's build folder (Maven: `target`, Gradle: `build`) for development
   * mode: `run` keeps the dev bundle and the dev server's files there. It is
   * persistent, so they survive `run` restarts, and specific to this module.
   * The production build uses a folder of its own in
   * [[VaadinProdModule.vaadinFrontendBuild]].
   */
  def vaadinDevBuildToolsDir: T[os.Path] = Task(persistent = true) { Task.dest }

  /** Identifier of the application's frontend bundle. */
  def vaadinApplicationIdentifier: T[String] = Task {
    "app-" + Option(artifactName()).filter(_.nonEmpty).getOrElse(moduleDir.last)
  }

  /**
   * Flow version on the application's classpath (usually managed by `vaadin-bom`).
   * It must be at least the version the worker is compiled against,
   * [[BuildInfo.flowPluginBaseVersion]].
   */
  def vaadinFlowVersion: T[String] = Task {
    val FlowServerJar = """flow-server-(\d[^/]*)\.jar""".r
    val version = resolvedRunMvnDeps().map(_.path.last)
      .collectFirst { case FlowServerJar(v) => v }
      .getOrElse(Task.fail(
        s"com.vaadin:flow-server not found in the mvnDeps of ${moduleSegments.render}"
      ))
    if (!VaadinModule.isSupportedFlowVersion(version)) {
      Task.fail(
        s"VaadinModule requires Vaadin Flow ${BuildInfo.flowPluginBaseVersion} or newer, found Flow $version"
      )
    }
    version
  }

  /**
   * Classpath of the frontend build worker: the worker plus `flow-plugin-base`
   * in the application's Flow version, so the bundle's build info always
   * matches the runtime.
   */
  def vaadinWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-contrib-vaadin-worker"),
      mvn"com.vaadin:flow-plugin-base:${vaadinFlowVersion()}",
      // Vaadin's build tasks log npm/Vite progress and errors via SLF4J (to stderr)
      mvn"org.slf4j:slf4j-simple:${BuildInfo.slf4jSimpleVersion}"
    ))
  }

  /**
   * Classloader of the worker. Only the worker API and the Scala library come
   * from the build's classloader; Vaadin's tooling and its dependencies are
   * isolated from Mill's own libraries.
   */
  private def vaadinWorkerClassLoader: Task.Worker[ClassLoader & AutoCloseable] = Task.Worker {
    Jvm.createClassLoader(
      classPath = vaadinWorkerClasspath().map(_.path),
      parent = null,
      sharedLoader = classOf[VaadinWorker].getClassLoader,
      sharedPrefixes = Seq("mill.contrib.vaadin.api.", "scala.")
    )
  }

  def vaadinWorker: Task.Worker[VaadinWorker] = Task.Worker {
    vaadinWorkerClassLoader()
      .loadClass("mill.contrib.vaadin.worker.VaadinWorkerImpl")
      .getConstructor()
      .newInstance()
      .asInstanceOf[VaadinWorker]
  }

  /**
   * The production build of the application, declared as a sub-module of the
   * Vaadin module:
   *
   * {{{
   * object prod extends VaadinProdModule
   * }}}
   *
   * It runs the classes and resources of the Vaadin module together with the
   * production frontend bundle ([[vaadinFrontendBuild]]). Its dependencies are
   * the `mvnDeps` of the Vaadin module and its module dependencies, but not the
   * Vaadin module's `runMvnDeps`, which are meant for development mode. Runtime
   * dependencies of the production build, e.g. a JDBC driver, go into the
   * sub-module's own `runMvnDeps`.
   *
   * As a regular [[JavaModule]], it provides `run`, `runBackground`, `assembly`
   * and `launcher` for the production application. Mix in
   * [[mill.javalib.repackage.RepackageModule]] for an executable Spring Boot jar
   * (`repackagedJar`).
   */
  trait VaadinProdModule extends JavaModule {
    override def moduleDeps: Seq[JavaModule] = outer.moduleDeps
    override def runModuleDeps: Seq[JavaModule] = outer.runModuleDeps
    override def mvnDeps: T[Seq[Dep]] = outer.mvnDeps()
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task { outer.mandatoryMvnDeps() }
    override def bomMvnDeps: T[Seq[Dep]] = super.bomMvnDeps() ++ outer.bomMvnDeps()
    override def depManagement: T[Seq[Dep]] = super.depManagement() ++ outer.depManagement()
    override def repositoriesTask: Task[Seq[Repository]] = Task.Anon { outer.repositoriesTask() }
    override def resolutionCustomizer: Task[Option[Resolution => Resolution]] =
      outer.resolutionCustomizer
    override def resolutionParams: Task[ResolutionParams] = outer.resolutionParams
    override def jvmWorker: ModuleRef[JvmWorkerModule] = outer.jvmWorker
    override def jvmId: T[String] = outer.jvmId()
    override def jvmVersion: T[String] = outer.jvmVersion()
    override def jvmIndexVersion: T[String] = outer.jvmIndexVersion()
    override def javaHome: T[Option[PathRef]] = outer.javaHome()
    override def mainClass: T[Option[String]] = outer.mainClass()

    /** No sources of its own: the application is compiled by the Vaadin module. */
    override def sources: T[Seq[PathRef]] = Task.Sources()

    /** Production-only resources (`resources/` of this sub-module) and the frontend bundle. */
    override def resources: T[Seq[PathRef]] = super.resources() ++ Seq(vaadinFrontendBuild())

    override def localRunClasspath: T[Seq[PathRef]] =
      outer.localClasspath() ++ super.localRunClasspath()

    override def skipIdea = true
    override def enableBsp = false

    /**
     * Runs the Vaadin production frontend build (npm install + Vite bundle),
     * writing servlet resources (`META-INF/VAADIN`) into the task's dest dir.
     * Vaadin's build folder for it is `build-tools` in the dest dir as well.
     * It scans the production classpath, so the bundle matches what is run
     * and packaged.
     */
    def vaadinFrontendBuild: T[PathRef] = Task {
      if (Runtime.version().feature() < 21) {
        Task.fail("Vaadin's build tooling requires Mill to run on JDK 21+ (set `mill-jvm-version`)")
      }
      outer.vaadinFrontendConfig()
      val stage = Task.dest / "servlet-resources"
      val buildTools = Task.dest / "build-tools"
      os.makeDir.all(stage)
      os.makeDir.all(buildTools)
      val sourceDirs = outer.sources().map(_.path)
      val classpath = resolvedRunMvnDeps() ++ transitiveLocalClasspath() ++ outer.localClasspath()

      val config = VaadinModule.config(
        projectDir = outer.moduleDir,
        frontendDir = outer.vaadinFrontendDir().path,
        javaSourceDir = sourceDirs.find(os.isDir).getOrElse(sourceDirs.last),
        resourcesDir = outer.resources().head.path,
        buildToolsDir = buildTools,
        stageDir = stage,
        classpath = classpath.map(_.path),
        applicationIdentifier = outer.vaadinApplicationIdentifier()
      )
      outer.vaadinWorker().buildFrontend(config, VaadinModule.logger(Task.log))

      PathRef(stage)
    }

    /** Command wrapper around [[vaadinFrontendBuild]]. */
    def vaadinBuildFrontend(): Command[PathRef] = Task.Command {
      vaadinFrontendBuild()
    }
  }

  /**
   * Dev mode (`run`): Vaadin looks for Maven/Gradle markers to find the
   * project, so point it at the module directly, at its frontend directory and
   * at [[vaadinDevBuildToolsDir]].
   */
  override def forkArgs: T[Seq[String]] = Task {
    val projectDir = moduleDir
    val buildTools = vaadinDevBuildToolsDir()
    super.forkArgs() ++ Seq(
      s"-Dvaadin.project.basedir=$projectDir",
      s"-D${VaadinModule.FrontendFolderProperty}=${vaadinFrontendDir().path}",
      s"-Dvaadin.build.folder=${buildTools.relativeTo(projectDir)}"
    )
  }
}

object VaadinModule {

  /**
   * System property of Vaadin's frontend directory in dev mode: Vaadin reads
   * the `vaadin.frontend.folder` setting from `vaadin.`-prefixed system properties.
   */
  private[vaadin] val FrontendFolderProperty = "vaadin.vaadin.frontend.folder"

  /** Whether the worker, compiled against [[BuildInfo.flowPluginBaseVersion]], supports `flowVersion`. */
  private[vaadin] def isSupportedFlowVersion(flowVersion: String): Boolean =
    Version.parse(flowVersion)
      .isAtLeast(Version.parse(BuildInfo.flowPluginBaseVersion))(using Version.MavenOrdering)

  private def logger(log: mill.api.Logger): VaadinLogger = new VaadinLogger {
    def error(msg: String): Unit = log.error(msg)
    def warn(msg: String): Unit = log.warn(msg)
    def info(msg: String): Unit = log.info(msg)
    def debug(msg: String): Unit = log.debug(msg)
  }

  private def config(
      projectDir: os.Path,
      frontendDir: os.Path,
      javaSourceDir: os.Path,
      resourcesDir: os.Path,
      buildToolsDir: os.Path,
      stageDir: os.Path,
      classpath: Seq[os.Path],
      applicationIdentifier: String
  ): FrontendBuildConfig = FrontendBuildConfig(
    projectDir = projectDir.toNIO,
    frontendDir = frontendDir.toNIO,
    javaSourceDir = javaSourceDir.toNIO,
    resourcesDir = resourcesDir.toNIO,
    buildToolsDir = buildToolsDir.toNIO,
    stageDir = stageDir.toNIO,
    classpath = classpath.map(_.toNIO),
    applicationIdentifier = applicationIdentifier
  )
}
