package mill.contrib.vaadin

import coursier.core as cs
import mill.*
import mill.api.PathRef
import mill.contrib.vaadin.api.{FrontendBuildConfig, Logger as WorkerLogger, VaadinWorkerApi}
import mill.javalib.*
import mill.util.{Jvm, Version}

/**
 * Builds [[https://vaadin.com Vaadin Flow]] applications.
 *
 * Mix this trait into the Java/Kotlin/Scala module hosting the Vaadin
 * application. It provides:
 *
 *   - [[vaadinBuildFrontend]]: the production frontend build ("prepare-frontend"
 *     + "build-frontend" of the Maven/Gradle plugins): npm install and a Vite
 *     bundle, written as servlet resources (`META-INF/VAADIN`),
 *   - [[vaadinPackage]]: a self-contained production distribution,
 *   - dev mode support for `run`/`runBackground`, pointing Vaadin at the
 *     project folder.
 *
 * Runtime-only dependencies are declared per mode: `runMvnDeps` for `run`
 * (e.g. `com.vaadin:vaadin-dev`, Spring Boot devtools) and
 * [[vaadinProdRunMvnDeps]] for the production bundle and distribution.
 *
 * The frontend build runs Vaadin's `flow-plugin-base` in an isolated worker
 * classloader, in the Flow version found on the application's classpath. Flow
 * [[BuildInfo.flowPluginBaseVersion]] or newer is required.
 */
trait VaadinModule extends JavaModule {

  /**
   * Vaadin's frontend directory: custom frontend sources (themes, styles,
   * TypeScript views), plus the `generated` folder Vaadin writes into it.
   *
   * Defaults to `frontend` next to `src` and `resources` in Mill's layout, and
   * to Vaadin's own convention `src/main/frontend` in a [[MavenModule]]. Used by
   * the production build as well as by development mode.
   */
  def vaadinFrontendDir: T[PathRef] = Task.Source(
    this match {
      case _: MavenModule => os.sub / "src/main/frontend"
      case _ => os.sub / "frontend"
    }
  )

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
   * The production build uses a folder of its own in [[vaadinFrontendBuild]].
   */
  def vaadinDevBuildToolsDir: T[os.Path] = Task(persistent = true) { Task.dest }

  /** Identifier of the application's frontend bundle. */
  def vaadinApplicationIdentifier: T[String] = Task {
    "app-" + Option(artifactName()).filter(_.nonEmpty).getOrElse(moduleDir.last)
  }

  /**
   * Runtime-only dependencies of the production application, the production
   * counterpart of `runMvnDeps` (e.g. a JDBC driver or a logging backend).
   *
   * `runMvnDeps` is only used by `run`, and typically holds development tooling
   * such as `com.vaadin:vaadin-dev` (the Vite dev server integration, dev tools
   * and Copilot) that must neither be shipped nor scanned into the production
   * bundle. A runtime dependency needed in both modes goes into both, e.g.
   * `def runMvnDeps = super.runMvnDeps() ++ vaadinProdRunMvnDeps() ++ Seq(...)`.
   */
  def vaadinProdRunMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Third-party jars of the production application: the runtime closure of
   * `mvnDeps` of this module and its module dependencies, plus
   * [[vaadinProdRunMvnDeps]]. It is resolved like `resolvedRunMvnDeps`, but
   * with [[vaadinProdRunMvnDeps]] in place of `runMvnDeps`. Runtime-scoped
   * transitive dependencies are included, unlike in `compileClasspath`.
   */
  @annotation.nowarn("cat=deprecation")
  def vaadinProdMvnClasspath: T[Seq[PathRef]] = Task {
    val deps = (Task.traverse(transitiveModuleDeps)(_.allMvnDeps)().flatten ++
      vaadinProdRunMvnDeps()).distinct
    millResolver().classpath(
      deps.map(bindDependency()).map { bound =>
        if (bound.dep.isVariantAttributesBased) bound
        else bound.copy(dep = bound.dep.withConfiguration(cs.Configuration.runtime))
      },
      artifactTypes = Some(artifactTypes()),
      resolutionParamsMapOpt = Some { params =>
        params
          .withDefaultConfiguration(cs.Configuration.runtime)
          .withDefaultVariantAttributes(
            cs.VariantSelector.AttributesBased(
              params.defaultVariantAttributes.map(_.matchers).getOrElse(Map()) ++ Seq(
                "org.gradle.usage" -> cs.VariantSelector.VariantMatcher.Runtime
              )
            )
          )
      },
      boms = allBomDeps()
    )
  }

  /**
   * Production runtime classpath: compiled classes and resources plus
   * [[vaadinProdMvnClasspath]]. Scanned by the frontend build, so that the
   * bundle matches what [[vaadinPackage]] ships.
   */
  def vaadinProdRunClasspath: T[Seq[PathRef]] = Task {
    transitiveLocalClasspath() ++ localClasspath() ++ vaadinProdMvnClasspath()
  }

  /**
   * Flow version on the application's classpath (usually managed by `vaadin-bom`).
   * It must be at least the version the worker is compiled against,
   * [[BuildInfo.flowPluginBaseVersion]].
   */
  def vaadinFlowVersion: T[String] = Task {
    val FlowServerJar = """flow-server-(\d[^/]*)\.jar""".r
    val version = vaadinProdMvnClasspath().map(_.path.last)
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
      sharedLoader = classOf[VaadinWorkerApi].getClassLoader,
      sharedPrefixes = Seq("mill.contrib.vaadin.api.", "scala.")
    )
  }

  def vaadinWorker: Task.Worker[VaadinWorkerApi] = Task.Worker {
    vaadinWorkerClassLoader()
      .loadClass("mill.contrib.vaadin.worker.VaadinWorkerImpl")
      .getConstructor()
      .newInstance()
      .asInstanceOf[VaadinWorkerApi]
  }

  /**
   * Runs the Vaadin production frontend build (npm install + Vite bundle),
   * writing servlet resources (`META-INF/VAADIN`) into the task's dest dir.
   * Vaadin's build folder for it is `build-tools` in the dest dir as well.
   */
  def vaadinFrontendBuild: T[PathRef] = Task {
    if (Runtime.version().feature() < 21) {
      Task.fail("Vaadin's build tooling requires Mill to run on JDK 21+ (set `mill-jvm-version`)")
    }
    vaadinFrontendConfig()
    val stage = Task.dest / "servlet-resources"
    val buildTools = Task.dest / "build-tools"
    os.makeDir.all(stage)
    os.makeDir.all(buildTools)
    val sourceDirs = sources().map(_.path)

    val config = VaadinModule.config(
      projectDir = moduleDir,
      frontendDir = vaadinFrontendDir().path,
      javaSourceDir = sourceDirs.find(os.isDir).getOrElse(sourceDirs.last),
      resourcesDir = resources().head.path,
      buildToolsDir = buildTools,
      stageDir = stage,
      classpath = vaadinProdRunClasspath().map(_.path),
      applicationIdentifier = vaadinApplicationIdentifier()
    )
    vaadinWorker().buildFrontend(config, VaadinModule.logger(Task.log))

    PathRef(stage)
  }

  /** Command wrapper around [[vaadinFrontendBuild]]. */
  def vaadinBuildFrontend(): Command[PathRef] = Task.Command {
    vaadinFrontendBuild()
  }

  /**
   * Builds a self-contained production distribution:
   *
   *   - `dist/app.jar`            application classes and resources
   *   - `dist/lib/`               module dependency jars and third-party jars
   *   - `dist/vaadin-frontend/`   production frontend bundle (`META-INF/VAADIN`)
   *   - `dist/run-prod.sh`        launch script
   */
  def vaadinPackage(): Command[PathRef] = Task.Command {
    val stage = vaadinFrontendBuild().path
    val dist = Task.dest / "dist"
    os.remove.all(dist)
    os.makeDir.all(dist / "lib")

    os.copy.over(jar().path, dist / "app.jar")
    // Module jars are all called `out.jar`; name them after their module.
    recursiveModuleDeps.zip(Task.traverse(recursiveModuleDeps)(_.jar)()).foreach {
      case (module, moduleJar) =>
        os.copy.over(moduleJar.path, dist / "lib" / s"${module.moduleSegments.render}.jar")
    }
    vaadinProdMvnClasspath().map(_.path).filter(_.last.endsWith(".jar")).foreach { jarPath =>
      os.copy.over(jarPath, dist / "lib" / jarPath.last)
    }
    os.copy(stage, dist / "vaadin-frontend")

    val cp = Seq("app.jar", "vaadin-frontend", "lib/*").mkString(java.io.File.pathSeparator)
    os.write(
      dist / "run-prod.sh",
      s"""#!/bin/sh
         |cd "$$(dirname "$$0")"
         |exec java -cp "$cp" ${finalMainClass()} "$$@"
         |""".stripMargin
    )
    os.perms.set(dist / "run-prod.sh", "rwxr-xr-x")
    PathRef(dist)
  }

  /**
   * Dev mode (`run`): Vaadin looks for Maven/Gradle markers to find the
   * project, so point it at the module directly, at its frontend directory and
   * at [[vaadinDevBuildToolsDir]].
   */
  override def forkArgs: T[Seq[String]] = Task {
    val projectDir = VaadinModule.realPath(moduleDir)
    val buildTools = VaadinModule.realPath(vaadinDevBuildToolsDir())
    super.forkArgs() ++ Seq(
      s"-Dvaadin.project.basedir=$projectDir",
      s"-D${VaadinModule.FrontendFolderProperty}=${VaadinModule.realPath(vaadinFrontendDir().path)}",
      s"-Dvaadin.build.folder=${projectDir.relativize(buildTools)}"
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

  /**
   * Real on-disk path of `p`. Mill may hand out relativized or symlink-aliased
   * paths (`../mill-workspace/...`); Vaadin resolves paths on its own, also from
   * other working directories (dev mode) and relative to each other, so it needs
   * the real location. Resolves the nearest existing ancestor, as `p` itself may
   * not have been created yet.
   */
  private[vaadin] def realPath(p: os.Path): java.nio.file.Path = {
    val abs = PathRef.toAbsNioPath(p)
    Iterator.iterate(abs)(_.getParent).takeWhile(_ != null)
      .find(java.nio.file.Files.exists(_))
      .fold(abs)(existing => existing.toRealPath().resolve(existing.relativize(abs)))
  }

  private def logger(log: mill.api.Logger): WorkerLogger = new WorkerLogger {
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
    projectDir = realPath(projectDir),
    frontendDir = realPath(frontendDir),
    javaSourceDir = realPath(javaSourceDir),
    resourcesDir = realPath(resourcesDir),
    buildToolsDir = realPath(buildToolsDir),
    stageDir = realPath(stageDir),
    classpath = classpath.map(realPath),
    applicationIdentifier = applicationIdentifier
  )
}
