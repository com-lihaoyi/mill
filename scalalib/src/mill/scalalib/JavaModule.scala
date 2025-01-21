package mill
package scalalib

import coursier.{core => cs}
import coursier.core.{BomDependency, Configuration, DependencyManagement, Resolution}
import coursier.params.ResolutionParams
import coursier.parse.JavaOrScalaModule
import coursier.parse.ModuleParser
import coursier.util.{EitherT, ModuleMatcher, Monad}
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
    with SemanticDbJavaModule
    with AssemblyModule { outer =>

  override def zincWorker: ModuleRef[ZincWorkerModule] = super.zincWorker
  @nowarn
  type JavaTests = JavaModuleTests
  @deprecated("Use JavaTests instead", since = "Mill 0.11.10")
  trait JavaModuleTests extends JavaModule with TestModule {
    // Run some consistence checks
    hierarchyChecks()

    override def resources = super[JavaModule].resources
    override def moduleDeps: Seq[JavaModule] = Seq(outer)
    override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
      internalRepositories() ++ outer.repositoriesTask()
    }
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

    override def bomIvyDeps = Task.Anon[Agg[Dep]] {
      // FIXME Add that back when we can break bin-compat
      // super.bomIvyDeps() ++
      outer.bomIvyDeps()
    }
    override def depManagement = Task.Anon[Agg[Dep]] {
      // FIXME Add that back when we can break bin-compat
      // super.depManagement() ++
      outer.depManagement()
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
   * Aggregation of mandatoryIvyDeps and ivyDeps.
   * In most cases, instead of overriding this Target you want to override `ivyDeps` instead.
   */
  def allIvyDeps: T[Agg[Dep]] = Task { ivyDeps() ++ mandatoryIvyDeps() }

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

  /**
   * Any BOM dependencies you want to add to this Module, in the format
   * ivy"org:name:version"
   */
  def bomIvyDeps: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  def allBomDeps: Task[Agg[BomDependency]] = Task.Anon {
    val modVerOrMalformed =
      bomIvyDeps().map(bindDependency()).map { bomDep =>
        val fromModVer = coursier.core.Dependency(bomDep.dep.module, bomDep.dep.version)
        if (fromModVer == bomDep.dep)
          Right(bomDep.dep.asBomDependency)
        else
          Left(bomDep)
      }

    val malformed = modVerOrMalformed.collect {
      case Left(malformedBomDep) =>
        malformedBomDep
    }
    if (malformed.isEmpty)
      modVerOrMalformed.collect {
        case Right(bomDep) => bomDep
      }
    else
      throw new Exception(
        "Found BOM dependencies with invalid parameters:" + System.lineSeparator() +
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
   *   def depManagement = super.depManagement() ++ Agg(
   *     ivy"com.lihaoyi::os-lib:0.11.3",
   *     ivy"com.lihaoyi::cask:0.9.5".exclude("org.slf4j", "slf4j-api")
   *   )
   * }}}
   */
  def depManagement: T[Agg[Dep]] = Task { Agg.empty[Dep] }

  /**
   * Data from depManagement, converted to a type ready to be passed to coursier
   * for dependency resolution
   */
  protected def processedDependencyManagement(deps: Seq[coursier.core.Dependency])
      : Seq[(DependencyManagement.Key, DependencyManagement.Values)] = {
    val keyValuesOrErrors =
      deps.map { depMgmt =>
        val fromUsedValues = coursier.core.Dependency(depMgmt.module, depMgmt.version)
          .withPublication(coursier.core.Publication(
            "",
            depMgmt.publication.`type`,
            coursier.core.Extension.empty,
            depMgmt.publication.classifier
          ))
          .withMinimizedExclusions(depMgmt.minimizedExclusions)
          .withOptional(depMgmt.optional)
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
            depMgmt.version,
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
   *  The compile-only direct dependencies of this module. These are *not*
   *  transitive, and only take effect in the module that they are declared in.
   */
  def compileModuleDeps: Seq[JavaModule] = Seq.empty

  /**
   * The runtime-only direct dependencies of this module. These *are* transitive,
   * and so get propagated to downstream modules automatically
   */
  def runModuleDeps: Seq[JavaModule] = Seq.empty

  /**
   * Same as [[moduleDeps]] but checked to not contain cycles.
   * Prefer this over using [[moduleDeps]] directly.
   */
  final def moduleDepsChecked: Seq[JavaModule] = {
    // trigger initialization to check for cycles
    recModuleDeps
    moduleDeps
  }

  /** Same as [[compileModuleDeps]] but checked to not contain cycles. */
  final def compileModuleDepsChecked: Seq[JavaModule] = {
    // trigger initialization to check for cycles
    recCompileModuleDeps
    compileModuleDeps
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

  /** Should only be called from [[compileModuleDeps]] */
  private lazy val recCompileModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (millModuleSegments ++ Seq(Segment.Label("compileModuleDeps"))).render,
      this,
      _.compileModuleDeps
    )

  /** Should only be called from [[runModuleDepsChecked]] */
  private lazy val recRunModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (millModuleSegments ++ Seq(Segment.Label("runModuleDeps"))).render,
      this,
      m => m.runModuleDeps ++ m.moduleDeps
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
   * The `coursier.Dependency` to use to refer to this module
   */
  def coursierDependency: cs.Dependency =
    // this is a simple def, and not a Task, as this is simple enough and needed in places
    // where eval'ing a Task would be impractical or not allowed
    cs.Dependency(
      cs.Module(
        JavaModule.internalOrg,
        coursier.core.ModuleName(millModuleSegments.parts.mkString("-")),
        Map.empty
      ),
      JavaModule.internalVersion
    ).withConfiguration(cs.Configuration.compile)

  /**
   * The `coursier.Project` corresponding to this `JavaModule`.
   *
   * This provides details about this module to the coursier resolver (details such as
   * dependencies, BOM dependencies, dependency management, etc.). Beyond more general
   * resolution parameters (such as artifact types, etc.), this should be the only way
   * we provide details about this module to coursier.
   */
  def coursierProject: Task[cs.Project] = Task.Anon {
    coursierProject0()
  }

  private[mill] def coursierProject0: Task[cs.Project] = Task.Anon {

    // Tells coursier that if something depends on a given scope of ours, we should also
    // pull other scopes of our own dependencies.
    //
    // E.g. scopes(runtime) contains compile, so depending on us as a runtime dependency
    // will not only pull our runtime dependencies, but also our compile ones.
    //
    // This is the default scope mapping used in coursier for Maven dependencies, but for
    // one scope: provided. By default in Maven, depending on a dependency as provided
    // doesn't pull anything. Here, by explicitly adding provided to the values,
    // we make coursier pull our own provided dependencies.
    //
    // Note that this is kind of a hack: by default, pulling a dependency in scope A
    // pulls its scope A dependencies. But this is withheld for provided, unless it's
    // added back explicitly like we do here.
    val scopes = Map(
      cs.Configuration.compile -> Seq.empty,
      cs.Configuration.runtime -> Seq(cs.Configuration.compile),
      cs.Configuration.default -> Seq(cs.Configuration.runtime),
      cs.Configuration.test -> Seq(cs.Configuration.runtime),
      // hack, so that depending on `coursierDependency.withConfiguration(Configuration.provided)`
      // pulls our provided dependencies (rather than nothing)
      cs.Configuration.provided -> Seq(cs.Configuration.provided)
    )

    val internalDependencies =
      moduleDepsChecked.flatMap { modDep =>
        // Standard dependencies
        // We pull their compile scope when our compile scope is asked,
        // and pull their runtime scope when our runtime scope is asked.
        Seq(
          (
            cs.Configuration.compile,
            modDep.coursierDependency.withConfiguration(cs.Configuration.compile)
          ),
          (
            cs.Configuration.runtime,
            modDep.coursierDependency.withConfiguration(cs.Configuration.runtime)
          )
        )
      } ++
        compileModuleDepsChecked.map { modDep =>
          // Compile-only (aka provided) dependencies
          // We pull their compile scope when our provided scope is asked (see scopes above)
          (
            cs.Configuration.provided,
            modDep.coursierDependency.withConfiguration(cs.Configuration.compile)
          )
        } ++
        runModuleDepsChecked.map { modDep =>
          // Runtime dependencies
          // We pull their runtime scope when our runtime scope is pulled
          (
            cs.Configuration.runtime,
            modDep.coursierDependency.withConfiguration(cs.Configuration.runtime)
          )
        }

    val dependencies =
      (mandatoryIvyDeps() ++ ivyDeps()).map(bindDependency()).map(_.dep).iterator.toSeq.flatMap {
        dep =>
          // Standard dependencies, like above
          // We pull their compile scope when our compile scope is asked,
          // and pull their runtime scope when our runtime scope is asked.
          Seq(
            (cs.Configuration.compile, dep.withConfiguration(cs.Configuration.compile)),
            (cs.Configuration.runtime, dep.withConfiguration(cs.Configuration.runtime))
          )
      } ++
        compileIvyDeps().map(bindDependency()).map(_.dep).map { dep =>
          // Compile-only (aka provided) dependencies, like above
          // We pull their compile scope when our provided scope is asked (see scopes above)
          (cs.Configuration.provided, dep.withConfiguration(cs.Configuration.compile))
        } ++
        runIvyDeps().map(bindDependency()).map(_.dep).map { dep =>
          // Runtime dependencies, like above
          // We pull their runtime scope when our runtime scope is pulled
          (
            cs.Configuration.runtime,
            dep.withConfiguration(cs.Configuration.runtime)
          )
        } ++
        allBomDeps().map { bomDep =>
          // BOM dependencies
          // Maven has a special scope for those: "import"
          val dep =
            cs.Dependency(bomDep.module, bomDep.version).withConfiguration(bomDep.config)
          (cs.Configuration.`import`, dep)
        }

    val depMgmt =
      processedDependencyManagement(
        depManagement().iterator.toSeq.map(bindDependency()).map(_.dep)
      ).map {
        case (key, values) =>
          val config0 =
            if (values.config.isEmpty) cs.Configuration.compile
            else values.config
          (config0, values.fakeDependency(key))
      }

    cs.Project(
      module = coursierDependency.module,
      version = coursierDependency.version,
      dependencies = internalDependencies ++ dependencies,
      configurations = scopes,
      parent = None,
      dependencyManagement = depMgmt,
      properties = Nil,
      profiles = Nil,
      versions = None,
      snapshotVersioning = None,
      packagingOpt = None,
      relocated = false,
      actualVersionOpt = None,
      publications = Nil,
      info = coursier.core.Info.empty
    )
  }

  /**
   * Coursier project of this module and those of all its transitive module dependencies
   */
  def transitiveCoursierProjects: Task[Seq[cs.Project]] = Task {
    Seq(coursierProject()) ++
      T.traverse(compileModuleDepsChecked)(_.transitiveCoursierProjects)().flatten ++
      T.traverse(moduleDepsChecked)(_.transitiveCoursierProjects)().flatten ++
      T.traverse(runModuleDepsChecked)(_.transitiveCoursierProjects)().flatten
  }

  /**
   * The Ivy dependencies of this module, with BOM and dependency management details
   * added to them. This should be used when propagating the dependencies transitively
   * to other modules.
   */
  @deprecated("Unused by Mill, use allIvyDeps instead", "Mill after 0.12.5")
  def processedIvyDeps: Task[Agg[BoundDep]] = Task {
    allIvyDeps().map(bindDependency())
  }

  /**
   * Returns a function adding BOM and dependency management details of
   * this module to a `coursier.core.Dependency`
   */
  @deprecated("Unused by Mill", "Mill after 0.12.5")
  def processDependency(
      overrideVersions: Boolean = false
  ): Task[coursier.core.Dependency => coursier.core.Dependency] =
    Task.Anon((x: coursier.core.Dependency) => x)

  /**
   * The transitive ivy dependencies of this module and all it's upstream modules.
   * This is calculated from [[ivyDeps]], [[mandatoryIvyDeps]] and recursively from [[moduleDeps]].
   *
   * This isn't used by Mill anymore. Instead of this, consider using either:
   *   * `coursierDependency`, which will pull all this module's dependencies transitively
   *   * `allIvyDeps`, which contains the full list of direct (external) dependencies of this module
   */
  @deprecated("Unused by Mill, use coursierDependency or allIvyDeps instead", "Mill after 0.12.5")
  def transitiveIvyDeps: T[Agg[BoundDep]] = Task {
    allIvyDeps().map(bindDependency()) ++
      T.traverse(moduleDepsChecked)(_.transitiveIvyDeps)().flatten
  }

  /**
   * The compile-only transitive ivy dependencies of this module and all its upstream compile-only modules.
   *
   * This isn't used by Mill anymore. Instead of this, consider using either:
   *   * `coursierDependency().withConfiguration(Configuration.provided`), which will pull all
   *      this module's compile-only dependencies transitively
   *   * `compileIvyDeps`, which contains the full list of direct (external) compile-only
   *      dependencies of this module
   */
  @deprecated(
    "Unused by Mill, use coursierDependency().withConfiguration(Configuration.provided) or compileIvyDeps instead",
    "Mill after 0.12.5"
  )
  def transitiveCompileIvyDeps: T[Agg[BoundDep]] = Task {
    compileIvyDeps().map(bindDependency()) ++
      T.traverse(moduleDepsChecked)(_.transitiveCompileIvyDeps)().flatten
  }

  /**
   * The transitive run ivy dependencies of this module and all it's upstream modules.
   * This is calculated from [[runIvyDeps]], [[mandatoryIvyDeps]] and recursively from [[moduleDeps]].
   *
   * This isn't used by Mill anymore. Instead of this, consider using either:
   *   * `coursierDependency().withConfiguration(Configuration.runtime`), which will pull all
   *      this module's runtime dependencies transitively
   *   * `runIvyDeps`, which contains the full list of direct (external) runtime
   *      dependencies of this module
   */
  @deprecated(
    "Unused by Mill, use coursierDependency().withConfiguration(Configuration.runtime) or runIvyDeps instead",
    "Mill after 0.12.5"
  )
  def transitiveRunIvyDeps: T[Agg[BoundDep]] = Task {
    runIvyDeps().map(bindDependency()) ++
      T.traverse(moduleDepsChecked)(_.transitiveRunIvyDeps)().flatten
  }

  /**
   * The repository that knows about this project itself and its module dependencies
   */
  def internalDependenciesRepository: Task[cs.Repository] = Task.Anon {
    // This is the main point of contact between the coursier resolver and Mill.
    // Basically, all relevant Mill modules are aggregated and converted to a
    // coursier.Project (provided by JavaModule#coursierProject).
    //
    // Dependencies, both external ones (like ivyDeps, bomIvyDeps, etc.) and internal ones
    // (like moduleDeps) are put in coursier.Project#dependencies. The coursier.Dependency
    // used to represent each module is built by JavaModule#coursierDependency. So we put
    // JavaModule#coursierDependency in the dependencies field of other modules'
    // JavaModule#coursierProject to represent links between them.
    //
    // coursier.Project#dependencies accepts (coursier.Configuration, coursier.Dependency) tuples.
    // The configuration is different for compile-time only / runtime / BOM dependencies
    // (it's respectively provided, runtime, import). The configuration is compile for
    // standard ivyDeps / moduleDeps.
    //
    JavaModule.InternalRepo(transitiveCoursierProjects().distinctBy(_.module.name.value))
  }

  /**
   * Mill internal repositories to be used during dependency resolution
   *
   * These are not meant to be modified by Mill users, unless you really know what you're
   * doing.
   */
  def internalRepositories: Task[Seq[cs.Repository]] = Task.Anon {
    Seq(internalDependenciesRepository())
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
   * Almost the same as [[transitiveLocalClasspath]], but using the [[jar]]s instead of [[localClasspath]].
   */
  def transitiveJars: T[Seq[PathRef]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps)(_.jar)()
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

  // bincompat stub
  def prependShellScript: T[String] = Task {
    prependShellScript0()
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
   * Resolved dependencies
   */
  def resolvedIvyDeps: T[Agg[PathRef]] = Task {
    defaultResolver().resolveDeps(
      Seq(
        BoundDep(
          coursierDependency.withConfiguration(cs.Configuration.provided),
          force = false
        ),
        BoundDep(coursierDependency, force = false)
      ),
      artifactTypes = Some(artifactTypes()),
      resolutionParamsMapOpt =
        Some((_: ResolutionParams).withDefaultConfiguration(coursier.core.Configuration.compile))
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
      Seq(
        BoundDep(
          coursierDependency.withConfiguration(cs.Configuration.runtime),
          force = false
        )
      ),
      artifactTypes = Some(artifactTypes()),
      resolutionParamsMapOpt =
        Some((_: ResolutionParams).withDefaultConfiguration(cs.Configuration.runtime))
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

  // bincompat stub
  def manifest: T[JarManifest] = Task { manifest0() }

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

  // Bincompat stub
  def upstreamAssembly2: T[Assembly] = Task { upstreamAssembly2_0() }

  // Bincompat stub
  override def assembly: T[PathRef] = Task[PathRef] { assembly0() }

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
  def docSources: T[Seq[PathRef]] = Task { allSources() }

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

  def launcher: T[PathRef] = Task { launcher0() }

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
      val dependencies =
        (additionalDeps() ++ Seq(BoundDep(coursierDependency, force = false))).iterator.to(Seq)
      val resolution: Resolution = Lib.resolveDependenciesMetadataSafe(
        repositoriesTask(),
        dependencies,
        Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
        resolutionParams = resolutionParams()
      ).getOrThrow

      val roots = whatDependsOn match {
        case List() =>
          val mandatoryModules =
            mandatoryIvyDeps().map(bindDependency()).iterator.map(_.dep.module).toSet
          val (mandatory, main) = resolution.dependenciesOf(coursierDependency)
            .partition(dep => mandatoryModules.contains(dep.module))
          additionalDeps().iterator.toSeq.map(_.dep) ++ main ++ mandatory
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

      val tree = coursier.util.Print.dependencyTree(
        resolution = resolution,
        roots = roots,
        printExclusions = false,
        reverse = if (whatDependsOn.isEmpty) inverse else true
      )

      // Filter the output, so that the special organization and version used for Mill's own modules
      // don't appear in the output. This only leaves the modules' name built from millModuleSegments.
      val processedTree = tree
        .replace(" mill-internal:", " ")
        .replace(":0+mill-internal ", " ")
        .replace(":0+mill-internal" + System.lineSeparator(), System.lineSeparator())

      println(processedTree)

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
                Agg(
                  coursierDependency.withConfiguration(cs.Configuration.provided),
                  coursierDependency.withConfiguration(cs.Configuration.runtime)
                ).map(BoundDep(_, force = false))
              },
              validModules
            )()
          }
        case (Flag(true), Flag(false)) =>
          Task.Command {
            printDepsTree(
              args.inverse.value,
              Task.Anon {
                Agg(BoundDep(
                  coursierDependency.withConfiguration(cs.Configuration.provided),
                  force = false
                ))
              },
              validModules
            )()
          }
        case (Flag(false), Flag(true)) =>
          Task.Command {
            printDepsTree(
              args.inverse.value,
              Task.Anon {
                Agg(BoundDep(
                  coursierDependency.withConfiguration(cs.Configuration.runtime),
                  force = false
                ))
              },
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
            Seq(
              coursierDependency.withConfiguration(cs.Configuration.provided),
              coursierDependency
            ),
            sources = true,
            resolutionParamsMapOpt =
              Some(
                (_: ResolutionParams).withDefaultConfiguration(coursier.core.Configuration.compile)
              )
          )
        },
        Task.Anon {
          defaultResolver().resolveDeps(
            Seq(coursierDependency.withConfiguration(cs.Configuration.runtime)),
            sources = true
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

object JavaModule {

  /**
   * An in-memory [[coursier.Repository]] that exposes the passed projects
   *
   * Doesn't generate artifacts for these. These are assumed to be managed
   * externally for now.
   *
   * @param projects
   */
  final case class InternalRepo(projects: Seq[cs.Project])
      extends cs.Repository {

    private lazy val map = projects.map(proj => proj.moduleVersion -> proj).toMap

    override def toString(): String =
      pprint.apply(this).toString

    def find[F[_]: Monad](
        module: cs.Module,
        version: String,
        fetch: cs.Repository.Fetch[F]
    ): EitherT[F, String, (cs.ArtifactSource, cs.Project)] =
      EitherT(
        Monad[F].point {
          map.get((module, version))
            .map((this, _))
            .toRight(s"Not an internal Mill module: ${module.repr}:$version")
        }
      )

    def artifacts(
        dependency: cs.Dependency,
        project: cs.Project,
        overrideClassifiers: Option[Seq[coursier.core.Classifier]]
    ): Seq[(coursier.core.Publication, coursier.util.Artifact)] =
      // Mill modules' artifacts are handled by Mill itself
      Nil
  }

  private[mill] def internalOrg = coursier.core.Organization("mill-internal")
  private[mill] def internalVersion = "0+mill-internal"
}
