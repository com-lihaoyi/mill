package mill.contrib.vaadin

import coursier.core as cs
import mill.*
import mill.api.{BuildCtx, PathRef}
import mill.constants.OutFiles.OutFiles
import mill.contrib.vaadin.api.VaadinWorkerApi
import mill.javalib.*
import mill.util.Jvm

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
 * Put dev-only dependencies (`com.vaadin:vaadin-dev`, Spring Boot devtools, ...)
 * into `runMvnDeps`: they are used by `run`, but kept out of the production
 * bundle and distribution.
 *
 * The frontend build runs Vaadin's `flow-plugin-base` in an isolated worker
 * classloader, in the Flow version found on the application's classpath.
 */
trait VaadinModule extends JavaModule {

  /** Custom frontend sources (themes, styles, ...). The first entry is Vaadin's frontend directory. */
  def vaadinFrontendSources: T[Seq[PathRef]] = Task.Sources("src/main/frontend")

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
   * Vaadin's build-tools folder (Maven: `target`, Gradle: `build`). Shared by
   * the production build and dev mode: `vite.generated.ts` references it.
   */
  def vaadinBuildToolsDir: os.Path =
    moduleSegments.parts.foldLeft(os.Path(OutFiles.out, BuildCtx.workspaceRoot))(_ / _) /
      "vaadin-build-tools"

  /** Identifier of the application's frontend bundle. */
  def vaadinApplicationIdentifier: T[String] = Task {
    "app-" + Option(artifactName()).filter(_.nonEmpty).getOrElse(moduleDir.last)
  }

  /**
   * Third-party jars of the production application: the runtime closure of
   * `mvnDeps` of this module and its module dependencies, including
   * runtime-scoped transitive dependencies but without `runMvnDeps`. Resolved
   * the same way as `resolvedRunMvnDeps`.
   */
  @annotation.nowarn("cat=deprecation")
  def vaadinProdMvnClasspath: T[Seq[PathRef]] = Task {
    val deps = Task.traverse(transitiveModuleDeps)(_.allMvnDeps)().flatten.distinct
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

  /** Flow version on the application's classpath (usually managed by `vaadin-bom`). */
  def vaadinFlowVersion: T[String] = Task {
    val FlowServerJar = """flow-server-(\d[^/]*)\.jar""".r
    val version = vaadinProdMvnClasspath().map(_.path.last)
      .collectFirst { case FlowServerJar(v) => v }
      .getOrElse(Task.fail(
        s"com.vaadin:flow-server not found in the mvnDeps of ${moduleSegments.render}"
      ))
    if (version.takeWhile(_.isDigit).toInt < VaadinModule.MinFlowMajorVersion) {
      Task.fail(
        s"VaadinModule supports Vaadin/Flow ${VaadinModule.MinFlowMajorVersion}+, found Flow $version"
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
      mvn"org.slf4j:slf4j-simple:${VaadinModule.Slf4jVersion}"
    ))
  }

  /**
   * Classloader of the worker. Only the worker API comes from the build's
   * classloader; Vaadin's tooling and its dependencies are isolated from
   * Mill's own libraries.
   */
  private def vaadinWorkerClassLoader: Task.Worker[ClassLoader & AutoCloseable] = Task.Worker {
    Jvm.createClassLoader(
      classPath = vaadinWorkerClasspath().map(_.path),
      parent = null,
      sharedLoader = classOf[VaadinWorkerApi].getClassLoader,
      sharedPrefixes = Seq("mill.contrib.vaadin.api.")
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
   */
  def vaadinFrontendBuild: T[PathRef] = Task {
    if (Runtime.version().feature() < 21) {
      Task.fail("Vaadin's build tooling requires Mill to run on JDK 21+ (set `mill-jvm-version`)")
    }
    vaadinFrontendConfig()
    val stage = Task.dest / "servlet-resources"
    os.makeDir.all(stage)
    val sourceDirs = sources().map(_.path)

    val config = VaadinModule.config(
      projectDir = moduleDir,
      frontendDir = vaadinFrontendSources().head.path,
      javaSourceDir = sourceDirs.find(os.isDir).getOrElse(sourceDirs.last),
      resourcesDir = resources().head.path,
      buildToolsDir = vaadinBuildToolsDir,
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
   * project, so point it at the module directly, and at the same build-tools
   * folder as the production build.
   */
  override def forkArgs: T[Seq[String]] = Task {
    val projectDir = VaadinModule.realPath(moduleDir)
    super.forkArgs() ++ Seq(
      s"-Dvaadin.project.basedir=$projectDir",
      s"-Dvaadin.build.folder=${projectDir.relativize(VaadinModule.realPath(vaadinBuildToolsDir))}"
    )
  }
}

object VaadinModule {

  /** Oldest Flow major version whose build SPI the worker implements. */
  val MinFlowMajorVersion = 25

  private val Slf4jVersion = "2.0.17"

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

  private def logger(log: mill.api.Logger): VaadinWorkerApi.Logger = new VaadinWorkerApi.Logger {
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
  ): VaadinWorkerApi.Config = {
    val (project, frontend, source, resources, buildTools, stage, cp, appId) = (
      realPath(projectDir),
      realPath(frontendDir),
      realPath(javaSourceDir),
      realPath(resourcesDir),
      realPath(buildToolsDir),
      realPath(stageDir),
      classpath.map(realPath).toArray,
      applicationIdentifier
    )
    new VaadinWorkerApi.Config {
      def projectDir() = project
      def frontendDir() = frontend
      def javaSourceDir() = source
      def resourcesDir() = resources
      def buildToolsDir() = buildTools
      def stageDir() = stage
      def classpath() = cp
      def applicationIdentifier() = appId
    }
  }
}
