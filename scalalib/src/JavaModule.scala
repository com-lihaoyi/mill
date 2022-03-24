package mill
package scalalib

import scala.annotation.nowarn

import coursier.Repository
import mainargs.Flag
import mill.api.Loose.Agg
import mill.api.{PathRef, Result, internal}
import mill.define.{Command, Sources, Target, Task, TaskModule}
import mill.eval.EvaluatorPathsResolver
import mill.modules.Jvm.{createAssembly, createJar}
import mill.modules.{Assembly, Jvm}
import mill.scalalib.api.CompilationResult
import mill.scalalib.bsp.{BspBuildTarget, BspModule}
import mill.scalalib.publish.Artifact
import os.Path

/**
 * Core configuration required to compile a single Java compilation target
 */
trait JavaModule
    extends mill.Module
    with TaskModule
    with GenIdeaModule
    with CoursierModule
    with OfflineSupportModule
    with BspModule { outer =>

  def zincWorker: ZincWorkerModule = mill.scalalib.ZincWorkerModule

  trait JavaModuleTests extends TestModule {
    override def moduleDeps: Seq[JavaModule] = Seq(outer)
    override def repositories: Seq[Repository] = outer.repositories
    override def repositoriesTask: Task[Seq[Repository]] = outer.repositoriesTask
    override def resolutionCustomizer: Task[Option[coursier.Resolution => coursier.Resolution]] =
      outer.resolutionCustomizer
    override def javacOptions: T[Seq[String]] = outer.javacOptions
    override def zincWorker: ZincWorkerModule = outer.zincWorker
    override def skipIdea: Boolean = outer.skipIdea
    override def runUseArgsFile: T[Boolean] = super.runUseArgsFile
  }
  trait Tests extends JavaModuleTests

  def defaultCommandName() = "run"

  def resolvePublishDependency: Task[Dep => publish.Dependency] = T.task {
    Artifact.fromDepJava(_: Dep)
  }

  /**
   * Allows you to specify an explicit main class to use for the `run` command.
   * If none is specified, the classpath is searched for an appropriate main
   * class to use if one exists
   */
  def mainClass: T[Option[String]] = None

  def finalMainClassOpt: T[Either[String, String]] = T {
    mainClass() match {
      case Some(m) => Right(m)
      case None =>
        zincWorker.worker().discoverMainClasses(compile()) match {
          case Seq() => Left("No main class specified or found")
          case Seq(main) => Right(main)
          case mains =>
            Left(
              s"Multiple main classes found (${mains.mkString(",")}) " +
                "please explicitly specify which one to use by overriding mainClass"
            )
        }
    }
  }

  def finalMainClass: T[String] = T {
    finalMainClassOpt() match {
      case Right(main) => Result.Success(main)
      case Left(msg) => Result.Failure(msg)
    }
  }

  /**
   * Mandatory ivy dependencies that are typically always required and shouldn't be removed by
   * overriding [[ivyDeps]], e.g. the scala-library in the [[ScalaModule]].
   */
  def mandatoryIvyDeps: T[Agg[Dep]] = T { Agg.empty[Dep] }

  /**
   * Any ivy dependencies you want to add to this Module, in the format
   * ivy"org::name:version" for Scala dependencies or ivy"org:name:version"
   * for Java dependencies
   */
  def ivyDeps: T[Agg[Dep]] = T { Agg.empty[Dep] }

  /**
   * Aggregation of mandatoryIvyDeps and ivyDeps.
   * In most cases, instead of overriding this Target you want to override `ivyDeps` instead.
   */
  def allIvyDeps: T[Agg[Dep]] = T { mandatoryIvyDeps() ++ ivyDeps() }

  /**
   * Same as `ivyDeps`, but only present at compile time. Useful for e.g.
   * macro-related dependencies like `scala-reflect` that doesn't need to be
   * present at runtime
   */
  def compileIvyDeps = T { Agg.empty[Dep] }

  /**
   * Same as `ivyDeps`, but only present at runtime. Useful for e.g.
   * selecting different versions of a dependency to use at runtime after your
   * code has already been compiled
   */
  def runIvyDeps: T[Agg[Dep]] = T { Agg.empty[Dep] }

  /**
   * Options to pass to the java compiler
   */
  def javacOptions: T[Seq[String]] = T { Seq.empty[String] }

  /** The direct dependencies of this module */
  def moduleDeps: Seq[JavaModule] = Seq.empty

  /** The compile-only direct dependencies of this module. */
  def compileModuleDeps: Seq[JavaModule] = Seq.empty

  /** The compile-only transitive ivy dependencies of this module and all it's upstream compile-only modules. */
  def transitiveCompileIvyDeps: T[Agg[Dep]] = T {
    // We never include compile-only dependencies transitively, but we must include normal transitive dependencies!
    compileIvyDeps() ++ T
      .traverse(compileModuleDeps)(_.transitiveIvyDeps)()
      .flatten
  }

  /**
   * Show the module dependencies.
   * @param recursive If `true` include all recursive module dependencies, else only show direct dependencies.
   */
  def showModuleDeps(recursive: Boolean = false): Command[Unit] = T.command {
    val normalDeps = if (recursive) recursiveModuleDeps else moduleDeps
    val compileDeps =
      if (recursive) compileModuleDeps.flatMap(_.transitiveModuleDeps).distinct
      else compileModuleDeps
    val deps = (normalDeps ++ compileDeps).distinct
    val asString =
      s"${if (recursive) "Recursive module"
      else "Module"} dependencies of ${millModuleSegments.render}:\n\t${deps
        .map { dep =>
          dep.millModuleSegments.render ++
            (if (compileModuleDeps.contains(dep) || !normalDeps.contains(dep)) " (compile)"
             else "")
        }
        .mkString("\n\t")}"
    T.log.outputStream.println(asString)
  }

  /** The direct and indirect dependencies of this module */
  def recursiveModuleDeps: Seq[JavaModule] = {
    moduleDeps.flatMap(_.transitiveModuleDeps).distinct
  }

  /** Like `recursiveModuleDeps` but also include the module itself */
  def transitiveModuleDeps: Seq[JavaModule] = {
    Seq(this) ++ recursiveModuleDeps
  }

  /**
   * Additional jars, classfiles or resources to add to the classpath directly
   * from disk rather than being downloaded from Maven Central or other package
   * repositories
   */
  def unmanagedClasspath: T[Agg[PathRef]] = T { Agg.empty[PathRef] }

  /**
   * The transitive ivy dependencies of this module and all it's upstream modules.
   * This is calculated from [[ivyDeps]], [[mandatoryIvyDeps]] and recursively from [[moduleDeps]].
   */
  def transitiveIvyDeps: T[Agg[Dep]] = T {
    // NOTE: If you update this, reflect the changes in ScalaNativeModule
    ivyDeps() ++ mandatoryIvyDeps() ++ T.traverse(moduleDeps)(_.transitiveIvyDeps)().flatten
  }

  /**
   * The upstream compilation output of all this module's upstream modules
   */
  def upstreamCompileOutput: T[Seq[CompilationResult]] = T {
    T.traverse((recursiveModuleDeps ++ compileModuleDeps.flatMap(
      _.transitiveModuleDeps
    )).distinct)(_.compile)
  }

  /**
   * The transitive version of `localClasspath`
   */
  def transitiveLocalClasspath: T[Agg[PathRef]] = T {
    T.traverse(moduleDeps ++ compileModuleDeps)(m =>
      T.task { m.localClasspath() ++ m.transitiveLocalClasspath() }
    )()
      .flatten
  }

  /**
   * What platform suffix to use for publishing, e.g. `_sjs` for Scala.js
   * projects
   */
  def platformSuffix: T[String] = T { "" }

  /**
   * What shell script to use to launch the executable generated by `assembly`.
   * Defaults to a generic "universal" launcher that should work for Windows,
   * OS-X and Linux
   */
  def prependShellScript: T[String] = T {
    finalMainClassOpt().toOption match {
      case None => ""
      case Some(cls) =>
        mill.modules.Jvm.launcherUniversalScript(
          mainClass = cls,
          shellClassPath = Agg("$0"),
          cmdClassPath = Agg("%~dpnx0"),
          jvmArgs = forkArgs()
        )
    }
  }

  def assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules

  /**
   * The folders where the source files for this module live
   */
  def sources = T.sources { millSourcePath / "src" }

  /**
   * The folders where the resource files for this module live
   */
  def resources: Sources = T.sources { millSourcePath / "resources" }

  /**
   * Folders containing source files that are generated rather than
   * hand-written; these files can be generated in this target itself,
   * or can refer to files generated from other targets
   */
  def generatedSources: T[Seq[PathRef]] = T { Seq.empty[PathRef] }

  /**
   * The folders containing all source files fed into the compiler
   */
  def allSources: T[Seq[PathRef]] = T { sources() ++ generatedSources() }

  /**
   * All individual source files fed into the Java compiler
   */
  def allSourceFiles: T[Seq[PathRef]] = T {
    Lib.findSourceFiles(allSources(), Seq("java")).map(PathRef(_))
  }

  /**
   * Compiles the current module to generate compiled classfiles/bytecode
   */
  // Keep in sync with [[bspCompileClassesPath]]
  def compile: T[mill.scalalib.api.CompilationResult] = T.persistent {
    zincWorker
      .worker()
      .compileJava(
        upstreamCompileOutput(),
        allSourceFiles().map(_.path),
        compileClasspath().map(_.path),
        javacOptions(),
        T.reporter.apply(hashCode)
      )
  }

  /** The path to the compiled classes without forcing to actually run the target. */
  // Keep in sync with [[compile]]
  @internal
  def bspCompileClassesPath(pathsResolver: Task[EvaluatorPathsResolver]): Task[PathRef] = {
    if (compile.ctx.enclosing == s"${classOf[JavaModule].getName}#compile") {
      T.task {
        val pr = PathRef(
          pathsResolver().resolveDest(compile).dest / "classes"
        )
        T.log.debug(
          s"compile target was not overridden, assuming hard-coded classes directory for target ${compile}"
        )
        pr
      }
    } else T.task {
      T.log.debug(
        s"compile target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compile}"
      )
      compile().classes
    }
  }

  /**
   * The output classfiles/resources from this module, excluding upstream
   * modules and third-party dependencies
   */
  def localClasspath: T[Seq[PathRef]] = T {
    resources() ++ Agg(compile().classes)
  }

  /**
   * All classfiles and resources from upstream modules and dependencies
   * necessary to compile this module
   */
  // Keep in sync with [[bspCompileClasspath]]
  def compileClasspath: T[Agg[PathRef]] = T {
    transitiveLocalClasspath() ++
      resources() ++
      unmanagedClasspath() ++
      resolvedIvyDeps()
  }

  /** Same as [[compileClasspath]], but does not trigger compilation targets, if possible. */
  // Keep in sync with [[compileClasspath]]
  @internal
  def bspCompileClasspath(pathsResolver: Task[EvaluatorPathsResolver]): Task[Seq[PathRef]] = {
    def bspLocalClasspath(
        j: JavaModule,
        pathsResolver: Task[EvaluatorPathsResolver]
    ): Task[Seq[PathRef]] = {
      val cl = j.bspCompileClassesPath(pathsResolver)
      T.task {
        j.resources() ++ Seq(cl())
      }
    }

    @scala.annotation.tailrec
    def bspTransitiveModuleDeps(todo: List[JavaModule], seen: Set[JavaModule], acc: List[JavaModule]): List[JavaModule] =
      todo match {
        case Nil                                 => acc
        case head :: tail if seen.contains(head) => bspTransitiveModuleDeps(tail, seen, acc)
        case head :: tail                        => bspTransitiveModuleDeps(head.moduleDeps ++: head.compileModuleDeps ++: tail, seen + head, head :: acc)
    }

    def bspTransitiveLocalClasspath(pathsResolver: Task[EvaluatorPathsResolver]): Task[Seq[PathRef]] = {
      // Exclude `this` so as not to include `bspCompileClassesPath`
      val transitiveModuleDeps = bspTransitiveModuleDeps(List.concat(moduleDeps, compileModuleDeps), Set(this), Nil)
      val cps = T.traverse(transitiveModuleDeps)(bspLocalClasspath(_, pathsResolver))
      T.task { cps().flatten[PathRef] }
    }

    val lcp = bspTransitiveLocalClasspath(pathsResolver)
    T.task {
      lcp() ++
        resources() ++
        unmanagedClasspath() ++
        resolvedIvyDeps()
    }
  }

  /**
   * Resolved dependencies based on [[transitiveIvyDeps]] and [[transitiveCompileIvyDeps]].
   */
  def resolvedIvyDeps: T[Agg[PathRef]] = T {
    resolveDeps(T.task {
      transitiveCompileIvyDeps() ++ transitiveIvyDeps()
    })()
  }

  /**
   * All upstream classfiles and resources necessary to build and executable
   * assembly, but without this module's contribution
   */
  def upstreamAssemblyClasspath: T[Agg[PathRef]] = T {
    transitiveLocalClasspath() ++
      unmanagedClasspath() ++
      resolvedRunIvyDeps()
  }

  def resolvedRunIvyDeps: T[Agg[PathRef]] = T {
    resolveDeps(T.task {
      runIvyDeps() ++ transitiveIvyDeps()
    })()
  }

  /**
   * All classfiles and resources from upstream modules and dependencies
   * necessary to run this module's code after compilation
   */
  def runClasspath: T[Seq[PathRef]] = T {
    localClasspath() ++
      upstreamAssemblyClasspath()
  }

  /**
   * Creates a manifest representation which can be modified or replaced
   * The default implementation just adds the `Manifest-Version`, `Main-Class` and `Created-By` attributes
   */
  def manifest: T[Jvm.JarManifest] = T {
    Jvm.createManifest(finalMainClassOpt().toOption)
  }

  /**
   * Build the assembly for upstream dependencies separate from the current
   * classpath
   *
   * This should allow much faster assembly creation in the common case where
   * upstream dependencies do not change
   */
  def upstreamAssembly: T[PathRef] = T {
    createAssembly(
      upstreamAssemblyClasspath().map(_.path),
      manifest(),
      assemblyRules = assemblyRules
    )
  }

  /**
   * An executable uber-jar/assembly containing all the resources and compiled
   * classfiles from this module and all it's upstream modules and dependencies
   */
  def assembly: T[PathRef] = T {
    createAssembly(
      Agg.from(localClasspath().map(_.path)),
      manifest(),
      prependShellScript(),
      Some(upstreamAssembly().path),
      assemblyRules
    )
  }

  /**
   * A jar containing only this module's resources and compiled classfiles,
   * without those from upstream modules and dependencies
   */
  def jar: T[PathRef] = T {
    createJar(
      localClasspath().map(_.path).filter(os.exists),
      manifest()
    )
  }

  /**
   * Additional options to be used by the javadoc tool.
   * You should not set the `-d` setting for specifying the target directory,
   * as that is done in the [[docJar]] target.
   */
  def javadocOptions: T[Seq[String]] = T { Seq[String]() }

  /**
   * Directories to be processed by the API documentation tool.
   *
   * Typically includes the source files to generate documentation from.
   * @see [[docResources]]
   */
  def docSources: Sources = T.sources(allSources())

  /**
   * Extra directories to be copied into the documentation.
   *
   * Typically includes static files such as html and markdown, but depends
   * on the doc tool that is actually used.
   * @see [[docSources]]
   */
  def docResources: Sources = T.sources(millSourcePath / "docs")

  /**
   * The documentation jar, containing all the Javadoc/Scaladoc HTML files, for
   * publishing to Maven Central
   */
  def docJar: T[PathRef] = T[PathRef] {
    val outDir = T.dest

    val javadocDir = outDir / "javadoc"
    os.makeDir.all(javadocDir)

    val files = Lib.findSourceFiles(docSources(), Seq("java"))
    val options = javadocOptions() ++ Seq("-d", javadocDir.toNIO.toString)

    if (files.nonEmpty)
      Jvm.runSubprocess(
        commandArgs = Seq(
          "javadoc"
        ) ++ options ++
          Seq(
            "-classpath",
            compileClasspath()
              .map(_.path)
              .filter(_.ext != "pom")
              .mkString(java.io.File.pathSeparator)
          ) ++
          files.map(_.toString),
        envArgs = Map(),
        workingDir = T.dest
      )

    createJar(Agg(javadocDir))(outDir)
  }

  /**
   * The source jar, containing only source code for publishing to Maven Central
   */
  def sourceJar: Target[PathRef] = T {
    createJar(
      (allSources() ++ resources()).map(_.path).filter(os.exists),
      manifest()
    )
  }

  /**
   * Any command-line parameters you want to pass to the forked JVM under `run`,
   * `test` or `repl`
   */
  def forkArgs: Target[Seq[String]] = T { Seq.empty[String] }

  /**
   * Any environment variables you want to pass to the forked JVM under `run`,
   * `test` or `repl`
   */
  def forkEnv: Target[Map[String, String]] = T.input { T.env }

  /**
   * Builds a command-line "launcher" file that can be used to run this module's
   * code, without the Mill process. Useful for deployment & other places where
   * you do not want a build tool running
   */
  def launcher = T {
    Result.Success(
      Jvm.createLauncher(
        finalMainClass(),
        runClasspath().map(_.path),
        forkArgs()
      )
    )
  }

  /**
   * Task that print the transitive dependency tree to STDOUT.
   * @param inverse Invert the tree representation, so that the root is on the bottom.
   * @param additionalDeps Additional dependency to be included into the tree.
   */
  protected def printDepsTree(inverse: Boolean, additionalDeps: Task[Agg[Dep]]): Task[Unit] =
    T.task {
      val (flattened, resolution) = Lib.resolveDependenciesMetadata(
        repositoriesTask(),
        resolveCoursierDependency().apply(_),
        additionalDeps() ++ transitiveIvyDeps(),
        Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
      )

      println(
        coursier.util.Print.dependencyTree(
          roots = flattened,
          resolution = resolution,
          printExclusions = false,
          reverse = inverse
        )
      )

      Result.Success(())
    }

  /**
   * Command to print the transitive dependency tree to STDOUT.
   *
   * @param inverse Invert the tree representation, so that the root is on the bottom.
   * @param withCompile Include the compile-time only dependencies (`compileIvyDeps`, provided scope) into the tree.
   * @param withRuntime Include the runtime dependencies (`runIvyDeps`, runtime scope) into the tree.
   */
  def ivyDepsTree(
      inverse: Boolean = false,
      withCompile: Boolean = false,
      withRuntime: Boolean = false
  ): Command[Unit] =
    (withCompile, withRuntime) match {
      case (true, true) =>
        T.command {
          printDepsTree(
            inverse,
            T.task {
              transitiveCompileIvyDeps() ++ runIvyDeps()
            }
          )
        }
      case (true, false) =>
        T.command {
          printDepsTree(inverse, transitiveCompileIvyDeps)
        }
      case (false, true) =>
        T.command {
          printDepsTree(inverse, runIvyDeps)
        }
      case _ =>
        T.command {
          printDepsTree(inverse, T.task { Agg.empty[Dep] })
        }
    }

  /** Control whether `run*`-targets should use an args file to pass command line args, if possible. */
  def runUseArgsFile: T[Boolean] = T { scala.util.Properties.isWin }

  /**
   * Runs this module's code in-process within an isolated classloader. This is
   * faster than `run`, but in exchange you have less isolation between runs
   * since the code can dirty the parent Mill process and potentially leave it
   * in a bad state.
   */
  def runLocal(args: String*): Command[Unit] = T.command {
    Jvm.runLocal(
      finalMainClass(),
      runClasspath().map(_.path),
      args
    )
  }

  /**
   * Runs this module's code in a subprocess and waits for it to finish
   */
  def run(args: String*): Command[Unit] = T.command {
    try Result.Success(
      Jvm.runSubprocess(
        finalMainClass(),
        runClasspath().map(_.path),
        forkArgs(),
        forkEnv(),
        args,
        workingDir = forkWorkingDir(),
        useCpPassingJar = runUseArgsFile()
      )
    )
    catch {
      case e: Exception =>
        Result.Failure("subprocess failed")
    }
  }

  private[this] def backgroundSetup(dest: os.Path): (Path, Path, String) = {
    val token = java.util.UUID.randomUUID().toString
    val procId = dest / ".mill-background-process-id"
    val procTombstone = dest / ".mill-background-process-tombstone"
    // The backgrounded subprocesses poll the procId file, and kill themselves
    // when the procId file is deleted. This deletion happens immediately before
    // the body of these commands run, but we cannot be sure the subprocess has
    // had time to notice.
    //
    // To make sure we wait for the previous subprocess to
    // die, we make the subprocess write a tombstone file out when it kills
    // itself due to procId being deleted, and we wait a short time on task-start
    // to see if such a tombstone appears. If a tombstone appears, we can be sure
    // the subprocess has killed itself, and can continue. If a tombstone doesn't
    // appear in a short amount of time, we assume the subprocess exited or was
    // killed via some other means, and continue anyway.
    val start = System.currentTimeMillis()
    while ({
      if (os.exists(procTombstone)) {
        Thread.sleep(10)
        os.remove.all(procTombstone)
        true
      } else {
        Thread.sleep(10)
        System.currentTimeMillis() - start < 100
      }
    }) ()

    os.write(procId, token)
    os.write(procTombstone, token)
    (procId, procTombstone, token)
  }

  /**
   * Runs this module's code in a background process, until it dies or
   * `runBackground` is used again. This lets you continue using Mill while
   * the process is running in the background: editing files, compiling, and
   * only re-starting the background process when you're ready.
   *
   * You can also use `-w foo.runBackground` to make Mill watch for changes
   * and automatically recompile your code & restart the background process
   * when ready. This is useful when working on long-running server processes
   * that would otherwise run forever
   */
  def runBackground(args: String*): Command[Unit] = T.command {
    val (procId, procTombstone, token) = backgroundSetup(T.dest)
    try Result.Success(
      Jvm.runSubprocess(
        "mill.scalalib.backgroundwrapper.BackgroundWrapper",
        (runClasspath() ++ zincWorker.backgroundWrapperClasspath()).map(_.path),
        forkArgs(),
        forkEnv(),
        Seq(procId.toString, procTombstone.toString, token, finalMainClass()) ++ args,
        workingDir = forkWorkingDir(),
        background = true,
        useCpPassingJar = runUseArgsFile()
      )
    )
    catch {
      case e: Exception =>
        Result.Failure("subprocess failed")
    }
  }

  /**
   * Same as `runBackground`, but lets you specify a main class to run
   */
  def runMainBackground(mainClass: String, args: String*): Command[Unit] =
    T.command {
      val (procId, procTombstone, token) = backgroundSetup(T.dest)
      try Result.Success(
        Jvm.runSubprocess(
          "mill.scalalib.backgroundwrapper.BackgroundWrapper",
          (runClasspath() ++ zincWorker.backgroundWrapperClasspath())
            .map(_.path),
          forkArgs(),
          forkEnv(),
          Seq(procId.toString, procTombstone.toString, token, mainClass) ++ args,
          workingDir = forkWorkingDir(),
          background = true,
          useCpPassingJar = runUseArgsFile()
        )
      )
      catch {
        case e: Exception =>
          Result.Failure("subprocess failed")
      }
    }

  /**
   * Same as `runLocal`, but lets you specify a main class to run
   */
  def runMainLocal(mainClass: String, args: String*): Command[Unit] =
    T.command {
      Jvm.runLocal(
        mainClass,
        runClasspath().map(_.path),
        args
      )
    }

  /**
   * Same as `run`, but lets you specify a main class to run
   */
  def runMain(mainClass: String, args: String*): Command[Unit] = T.command {
    try Result.Success(
      Jvm.runSubprocess(
        mainClass,
        runClasspath().map(_.path),
        forkArgs(),
        forkEnv(),
        args,
        workingDir = forkWorkingDir(),
        useCpPassingJar = runUseArgsFile()
      )
    )
    catch {
      case e: Exception =>
        Result.Failure("subprocess failed")
    }
  }

  /**
   * Override this to change the published artifact id.
   * For example, by default a scala module foo.baz might be published as foo-baz_2.12 and a java module would be foo-baz.
   * Setting this to baz would result in a scala artifact baz_2.12 or a java artifact baz.
   */
  def artifactName: T[String] = millModuleSegments.parts.mkString("-")

  /**
   * The exact id of the artifact to be published. You probably don't want to override this.
   * If you want to customize the name of the artifact, override artifactName instead.
   * If you want to customize the scala version in the artifact id, see ScalaModule.artifactScalaVersion
   */
  def artifactId: T[String] = artifactName()

  def forkWorkingDir: Target[Path] = T { T.workspace }

  /**
   * @param all If `true` fetches also source dependencies
   */
  @nowarn("msg=pure expression does nothing")
  override def prepareOffline(all: Flag): Command[Unit] = {
    val tasks =
      if (all.value) Seq(
        resolveDeps(
          T.task {
            transitiveCompileIvyDeps() ++ transitiveIvyDeps()
          },
          sources = true
        ),
        resolveDeps(
          T.task {
            runIvyDeps() ++ transitiveIvyDeps()
          },
          sources = true
        )
      )
      else Seq()

    T.command {
      super.prepareOffline(all)()
      resolvedIvyDeps()
      zincWorker.prepareOffline(all)()
      resolvedRunIvyDeps()
      T.sequence(tasks)()
      ()
    }
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(BspModule.LanguageId.Java),
    canCompile = true,
    canRun = true
  )

}
