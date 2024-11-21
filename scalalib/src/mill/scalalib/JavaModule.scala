package mill
package scalalib

import coursier.core.{Configuration, DependencyManagement, Resolution}
import coursier.parse.JavaOrScalaModule
import coursier.parse.ModuleParser
import coursier.util.ModuleMatcher
import coursier.{Repository, Type}
import mainargs.{Flag, arg}
import mill.Agg
import mill.api.{Ctx, JarManifest, MillException, PathRef, Result, internal}
import mill.define.{Command, ModuleRef, Segment, Task, TaskModule}
import mill.scalalib.internal.ModuleUtils
import mill.scalalib.api.CompilationResult
import mill.scalalib.bsp.{BspBuildTarget, BspModule, BspUri, JvmBuildTarget}
import mill.scalalib.publish.Artifact
import mill.util.Jvm
import os.{Path, ProcessOutput}

import scala.annotation.nowarn

/**
 * Core configuration required to compile a single Java compilation target
 */
trait JavaModule
    extends mill.Module
    with WithZincWorker
    with TestModule.JavaModuleBase
    with TaskModule
    with RunModule
    with GenIdeaModule
    with CoursierModule
    with OfflineSupportModule
    with BspModule
    with SemanticDbJavaModule { outer =>

  override def zincWorker: ModuleRef[ZincWorkerModule] = super.zincWorker
  @nowarn
  type JavaTests = JavaModuleTests
  @deprecated("Use JavaTests instead", since = "Mill 0.11.10")
  trait JavaModuleTests extends JavaModule with TestModule {
    // Run some consistence checks
    hierarchyChecks()

    override def resources = super[JavaModule].resources
    override def moduleDeps: Seq[JavaModule] = Seq(outer)
    override def repositoriesTask: Task[Seq[Repository]] = outer.repositoriesTask
    override def resolutionCustomizer: Task[Option[coursier.Resolution => coursier.Resolution]] =
      outer.resolutionCustomizer
    override def javacOptions: T[Seq[String]] = Task { outer.javacOptions() }
    override def zincWorker: ModuleRef[ZincWorkerModule] = outer.zincWorker
    override def skipIdea: Boolean = outer.skipIdea
    override def runUseArgsFile: T[Boolean] = Task { outer.runUseArgsFile() }
    override def sources = Task.Sources {
      for (src <- outer.sources()) yield {
        PathRef(this.millSourcePath / src.path.relativeTo(outer.millSourcePath))
      }
    }

    /**
     * JavaModule and its derivatives define inner test modules.
     * To avoid unexpected misbehavior due to the use of the wrong inner test trait
     * we apply some hierarchy consistency checks.
     * If, for some reason, those are too restrictive to you, you can override this method.
     * @throws MillException
     */
    protected def hierarchyChecks(): Unit = {
      val outerInnerSets = Seq(
        ("mill.scalajslib.ScalaJSModule", "ScalaJSTests"),
        ("mill.scalanativelib.ScalaNativeModule", "ScalaNativeTests"),
        ("mill.scalalib.SbtModule", "SbtTests"),
        ("mill.scalalib.MavenModule", "MavenTests")
      )
      for {
        (mod, testModShort) <- outerInnerSets
        testMod = s"${mod}$$${testModShort}"
      }
        try {
          if (Class.forName(mod).isInstance(outer) && !Class.forName(testMod).isInstance(this))
            throw new MillException(
              s"$outer is a `${mod}`. $this needs to extend `${testModShort}`."
            )
        } catch {
          case _: ClassNotFoundException => // if we can't find the classes, we certainly are not in a ScalaJSModule
        }
    }
  }

  def defaultCommandName(): String = "run"
  def resolvePublishDependency: Task[Dep => publish.Dependency] = Task.Anon {
    Artifact.fromDepJava(_: Dep)
  }

  /**
   * Allows you to specify an explicit main class to use for the `run` command.
   * If none is specified, the classpath is searched for an appropriate main
   * class to use if one exists
   */
  def mainClass: T[Option[String]] = None

  def finalMainClassOpt: T[Either[String, String]] = Task {
    mainClass() match {
      case Some(m) => Right(m)
      case None =>
        if (zincWorker().javaHome().isDefined) {
          super[RunModule].finalMainClassOpt()
        } else {
          zincWorker().worker().discoverMainClasses(compile()) match {
            case Seq() => Left("No main class specified or found")
            case Seq(main) => Right(main)
            case mains =>
              Left(
                s"Multiple main classes found (${mains.mkString(",")}) " +
                  "please explicitly specify which one to use by overriding `mainClass` " +
                  "or using `runMain <main-class> <...args>` instead of `run`"
              )
          }
        }
    }
  }

  def finalMainClass: T[String] = Task {
    finalMainClassOpt() match {
      case Right(main) => Result.Success(main)
      case Left(msg) => Result.Failure(msg)
    }
  }

  /**
   * Mandatory ivy dependencies that are typically always required and shouldn't be removed by
   * overriding [[ivyDeps]], e.g. the scala-library in the [[ScalaModule]].
   */
  def mandatoryIvyDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  /**
   * Any ivy dependencies you want to add to this Module, in the format
   * ivy"org::name:version" for Scala dependencies or ivy"org:name:version"
   * for Java dependencies
   */
  def ivyDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  /**
   * Aggregation of mandatoryIvyDeps and ivyDeps, with BOMs and dependency management data
   * added to each of them.
   * In most cases, instead of overriding this Target you want to override `ivyDeps` instead.
   */
  def allIvyDeps: T[Agg[Dep]] = Task {
    val bomDeps0 = allBomDeps().toSeq
    val rawDeps = ivyDeps() ++ mandatoryIvyDeps()
    val depsWithBoms =
      if (bomDeps0.isEmpty) rawDeps
      else rawDeps.map(dep => dep.copy(dep = dep.dep.addBoms(bomDeps0)))
    val depMgmt = dependencyManagementDict()
    if (depMgmt.isEmpty)
      depsWithBoms
    else {
      lazy val depMgmtMap = depMgmt.toMap
      depsWithBoms
        .map(dep => dep.copy(dep = dep.dep.withOverrides(dep.dep.overrides ++ depMgmt)))
        .map { dep =>
          val key = DependencyManagement.Key(
            dep.dep.module.organization,
            dep.dep.module.name,
            coursier.core.Type.jar,
            dep.dep.publication.classifier
          )
          val versionOverride =
            if (dep.dep.version == "_") depMgmtMap.get(key).map(_.version)
            else None
          val exclusions = depMgmtMap.get(key)
            .map(_.minimizedExclusions)
            .map(dep.dep.minimizedExclusions.join(_))
            .getOrElse(dep.dep.minimizedExclusions)
          dep.copy(
            dep = dep.dep
              .withVersion(versionOverride.getOrElse(dep.dep.version))
              .withMinimizedExclusions(exclusions)
          )
        }
    }
  }

  /**
   * Same as `ivyDeps`, but only present at compile time. Useful for e.g.
   * macro-related dependencies like `scala-reflect` that doesn't need to be
   * present at runtime
   */
  def compileIvyDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  /**
   * Additional dependencies, only present at runtime. Useful for e.g.
   * selecting different versions of a dependency to use at runtime after your
   * code has already been compiled.
   */
  def runIvyDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  def parentDep: T[Option[Dep]] = Task { None }

  /**
   * Any BOM dependencies you want to add to this Module, in the format
   * ivy"org:name:version"
   */
  def bomDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  def allBomDeps: Task[Agg[(coursier.core.Module, String)]] = Task.Anon {
    val modVerOrMalformed =
      (Agg(parentDep().toSeq: _*) ++ bomDeps()).map(bindDependency()).map { bomDep =>
        val fromModVer = coursier.core.Dependency(bomDep.dep.module, bomDep.dep.version)
          .withConfiguration(coursier.core.Configuration.defaultCompile)
        if (fromModVer == bomDep.dep)
          Right((bomDep.dep.module, bomDep.dep.version))
        else
          Left(bomDep)
      }

    val malformed = modVerOrMalformed.collect {
      case Left(malformedBomDep) =>
        malformedBomDep
    }
    if (malformed.isEmpty)
      modVerOrMalformed.collect {
        case Right(modVer) => modVer
      }
    else
      throw new Exception(
        "Found parent or BOM dependencies with invalid parameters:" + System.lineSeparator() +
          malformed.map("- " + _.dep + System.lineSeparator()).mkString +
          "Only organization, name, and version are accepted."
      )
  }

  /**
   * Dependency management data
   *
   * Versions and exclusions in dependency management override those of transitive dependencies,
   * while they have no effect if the corresponding dependency isn't pulled during dependency
   * resolution.
   *
   * For example, the following forces com.lihaoyi::os-lib to version 0.11.3, and
   * excludes org.slf4j:slf4j-api from com.lihaoyi::cask that it forces to version 0.9.4
   * {{{
   *   def dependencyManagement = super.dependencyManagement() ++ Agg(
   *     ivy"com.lihaoyi::os-lib:0.11.3",
   *     ivy"com.lihaoyi::cask:0.9.4".exclude("org.slf4j", "slf4j-api")
   *   )
   * }}}
   */
  def dependencyManagement: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  /**
   * Data from dependencyManagement, converted to a type ready to be passed to coursier
   * for dependency resolution
   */
  def dependencyManagementDict: Task[Seq[(DependencyManagement.Key, DependencyManagement.Values)]] =
    Task.Anon {
      val keyValuesOrErrors =
        dependencyManagement().toSeq.map(bindDependency()).map(_.dep).map { depMgmt =>
          val fromUsedValues = coursier.core.Dependency(depMgmt.module, depMgmt.version)
            .withPublication(coursier.core.Publication(
              "",
              depMgmt.publication.`type`,
              coursier.core.Extension.empty,
              depMgmt.publication.classifier
            ))
            .withMinimizedExclusions(depMgmt.minimizedExclusions)
            .withOptional(depMgmt.optional)
            .withConfiguration(Configuration.defaultCompile)
          if (fromUsedValues == depMgmt) {
            val key = DependencyManagement.Key(
              depMgmt.module.organization,
              depMgmt.module.name,
              if (depMgmt.publication.`type`.isEmpty) coursier.core.Type.jar
              else depMgmt.publication.`type`,
              depMgmt.publication.classifier
            )
            val values = DependencyManagement.Values(
              Configuration.empty,
              if (depMgmt.version == "_") "" // shouldn't be needed with future coursier versions
              else depMgmt.version,
              depMgmt.minimizedExclusions,
              depMgmt.optional
            )
            Right(key -> values)
          } else
            Left(depMgmt)
        }

      val errors = keyValuesOrErrors.collect {
        case Left(errored) => errored
      }
      if (errors.isEmpty)
        keyValuesOrErrors.collect { case Right(kv) => kv }
      else
        throw new Exception(
          "Found dependency management entries with invalid values. Only organization, name, version, type, classifier, exclusions, and optionality can be specified" + System.lineSeparator() +
            errors.map("- " + _ + System.lineSeparator()).mkString
        )
    }

  /**
   * Default artifact types to fetch and put in the classpath. Add extra types
   * here if you'd like fancy artifact extensions to be fetched.
   */
  def artifactTypes: T[Set[Type]] = Task { coursier.core.Resolution.defaultTypes }

  /**
   * Options to pass to the java compiler
   */
  def javacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Additional options for the java compiler derived from other module settings.
   */
  def mandatoryJavacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   *  The direct dependencies of this module.
   *  This is meant to be overridden to add dependencies.
   *  To read the value, you should use [[moduleDepsChecked]] instead,
   *  which uses a cached result which is also checked to be free of cycle.
   *  @see [[moduleDepschecked]]
   */
  def moduleDeps: Seq[JavaModule] = Seq.empty

  /**
   * Same as [[moduleDeps]] but checked to not contain cycles.
   * Prefer this over using [[moduleDeps]] directly.
   */
  final def moduleDepsChecked: Seq[JavaModule] = {
    // trigger initialization to check for cycles
    recModuleDeps
    moduleDeps
  }

  /**
   * Same as [[moduleDeps]] but checked to not contain cycles.
   * Prefer this over using [[moduleDeps]] directly.
   */
  final def runModuleDepsChecked: Seq[JavaModule] = {
    // trigger initialization to check for cycles
    recRunModuleDeps
    runModuleDeps
  }

  /** Should only be called from [[moduleDepsChecked]] */
  private lazy val recModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (millModuleSegments ++ Seq(Segment.Label("moduleDeps"))).render,
      this,
      _.moduleDeps
    )

  /** Should only be called from [[runModuleDepsChecked]] */
  private lazy val recRunModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (millModuleSegments ++ Seq(Segment.Label("runModuleDeps"))).render,
      this,
      m => m.runModuleDeps ++ m.moduleDeps
    )

  /**
   *  The compile-only direct dependencies of this module. These are *not*
   *  transitive, and only take effect in the module that they are declared in.
   */
  def compileModuleDeps: Seq[JavaModule] = Seq.empty

  /**
   * The runtime-only direct dependencies of this module. These *are* transitive,
   * and so get propagated to downstream modules automatically
   */
  def runModuleDeps: Seq[JavaModule] = Seq.empty

  /** Same as [[compileModuleDeps]] but checked to not contain cycles. */
  final def compileModuleDepsChecked: Seq[JavaModule] = {
    // trigger initialization to check for cycles
    recCompileModuleDeps
    compileModuleDeps
  }

  /** Should only be called from [[compileModuleDeps]] */
  private lazy val recCompileModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (millModuleSegments ++ Seq(Segment.Label("compileModuleDeps"))).render,
      this,
      _.compileModuleDeps
    )

  /** The direct and indirect dependencies of this module */
  def recursiveModuleDeps: Seq[JavaModule] = { recModuleDeps }

  /** The direct and indirect runtime module dependencies of this module */
  def recursiveRunModuleDeps: Seq[JavaModule] = { recRunModuleDeps }

  /**
   * Like `recursiveModuleDeps` but also include the module itself,
   * basically the modules whose classpath are needed at runtime
   */
  def transitiveModuleDeps: Seq[JavaModule] = recursiveModuleDeps ++ Seq(this)

  /**
   * Like `recursiveModuleDeps` but also include the module itself,
   * basically the modules whose classpath are needed at runtime
   */
  def transitiveRunModuleDeps: Seq[JavaModule] = recursiveRunModuleDeps ++ Seq(this)

  /**
   * All direct and indirect module dependencies of this module, including
   * compile-only dependencies: basically the modules whose classpath are needed
   * at compile-time.
   *
   * Note that `compileModuleDeps` are defined to be non-transitive, so we only
   * look at the direct `compileModuleDeps` when assembling this list
   */
  def transitiveModuleCompileModuleDeps: Seq[JavaModule] = {
    (moduleDepsChecked ++ compileModuleDepsChecked).flatMap(_.transitiveModuleDeps).distinct
  }

  /**
   * All direct and indirect module dependencies of this module, including
   * compile-only dependencies: basically the modules whose classpath are needed
   * at runtime.
   *
   * Note that `runModuleDeps` are defined to be transitive
   */
  def transitiveModuleRunModuleDeps: Seq[JavaModule] = {
    (runModuleDepsChecked ++ moduleDepsChecked).flatMap(_.transitiveRunModuleDeps).distinct
  }

  /** The compile-only transitive ivy dependencies of this module and all it's upstream compile-only modules. */
  def transitiveCompileIvyDeps: T[Agg[BoundDep]] = Task {
    // We never include compile-only dependencies transitively, but we must include normal transitive dependencies!
    compileIvyDeps().map(bindDependency()) ++
      T.traverse(compileModuleDepsChecked)(_.transitiveIvyDeps)().flatten
  }

  /**
   * Show the module dependencies.
   * @param recursive If `true` include all recursive module dependencies, else only show direct dependencies.
   */
  def showModuleDeps(recursive: Boolean = false): Command[Unit] = Task.Command {
    val normalDeps = if (recursive) recursiveModuleDeps else moduleDepsChecked
    val compileDeps =
      if (recursive) compileModuleDepsChecked.flatMap(_.transitiveModuleDeps).distinct
      else compileModuleDepsChecked
    val deps = (normalDeps ++ compileDeps).distinct
    val asString =
      s"${
          if (recursive) "Recursive module"
          else "Module"
        } dependencies of ${millModuleSegments.render}:\n\t${deps
          .map { dep =>
            dep.millModuleSegments.render ++
              (if (compileModuleDepsChecked.contains(dep) || !normalDeps.contains(dep)) " (compile)"
               else "")
          }
          .mkString("\n\t")}"
    T.log.outputStream.println(asString)
  }

  /**
   * Additional jars, classfiles or resources to add to the classpath directly
   * from disk rather than being downloaded from Maven Central or other package
   * repositories
   */
  def unmanagedClasspath: T[Agg[PathRef]] = Task { Agg.empty[PathRef] }

  /**
   * The transitive ivy dependencies of this module and all it's upstream modules.
   * This is calculated from [[ivyDeps]], [[mandatoryIvyDeps]] and recursively from [[moduleDeps]].
   */
  def transitiveIvyDeps: T[Agg[BoundDep]] = Task {
    allIvyDeps().map(bindDependency()) ++
      T.traverse(moduleDepsChecked)(_.transitiveIvyDeps)().flatten
  }

  /**
   * The transitive run ivy dependencies of this module and all it's upstream modules.
   * This is calculated from [[runIvyDeps]], [[mandatoryIvyDeps]] and recursively from [[moduleDeps]].
   */
  def transitiveRunIvyDeps: T[Agg[BoundDep]] = Task {
    runIvyDeps().map(bindDependency()) ++
      T.traverse(moduleDepsChecked)(_.transitiveRunIvyDeps)().flatten ++
      T.traverse(runModuleDepsChecked)(_.transitiveIvyDeps)().flatten ++
      T.traverse(runModuleDepsChecked)(_.transitiveRunIvyDeps)().flatten
  }

  /**
   * The upstream compilation output of all this module's upstream modules
   */
  def upstreamCompileOutput: T[Seq[CompilationResult]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps)(_.compile)
  }

  /**
   * The transitive version of `localClasspath`
   */
  def transitiveLocalClasspath: T[Agg[PathRef]] = Task {
    T.traverse(transitiveModuleRunModuleDeps)(_.localClasspath)().flatten
  }

  /**
   * Same as [[transitiveLocalClasspath]], but with all dependencies on [[compile]]
   * replaced by their non-compiling [[bspCompileClassesPath]] variants.
   *
   * Keep in sync with [[transitiveLocalClasspath]]
   */
  @internal
  def bspTransitiveLocalClasspath: T[Agg[UnresolvedPath]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps)(_.bspLocalClasspath)().flatten
  }

  /**
   * The transitive version of `compileClasspath`
   */
  def transitiveCompileClasspath: T[Agg[PathRef]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon { m.localCompileClasspath() ++ Agg(m.compile().classes) }
    )().flatten
  }

  /**
   * Same as [[transitiveCompileClasspath]], but with all dependencies on [[compile]]
   * replaced by their non-compiling [[bspCompileClassesPath]] variants.
   *
   * Keep in sync with [[transitiveCompileClasspath]]
   */
  @internal
  def bspTransitiveCompileClasspath: T[Agg[UnresolvedPath]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon {
        m.localCompileClasspath().map(p => UnresolvedPath.ResolvedPath(p.path)) ++
          Agg(m.bspCompileClassesPath())
      }
    )()
      .flatten
  }

  /**
   * What platform suffix to use for publishing, e.g. `_sjs` for Scala.js
   * projects
   */
  def platformSuffix: T[String] = Task { "" }

  /**
   * What shell script to use to launch the executable generated by `assembly`.
   * Defaults to a generic "universal" launcher that should work for Windows,
   * OS-X and Linux
   */
  def prependShellScript: T[String] = Task {
    finalMainClassOpt().toOption match {
      case None => ""
      case Some(cls) =>
        mill.util.Jvm.launcherUniversalScript(
          mainClass = cls,
          shellClassPath = Agg("$0"),
          cmdClassPath = Agg("%~dpnx0"),
          jvmArgs = forkArgs()
        )
    }
  }

  /**
   * Configuration for the [[assembly]] task: how files and file-conflicts are
   * managed when combining multiple jar files into one big assembly jar.
   */
  def assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules

  /**
   * The folders where the source files for this module live
   */
  def sources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "src" }

  /**
   * The folders where the resource files for this module live.
   * If you need resources to be seen by the compiler, use [[compileResources]].
   */
  def resources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "resources" }

  /**
   * The folders where the compile time resource files for this module live.
   * If your resources files do not necessarily need to be seen by the compiler,
   * you should use [[resources]] instead.
   */
  def compileResources: T[Seq[PathRef]] = Task.Sources { millSourcePath / "compile-resources" }

  /**
   * Folders containing source files that are generated rather than
   * handwritten; these files can be generated in this target itself,
   * or can refer to files generated from other targets
   */
  def generatedSources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * The folders containing all source files fed into the compiler
   */
  def allSources: T[Seq[PathRef]] = Task { sources() ++ generatedSources() }

  /**
   * All individual source files fed into the Java compiler
   */
  def allSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("java")).map(PathRef(_))
  }

  /**
   * If `true`, we always show problems (errors, warnings, infos) found in all source files, even when they have not changed since the previous incremental compilation.
   * When `false`, we report only problems for files which we re-compiled.
   */
  def zincReportCachedProblems: T[Boolean] = Task.Input {
    sys.props.getOrElse(
      "mill.scalalib.JavaModule.zincReportCachedProblems",
      "false"
    ).equalsIgnoreCase("true")
  }

  def zincIncrementalCompilation: T[Boolean] = Task {
    true
  }

  /**
   * Compiles the current module to generate compiled classfiles/bytecode.
   *
   * When you override this, you probably also want/need to override [[bspCompileClassesPath]],
   * as that needs to point to the same compilation output path.
   *
   * Keep in sync with [[bspCompileClassesPath]]
   */
  def compile: T[mill.scalalib.api.CompilationResult] = Task(persistent = true) {
    zincWorker()
      .worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = allSourceFiles().map(_.path),
        compileClasspath = compileClasspath().map(_.path),
        javacOptions = javacOptions() ++ mandatoryJavacOptions(),
        reporter = T.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      )
  }

  /**
   * The path to the compiled classes by [[compile]] without forcing to actually run the compilation.
   * This is safe in an BSP context, as the compilation done later will use the
   * exact same compilation settings, so we can safely use the same path.
   *
   * Keep in sync with [[compile]]
   */
  @internal
  def bspCompileClassesPath: T[UnresolvedPath] =
    if (compile.ctx.enclosing == s"${classOf[JavaModule].getName}#compile") {
      Task {
        T.log.debug(
          s"compile target was not overridden, assuming hard-coded classes directory for target ${compile}"
        )
        UnresolvedPath.DestPath(os.sub / "classes", compile.ctx.segments, compile.ctx.foreign)
      }
    } else {
      Task {
        T.log.debug(
          s"compile target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compile}"
        )
        UnresolvedPath.ResolvedPath(compile().classes.path)
      }
    }

  /**
   * The part of the [[localClasspath]] which is available "after compilation".
   *
   * Keep in sync with [[bspLocalRunClasspath]]
   */
  override def localRunClasspath: T[Seq[PathRef]] = Task {
    super.localRunClasspath() ++ resources() ++
      Agg(compile().classes)
  }

  /**
   * Same as [[localRunClasspath]] but for use in BSP server.
   *
   * Keep in sync with [[localRunClasspath]]
   */
  def bspLocalRunClasspath: T[Agg[UnresolvedPath]] = Task {
    Agg.from(super.localRunClasspath() ++ resources())
      .map(p => UnresolvedPath.ResolvedPath(p.path)) ++
      Agg(bspCompileClassesPath())
  }

  /**
   * The *output* classfiles/resources from this module, used for execution,
   * excluding upstream modules and third-party dependencies, but including unmanaged dependencies.
   *
   * This is build from [[localCompileClasspath]] and [[localRunClasspath]]
   * as the parts available "before compilation" and "after compilation".
   *
   * Keep in sync with [[bspLocalClasspath]]
   */
  def localClasspath: T[Seq[PathRef]] = Task {
    localCompileClasspath().toSeq ++ localRunClasspath()
  }

  /**
   * Same as [[localClasspath]], but with all dependencies on [[compile]]
   * replaced by their non-compiling [[bspCompileClassesPath]] variants.
   *
   * Keep in sync with [[localClasspath]]
   */
  @internal
  def bspLocalClasspath: T[Agg[UnresolvedPath]] = Task {
    (localCompileClasspath()).map(p => UnresolvedPath.ResolvedPath(p.path)) ++
      bspLocalRunClasspath()
  }

  /**
   * All classfiles and resources from upstream modules and dependencies
   * necessary to compile this module.
   *
   * Keep in sync with [[bspCompileClasspath]]
   */
  def compileClasspath: T[Agg[PathRef]] = Task {
    resolvedIvyDeps() ++ transitiveCompileClasspath() ++ localCompileClasspath()
  }

  /**
   * Same as [[compileClasspath]], but does not trigger compilation targets, if possible.
   *
   * Keep in sync with [[compileClasspath]]
   */
  @internal
  def bspCompileClasspath: T[Agg[UnresolvedPath]] = Task {
    resolvedIvyDeps().map(p => UnresolvedPath.ResolvedPath(p.path)) ++
      bspTransitiveCompileClasspath() ++
      localCompileClasspath().map(p => UnresolvedPath.ResolvedPath(p.path))
  }

  /**
   * The *input* classfiles/resources from this module, used during compilation,
   * excluding upstream modules and third-party dependencies
   */
  def localCompileClasspath: T[Agg[PathRef]] = Task {
    compileResources() ++ unmanagedClasspath()
  }

  /**
   * Resolved dependencies based on [[transitiveIvyDeps]] and [[transitiveCompileIvyDeps]].
   */
  def resolvedIvyDeps: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      transitiveCompileIvyDeps() ++ transitiveIvyDeps(),
      artifactTypes = Some(artifactTypes()),
      bomDeps = allBomDeps()
    )
  }

  /**
   * All upstream classfiles and resources necessary to build and executable
   * assembly, but without this module's contribution
   */
  def upstreamAssemblyClasspath: T[Agg[PathRef]] = Task {
    resolvedRunIvyDeps() ++ transitiveLocalClasspath()
  }

  def resolvedRunIvyDeps: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      transitiveRunIvyDeps() ++ transitiveIvyDeps(),
      artifactTypes = Some(artifactTypes()),
      bomDeps = allBomDeps()
    )
  }

  /**
   * All classfiles and resources from upstream modules and dependencies
   * necessary to run this module's code after compilation
   */
  override def runClasspath: T[Seq[PathRef]] = Task {
    super.runClasspath() ++
      resolvedRunIvyDeps().toSeq ++
      transitiveLocalClasspath() ++
      localClasspath()
  }

  /**
   * Creates a manifest representation which can be modified or replaced
   * The default implementation just adds the `Manifest-Version`, `Main-Class` and `Created-By` attributes
   */
  def manifest: T[JarManifest] = Task {
    Jvm.createManifest(finalMainClassOpt().toOption)
  }

  /**
   * Build the assembly for upstream dependencies separate from the current
   * classpath
   *
   * This should allow much faster assembly creation in the common case where
   * upstream dependencies do not change
   *
   * This implementation is deprecated because of it's return value.
   * Please use [[upstreamAssembly2]] instead.
   */
  @deprecated("Use upstreamAssembly2 instead, which has a richer return value", "Mill 0.11.8")
  def upstreamAssembly: T[PathRef] = Task {
    T.log.error(
      s"upstreamAssembly target is deprecated and should no longer used." +
        s" Please make sure to use upstreamAssembly2 instead."
    )
    upstreamAssembly2().pathRef
  }

  /**
   * Build the assembly for upstream dependencies separate from the current
   * classpath
   *
   * This should allow much faster assembly creation in the common case where
   * upstream dependencies do not change
   */
  def upstreamAssembly2: T[Assembly] = Task {
    Assembly.create(
      destJar = T.dest / "out.jar",
      inputPaths = upstreamAssemblyClasspath().map(_.path),
      manifest = manifest(),
      assemblyRules = assemblyRules
    )
  }

  /**
   * An executable uber-jar/assembly containing all the resources and compiled
   * classfiles from this module and all it's upstream modules and dependencies
   */
  def assembly: T[PathRef] = Task {
    // detect potential inconsistencies due to `upstreamAssembly` deprecation after 0.11.7
    if (
      (upstreamAssembly.ctx.enclosing: @nowarn) != s"${classOf[JavaModule].getName}#upstreamAssembly"
    ) {
      T.log.error(
        s"${upstreamAssembly.ctx.enclosing: @nowarn} is overriding a deprecated target which is no longer used." +
          s" Please make sure to override upstreamAssembly2 instead."
      )
    }

    val prependScript = Option(prependShellScript()).filter(_ != "")
    val upstream = upstreamAssembly2()

    val created = Assembly.create(
      destJar = T.dest / "out.jar",
      Agg.from(localClasspath().map(_.path)),
      manifest(),
      prependScript,
      Some(upstream.pathRef.path),
      assemblyRules
    )
    // See https://github.com/com-lihaoyi/mill/pull/2655#issuecomment-1672468284
    val problematicEntryCount = 65535
    if (
      prependScript.isDefined &&
      (upstream.addedEntries + created.addedEntries) > problematicEntryCount
    ) {
      Result.Failure(
        s"""The created assembly jar contains more than ${problematicEntryCount} ZIP entries.
           |JARs of that size are known to not work correctly with a prepended shell script.
           |Either reduce the entries count of the assembly or disable the prepended shell script with:
           |
           |  def prependShellScript = ""
           |""".stripMargin,
        Some(created.pathRef)
      )
    } else {
      Result.Success(created.pathRef)
    }
  }

  /**
   * A jar containing only this module's resources and compiled classfiles,
   * without those from upstream modules and dependencies
   */
  def jar: T[PathRef] = Task {
    Jvm.createJar(localClasspath().map(_.path).filter(os.exists), manifest())
  }

  /**
   * Additional options to be used by the javadoc tool.
   * You should not set the `-d` setting for specifying the target directory,
   * as that is done in the [[docJar]] target.
   */
  def javadocOptions: T[Seq[String]] = Task { Seq[String]() }

  /**
   * Directories to be processed by the API documentation tool.
   *
   * Typically, includes the source files to generate documentation from.
   * @see [[docResources]]
   */
  def docSources: T[Seq[PathRef]] = Task.Sources(allSources())

  /**
   * Extra directories to be copied into the documentation.
   *
   * Typically, includes static files such as html and markdown, but depends
   * on the doc tool that is actually used.
   * @see [[docSources]]
   */
  def docResources: T[Seq[PathRef]] = Task.Sources(millSourcePath / "docs")

  /**
   * Control whether `docJar`-target should use a file to pass command line arguments to the javadoc tool.
   * Defaults to `true` on Windows.
   * Beware: Using an args-file is probably not supported for very old javadoc versions.
   */
  def docJarUseArgsFile: T[Boolean] = Task { scala.util.Properties.isWin }

  /**
   * The documentation jar, containing all the Javadoc/Scaladoc HTML files, for
   * publishing to Maven Central
   */
  def docJar: T[PathRef] = T[PathRef] {
    val outDir = T.dest

    val javadocDir = outDir / "javadoc"
    os.makeDir.all(javadocDir)

    val files = Lib.findSourceFiles(docSources(), Seq("java"))

    if (files.nonEmpty) {
      val classPath = compileClasspath().iterator.map(_.path).filter(_.ext != "pom").toSeq
      val cpOptions =
        if (classPath.isEmpty) Seq()
        else Seq(
          "-classpath",
          classPath.mkString(java.io.File.pathSeparator)
        )

      val options = javadocOptions() ++
        Seq("-d", javadocDir.toString) ++
        cpOptions ++
        files.map(_.toString)

      val cmdArgs =
        if (docJarUseArgsFile()) {
          val content = options.map(s =>
            // make sure we properly mask backslashes (path separators on Windows)
            s""""${s.replace("\\", "\\\\")}""""
          ).mkString(" ")
          val argsFile = os.temp(
            contents = content,
            prefix = "javadoc-",
            deleteOnExit = false,
            dir = outDir
          )
          T.log.debug(
            s"Creating javadoc options file @${argsFile} ..."
          )
          Seq(s"@${argsFile}")
        } else {
          options
        }

      T.log.info("options: " + cmdArgs)

      Jvm.runSubprocess(
        commandArgs = Seq(Jvm.jdkTool("javadoc")) ++ cmdArgs,
        envArgs = Map(),
        workingDir = T.dest
      )
    }

    Jvm.createJar(Agg(javadocDir))(outDir)
  }

  /**
   * The source jar, containing only source code for publishing to Maven Central
   */
  def sourceJar: T[PathRef] = Task {
    Jvm.createJar(
      (allSources() ++ resources() ++ compileResources()).map(_.path).filter(os.exists),
      manifest()
    )
  }

  /**
   * Any command-line parameters you want to pass to the forked JVM under `run`,
   * `test` or `repl`
   */
  override def forkArgs: T[Seq[String]] = Task {
    // overridden here for binary compatibility (0.11.x)
    super.forkArgs()
  }

  /**
   * Any environment variables you want to pass to the forked JVM under `run`,
   * `test` or `repl`
   */
  override def forkEnv: T[Map[String, String]] = Task {
    // overridden here for binary compatibility (0.11.x)
    super.forkEnv()
  }

  /**
   * Builds a command-line "launcher" file that can be used to run this module's
   * code, without the Mill process. Useful for deployment & other places where
   * you do not want a build tool running
   */
  def launcher = Task {
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
   * NOTE: that when `whatDependsOn` is used with `inverse` it will just
   *       be ignored since when using `whatDependsOn` the tree _must_ be
   *       inversed to work, so this will always be set as true.
   * @param inverse Invert the tree representation, so that the root is on the bottom.
   * @param additionalDeps Additional dependency to be included into the tree.
   * @param whatDependsOn possible list of modules to target in the tree in order to see
   *                      where a dependency stems from.
   */
  protected def printDepsTree(
      inverse: Boolean,
      additionalDeps: Task[Agg[BoundDep]],
      whatDependsOn: List[JavaOrScalaModule]
  ): Task[Unit] =
    Task.Anon {
      val dependencies = (additionalDeps() ++ transitiveIvyDeps()).iterator.to(Seq)
      val resolution: Resolution = Lib.resolveDependenciesMetadataSafe(
        repositoriesTask(),
        dependencies,
        Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
        resolutionParams = resolutionParams(),
        bomDeps = allBomDeps()
      ).getOrThrow

      val roots = whatDependsOn match {
        case List() => dependencies.map(_.dep)
        case _ =>
          // We don't really care what scalaVersions is set as here since the user
          // will be passing in `_2.13` or `._3` anyways. Or it may even be a java
          // dependency. Looking at the usage upstream, it seems that this is set if
          // it can be or else defaults to "". Using it, I haven't been able to see
          // any difference whether it's set, and by using "" it greatly simplifies
          // it.
          val matchers = whatDependsOn
            .map(module => module.module(scalaVersion = ""))
            .map(module => ModuleMatcher(module))

          resolution.minDependencies
            .filter(dep => matchers.exists(matcher => matcher.matches(dep.module))).toSeq
      }

      println(
        coursier.util.Print.dependencyTree(
          resolution = resolution,
          roots = roots,
          printExclusions = false,
          reverse = if (whatDependsOn.isEmpty) inverse else true
        )
      )

      Result.Success(())
    }

  /**
   * Command to print the transitive dependency tree to STDOUT.
   */
  def ivyDepsTree(args: IvyDepsTreeArgs = IvyDepsTreeArgs()): Command[Unit] = {

    val (invalidModules, validModules) =
      args.whatDependsOn.map(ModuleParser.javaOrScalaModule(_)).partitionMap(identity)

    if (invalidModules.isEmpty) {
      (args.withCompile, args.withRuntime) match {
        case (Flag(true), Flag(true)) =>
          Task.Command {
            printDepsTree(
              args.inverse.value,
              Task.Anon {
                transitiveCompileIvyDeps() ++ transitiveRunIvyDeps()
              },
              validModules
            )()
          }
        case (Flag(true), Flag(false)) =>
          Task.Command {
            printDepsTree(args.inverse.value, transitiveCompileIvyDeps, validModules)()
          }
        case (Flag(false), Flag(true)) =>
          Task.Command {
            printDepsTree(
              args.inverse.value,
              Task.Anon { transitiveRunIvyDeps() },
              validModules
            )()
          }
        case _ =>
          Task.Command {
            printDepsTree(args.inverse.value, Task.Anon { Agg.empty[BoundDep] }, validModules)()
          }
      }
    } else {
      Task.Command {
        val msg = invalidModules.mkString("\n")
        Result.Failure[Unit](msg)
      }
    }
  }

  override def runUseArgsFile: T[Boolean] = Task {
    // overridden here for binary compatibility (0.11.x)
    super.runUseArgsFile()
  }

  override def runLocal(args: Task[Args] = Task.Anon(Args())): Command[Unit] = {
    // overridden here for binary compatibility (0.11.x)
    super.runLocal(args)
  }

  override def run(args: Task[Args] = Task.Anon(Args())): Command[Unit] = {
    // overridden here for binary compatibility (0.11.x)
    super.run(args)
  }

  @deprecated("Binary compat shim, use `.runner().run(..., background=true)`", "Mill 0.12.0")
  override protected def doRunBackground(
      taskDest: Path,
      runClasspath: Seq[PathRef],
      zwBackgroundWrapperClasspath: Agg[PathRef],
      forkArgs: Seq[String],
      forkEnv: Map[String, String],
      finalMainClass: String,
      forkWorkingDir: Path,
      runUseArgsFile: Boolean,
      backgroundOutputs: Option[Tuple2[ProcessOutput, ProcessOutput]]
  )(args: String*): Ctx => Result[Unit] = {
    // overridden here for binary compatibility (0.11.x)
    super.doRunBackground(
      taskDest,
      runClasspath,
      zwBackgroundWrapperClasspath,
      forkArgs,
      forkEnv,
      finalMainClass,
      forkWorkingDir,
      runUseArgsFile,
      backgroundOutputs
    )(args: _*)
  }

  override def runBackgroundLogToConsole: Boolean = {
    // overridden here for binary compatibility (0.11.x)
    super.runBackgroundLogToConsole
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
  def runBackground(args: String*): Command[Unit] = {
    val task = runBackgroundTask(finalMainClass, Task.Anon { Args(args) })
    Task.Command { task() }
  }

  /**
   * Same as `runBackground`, but lets you specify a main class to run
   */
  override def runMainBackground(
      @arg(positional = true) mainClass: String,
      args: String*
  ): Command[Unit] = {
    // overridden here for binary compatibility (0.11.x)
    super.runMainBackground(mainClass, args: _*)
  }

  /**
   * Same as `runLocal`, but lets you specify a main class to run
   */
  override def runMainLocal(
      @arg(positional = true) mainClass: String,
      args: String*
  ): Command[Unit] = {
    // overridden here for binary compatibility (0.11.x)
    super.runMainLocal(mainClass, args: _*)
  }

  /**
   * Same as `run`, but lets you specify a main class to run
   */
  override def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] = {
    // overridden here for binary compatibility (0.11.x)
    super.runMain(mainClass, args: _*)
  }

  /**
   * Override this to change the published artifact id.
   * For example, by default a scala module foo.baz might be published as foo-baz_2.12 and a java module would be foo-baz.
   * Setting this to baz would result in a scala artifact baz_2.12 or a java artifact baz.
   */
  def artifactName: T[String] = artifactNameParts().mkString("-")

  def artifactNameParts: T[Seq[String]] = millModuleSegments.parts

  /**
   * The exact id of the artifact to be published. You probably don't want to override this.
   * If you want to customize the name of the artifact, override artifactName instead.
   * If you want to customize the scala version in the artifact id, see ScalaModule.artifactScalaVersion
   */
  def artifactId: T[String] = artifactName() + artifactSuffix()

  /**
   * The suffix appended to the artifact IDs during publishing
   */
  def artifactSuffix: T[String] = platformSuffix()

  override def forkWorkingDir: T[Path] = Task {
    // overridden here for binary compatibility (0.11.x)
    super.forkWorkingDir()
  }

  /**
   * Files extensions that need to be managed by Zinc together with class files.
   * This means, if zinc needs to remove a class file, it will also remove files
   * which match the class file basename and a listed file extension.
   */
  def zincAuxiliaryClassFileExtensions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * @param all If `true` fetches also source dependencies
   */
  override def prepareOffline(all: Flag): Command[Unit] = {
    val tasks =
      if (all.value) Seq(
        Task.Anon {
          defaultResolver().resolveDeps(
            transitiveCompileIvyDeps() ++ transitiveIvyDeps(),
            sources = true,
            bomDeps = allBomDeps()
          )
        },
        Task.Anon {
          defaultResolver().resolveDeps(
            transitiveRunIvyDeps() ++ transitiveIvyDeps(),
            sources = true,
            bomDeps = allBomDeps()
          )
        }
      )
      else Seq()

    Task.Command {
      super.prepareOffline(all)()
      resolvedIvyDeps()
      zincWorker().prepareOffline(all)()
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

  @internal
  @deprecated("Use bspJvmBuildTargetTask instead", "0.12.3")
  def bspJvmBuildTarget: JvmBuildTarget =
    JvmBuildTarget(
      javaHome = Option(System.getProperty("java.home")).map(p => BspUri(os.Path(p))),
      javaVersion = Option(System.getProperty("java.version"))
    )

  @internal
  def bspJvmBuildTargetTask: Task[JvmBuildTarget] = Task.Anon {
    JvmBuildTarget(
      javaHome = zincWorker()
        .javaHome()
        .map(p => BspUri(p.path))
        .orElse(Option(System.getProperty("java.home")).map(p => BspUri(os.Path(p)))),
      javaVersion = Option(System.getProperty("java.version"))
    )
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon {
    Some((JvmBuildTarget.dataKind, bspJvmBuildTargetTask()))
  }
}
