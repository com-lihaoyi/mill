package mill
package javalib

import coursier.{Repository, Type, core as cs}
import coursier.core.{BomDependency, Configuration, DependencyManagement, Resolution}
import coursier.params.ResolutionParams
import coursier.parse.{JavaOrScalaModule, ModuleParser}
import coursier.util.{EitherT, ModuleMatcher, Monad}
import mainargs.Flag
import mill.api.{MillException, Result}
import mill.api.daemon.internal.{EvaluatorApi, JavaModuleApi, internal}
import mill.api.daemon.internal.bsp.{
  BspBuildTarget,
  BspJavaModuleApi,
  BspModuleApi,
  BspUri,
  JvmBuildTarget
}
import mill.api.daemon.internal.eclipse.GenEclipseInternalApi
import mill.javalib.*
import mill.api.daemon.internal.idea.GenIdeaInternalApi
import mill.api.{DefaultTaskModule, ModuleRef, PathRef, Segment, Task, TaskCtx}
import mill.javalib.api.CompilationResult
import mill.javalib.api.internal.{JavaCompilerOptions, ZincCompileJava}
import mill.javalib.bsp.{BspJavaModule, BspModule}
import mill.javalib.internal.ModuleUtils
import mill.javalib.publish.Artifact
import mill.util.{JarManifest, Jvm}
import os.Path

import scala.util.chaining.scalaUtilChainingOps
import scala.util.matching.Regex

/**
 * Core configuration required to compile a single Java module
 */
trait JavaModule
    extends mill.api.Module
    with WithJvmWorkerModule
    with TestModule.JavaModuleBase
    with DefaultTaskModule
    with RunModule
    with GenIdeaModule
    with CoursierModule
    with OfflineSupportModule
    with BspModule
    with SemanticDbJavaModule
    with AssemblyModule
    with JavaModuleApi { outer =>

  private[mill] lazy val bspExt: ModuleRef[mill.javalib.bsp.BspJavaModule] = {
    ModuleRef(new BspJavaModule.Wrap(this) {}.internalBspJavaModule)
  }
  override private[mill] def bspJavaModule: () => BspJavaModuleApi = () => bspExt()

  private[mill] lazy val genEclipseInternalExt: ModuleRef[mill.javalib.eclipse.GenEclipseModule] = {
    ModuleRef(new mill.javalib.eclipse.GenEclipseModule.Wrap(this) {}.internalGenEclipse)
  }

  private[mill] override def genEclipseInternal: () => GenEclipseInternalApi =
    () => genEclipseInternalExt()

  private[mill] lazy val genIdeaInternalExt: ModuleRef[mill.javalib.idea.GenIdeaModule] = {
    ModuleRef(new mill.javalib.idea.GenIdeaModule.Wrap(this) {}.internalGenIdea)
  }

  private[mill] override def genIdeaInternal: () => GenIdeaInternalApi =
    () => genIdeaInternalExt()

  override def jvmWorker: ModuleRef[JvmWorkerModule] = super.jvmWorker
  trait JavaTests extends JavaModule with TestModule {
    // Run some consistence checks
    hierarchyChecks()

    override def resources = super[JavaModule].resources
    override def moduleDeps: Seq[JavaModule] = Seq(outer)
    override def repositoriesTask: Task[Seq[Repository]] = Task.Anon {
      outer.repositoriesTask()
    }

    override def resolutionCustomizer: Task[Option[coursier.Resolution => coursier.Resolution]] =
      outer.resolutionCustomizer

    override def javacOptions: T[Seq[String]] = Task { outer.javacOptions() }
    override def jvmWorker: ModuleRef[JvmWorkerModule] = outer.jvmWorker

    def jvmId = outer.jvmId

    def jvmIndexVersion = outer.jvmIndexVersion

    /**
     * Optional custom Java Home for the JvmWorker to use
     *
     * If this value is None, then the JvmWorker uses the same Java used to run
     * the current mill instance.
     */
    def javaHome = outer.javaHome

    override def skipIdea: Boolean = outer.skipIdea
    override def runUseArgsFile: T[Boolean] = Task { outer.runUseArgsFile() }
    override def sourcesFolders = outer.sourcesFolders

    override def bomMvnDeps = Task[Seq[Dep]] {
      super.bomMvnDeps() ++
        outer.bomMvnDeps()
    }
    override def depManagement = Task[Seq[Dep]] {
      super.depManagement() ++
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

  def defaultTask(): String = "run"
  def resolvePublishDependency: Task[Dep => publish.Dependency] = Task.Anon {
    Artifact.fromDepJava(_: Dep)
  }

  /**
   * Mandatory ivy dependencies that are typically always required and shouldn't be removed by
   * overriding [[mvnDeps]], e.g. the scala-library in the [[ScalaModule]].
   */
  def mandatoryMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Any ivy dependencies you want to add to this Module, in the format
   * mvn"org::name:version" for Scala dependencies or mvn"org:name:version"
   * for Java dependencies
   */
  def mvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Aggregation of mandatoryMvnDeps and mvnDeps.
   * In most cases, instead of overriding this task you want to override `mvnDeps` instead.
   */
  def allMvnDeps: T[Seq[Dep]] = Task { mvnDeps() ++ mandatoryMvnDeps() }

  /**
   * Same as `mvnDeps`, but only present at compile time. Useful for e.g.
   * macro-related dependencies like `scala-reflect` that doesn't need to be
   * present at runtime
   */
  def compileMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Additional dependencies, only present at runtime. Useful for e.g.
   * selecting different versions of a dependency to use at runtime after your
   * code has already been compiled.
   */
  def runMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  /**
   * Any Bill of Material (BOM) dependencies you want to add to this Module, in the format
   * mvn"org:name:version"
   */
  def bomMvnDeps: T[Seq[Dep]] = Task { Seq.empty[Dep] }

  def allBomDeps: Task[Seq[BomDependency]] = Task.Anon {
    val modVerOrMalformed =
      bomMvnDeps().map(bindDependency()).map { bomDep =>
        val fromModVer = coursier.core.Dependency(bomDep.dep.module, bomDep.version)
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
        "Found Bill of Material (BOM) dependencies with invalid parameters:" + System.lineSeparator() +
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
   *   def depManagement = super.depManagement() ++ Seq(
   *     mvn"com.lihaoyi::os-lib:0.11.3",
   *     mvn"com.lihaoyi::cask:0.9.5".exclude("org.slf4j", "slf4j-api")
   *   )
   * }}}
   */
  def depManagement: T[Seq[Dep]] = Task { Seq.empty[Dep] }

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
  override def javacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Additional options for the java compiler derived from other module settings.
   */
  override def mandatoryJavacOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   *  The direct dependencies of this module.
   *  This is meant to be overridden to add dependencies.
   *  To read the value, you should use [[moduleDepsChecked]] instead,
   *  which uses a cached result which is also checked to be free of cycle.
   *  @see [[moduleDepsChecked]]
   */
  def moduleDeps: Seq[JavaModule] = Seq()

  /**
   *  The compile-only direct dependencies of this module. These are *not*
   *  transitive, and only take effect in the module that they are declared in.
   */
  def compileModuleDeps: Seq[JavaModule] = Seq()

  /**
   * The runtime-only direct dependencies of this module. These *are* transitive,
   * and so get propagated to downstream modules automatically
   */
  def runModuleDeps: Seq[JavaModule] = Seq()

  /**
   *  Bill of Material (BOM) dependencies of this module.
   *  This is meant to be overridden to add BOM dependencies.
   *  To read the value, you should use [[bomModuleDepsChecked]] instead,
   *  which uses a cached result which is also checked to be free of cycles.
   *  @see [[bomModuleDepsChecked]]
   */
  def bomModuleDeps: Seq[BomModule] = Seq()

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

  /**
   * Same as [[bomModuleDeps]] but checked to not contain cycles.
   * Prefer this over using [[bomModuleDeps]] directly.
   */
  final def bomModuleDepsChecked: Seq[BomModule] = {
    // trigger initialization to check for cycles
    recBomModuleDeps
    bomModuleDeps
  }

  /** Should only be called from [[moduleDepsChecked]] */
  private lazy val recModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (moduleSegments ++ Seq(Segment.Label("moduleDeps"))).render,
      this,
      _.moduleDeps
    )

  /** Should only be called from [[compileModuleDeps]] */
  private lazy val recCompileModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (moduleSegments ++ Seq(Segment.Label("compileModuleDeps"))).render,
      this,
      _.compileModuleDeps
    )

  /** Should only be called from [[runModuleDepsChecked]] */
  private lazy val recRunModuleDeps: Seq[JavaModule] =
    ModuleUtils.recursive[JavaModule](
      (moduleSegments ++ Seq(Segment.Label("runModuleDeps"))).render,
      this,
      m => m.runModuleDeps ++ m.moduleDeps
    )

  /** Should only be called from [[bomModuleDepsChecked]] */
  private lazy val recBomModuleDeps: Seq[BomModule] =
    ModuleUtils.recursive[BomModule](
      (moduleSegments ++ Seq(Segment.Label("bomModuleDeps"))).render,
      null,
      mod => if (mod == null) bomModuleDeps else mod.bomModuleDeps
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

  private def formatModuleDeps(recursive: Boolean, includeHeader: Boolean): Task[String] =
    Task.Anon {
      val normalDeps = if (recursive) recursiveModuleDeps else moduleDepsChecked
      val compileDeps =
        if (recursive) compileModuleDepsChecked.flatMap(_.transitiveModuleDeps).distinct
        else compileModuleDepsChecked
      val runtimeDeps =
        if (recursive) runModuleDepsChecked.flatMap(_.transitiveRunModuleDeps).distinct
        else runModuleDepsChecked
      val deps = (normalDeps ++ compileDeps ++ runModuleDeps).distinct

      val header = Option.when(includeHeader)(
        s"${if (recursive) "Recursive module" else "Module"} dependencies of ${moduleSegments.render}:"
      ).toSeq
      val lines = deps.map { dep =>
        val isNormal = normalDeps.contains(dep)
        val markers = Seq(
          Option.when(!isNormal && compileDeps.contains(dep))("compile"),
          Option.when(!isNormal && runtimeDeps.contains(dep))("runtime")
        ).flatten
        val suffix = if (markers.isEmpty) "" else markers.mkString(" (", ",", ")")
        "  " + dep.moduleSegments.render + suffix
      }
      (header ++ lines).mkString("\n")
    }

  /**
   * Show the module dependencies.
   * @param recursive If `true` include all recursive module dependencies, else only show direct dependencies.
   */
  def showModuleDeps(recursive: Boolean = false): Task.Command[Unit] = {
    // This is exclusive to avoid scrambled output
    Task.Command(exclusive = true) {
      val asString = formatModuleDeps(recursive, true)()
      Task.log.streams.out.println(asString)
    }
  }

  /**
   * Additional jars, classfiles or resources to add to the classpath directly
   * from disk rather than being downloaded from Maven Central or other package
   * repositories
   */
  def unmanagedClasspath: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * The `coursier.Dependency` to use to refer to this module
   */
  def coursierDependency: cs.Dependency =
    // this is a simple def, and not a Task, as this is simple enough and needed in places
    // where eval'ing a Task would be impractical or not allowed
    cs.Dependency(
      cs.Module(
        JavaModule.internalOrg,
        coursier.core.ModuleName(moduleSegments.parts.mkString("-")),
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
      bomModuleDepsChecked.map { modDep =>
        val dep = coursier.core.Dependency(
          coursier.core.Module(
            coursier.core.Organization("mill-internal"),
            coursier.core.ModuleName(modDep.moduleSegments.parts.mkString("-")),
            Map.empty
          ),
          "0+mill-internal"
        )
        (coursier.core.Configuration.`import`, dep)
      } ++
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
      (mandatoryMvnDeps() ++ mvnDeps()).map(bindDependency()).map(_.dep).iterator.toSeq.flatMap {
        dep =>
          // Standard dependencies, like above
          // We pull their compile scope when our compile scope is asked,
          // and pull their runtime scope when our runtime scope is asked.
          if (dep.isVariantAttributesBased)
            Seq(
              (cs.Configuration.compile, dep),
              (cs.Configuration.runtime, dep)
            )
          else
            Seq(
              (cs.Configuration.compile, dep.withConfiguration(cs.Configuration.compile)),
              (cs.Configuration.runtime, dep.withConfiguration(cs.Configuration.runtime))
            )
      } ++
        compileMvnDeps().map(bindDependency()).map(_.dep).map { dep =>
          // Compile-only (aka provided) dependencies, like above
          // We pull their compile scope when our provided scope is asked (see scopes above)
          if (dep.isVariantAttributesBased)
            (cs.Configuration.provided, dep)
          else
            (cs.Configuration.provided, dep.withConfiguration(cs.Configuration.compile))
        } ++
        runMvnDeps().map(bindDependency()).map(_.dep).map { dep =>
          // Runtime dependencies, like above
          // We pull their runtime scope when our runtime scope is pulled
          if (dep.isVariantAttributesBased)
            (
              cs.Configuration.runtime,
              dep
            )
          else
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
   * Coursier projects of all the transitive module dependencies of this module
   *
   * Doesn't include the coursier project of the current module, see [[coursierProject]] for that.
   */
  def transitiveCoursierProjects: Task[Seq[cs.Project]] = {
    val allModuleDeps =
      (compileModuleDepsChecked ++ moduleDepsChecked ++ runModuleDepsChecked ++ bomModuleDepsChecked).distinct
    Task {
      val allTransitiveProjects =
        Task.traverse(allModuleDeps)(_.transitiveCoursierProjects)().flatten
      val allModuleDepsProjects = Task.traverse(allModuleDeps)(_.coursierProject)()
      (allModuleDepsProjects ++ allTransitiveProjects).distinctBy(_.module.name.value)
    }
  }

  /**
   * The repository that knows about this project itself and its module dependencies
   */
  def internalDependenciesRepository: Task[cs.Repository] = Task.Anon {
    // This is the main point of contact between the coursier resolver and Mill.
    // Basically, all relevant Mill modules are aggregated and converted to a
    // coursier.Project (provided by JavaModule#coursierProject).
    //
    // Dependencies, both external ones (like mvnDeps, bomMvnDeps, etc.) and internal ones
    // (like moduleDeps) are put in coursier.Project#dependencies. The coursier.Dependency
    // used to represent each module is built by JavaModule#coursierDependency. So we put
    // JavaModule#coursierDependency in the dependencies field of other modules'
    // JavaModule#coursierProject to represent links between them.
    //
    // coursier.Project#dependencies accepts (coursier.Configuration, coursier.Dependency) tuples.
    // The configuration is different for compile-time only / runtime / BOM dependencies
    // (it's respectively provided, runtime, import). The configuration is compile for
    // standard mvnDeps / moduleDeps.
    //
    val project = coursierProject()
    // Mark optional direct dependencies as non-optional, so that these are included in the
    // class paths of this module
    val project0 = project.withDependencies0(
      project.dependencies0.map {
        case (conf, dep) if dep.optional =>
          (conf, dep.withOptional(false))
        case other =>
          other
      }
    )
    JavaModule.InternalRepo(Seq(project0) ++ transitiveCoursierProjects())
  }

  /**
   * Mill internal repositories to be used during dependency resolution
   *
   * These are not meant to be modified by Mill users, unless you really know what you're
   * doing.
   */
  private[mill] def internalRepositories: Task[Seq[cs.Repository]] = Task.Anon {
    Seq(internalDependenciesRepository())
  }

  /**
   * The upstream compilation output of all this module's upstream modules
   */
  def upstreamCompileOutput: T[Seq[CompilationResult]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps)(_.compile)()
  }

  /**
   * The transitive version of `localClasspath`
   */
  def transitiveLocalClasspath: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleRunModuleDeps)(_.localClasspath)().flatten
  }

  /**
   * Almost the same as [[transitiveLocalClasspath]], but using the [[jar]]s instead of [[localClasspath]].
   */
  def transitiveJars: T[Seq[PathRef]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps)(_.jar)()
  }

  /**
   * The transitive version of [[compileClasspath]]
   */
  def transitiveCompileClasspath: T[Seq[PathRef]] = Task {
    transitiveCompileClasspathTask(CompileFor.Regular)()
  }

  /**
   * The transitive version of [[compileClasspathTask]]
   */
  private[mill] def transitiveCompileClasspathTask(compileFor: CompileFor): Task[Seq[PathRef]] =
    Task.Anon {
      Task.traverse(transitiveModuleCompileModuleDeps)(m =>
        Task.Anon { m.localCompileClasspath() ++ Seq(m.compileFor(compileFor)().classes) }
      )().flatten
    }

  /**
   * Same as [[transitiveCompileClasspath]], but with all dependencies on [[compile]]
   * replaced by their non-compiling [[bspCompileClassesPath]] variants.
   *
   * Keep in sync with [[transitiveCompileClasspath]]
   */
  @internal
  private[mill] def bspTransitiveCompileClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): Task[Seq[UnresolvedPath]] = Task.Anon {
    Task.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon {
        val localCompileClasspath =
          m.localCompileClasspath().map(p => UnresolvedPath.ResolvedPath(p.path))
        val compileClassesPath = m.bspCompileClassesPath(needsToMergeResourcesIntoCompileDest)()
        localCompileClasspath :+ compileClassesPath
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
   * Configuration for the [[assembly]] task: how files and file-conflicts are
   * managed when combining multiple jar files into one big assembly jar.
   */
  def assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules

  def sourcesFolders: Seq[os.SubPath] = Seq("src")

  /**
   * The folders where the source files for this module live
   */
  def sources: T[Seq[PathRef]] = Task.Sources(sourcesFolders*)

  /**
   * The folders where the resource files for this module live.
   * If you need resources to be seen by the compiler, use [[compileResources]].
   */
  def resources: T[Seq[PathRef]] = Task.Sources("resources")

  /**
   * The folders where the compile time resource files for this module live.
   * If your resources files do not necessarily need to be seen by the compiler,
   * you should use [[resources]] instead.
   */
  def compileResources: T[Seq[PathRef]] = Task.Sources { "compile-resources" }

  /**
   * Folders containing source files that are generated rather than
   * handwritten; these files can be generated in this task itself,
   * or can refer to files generated from other tasks
   */
  def generatedSources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }

  /**
   * Path to sources generated as part of the `compile` step, eg.  by Java annotation
   * processors which often generate source code alongside classfiles during compilation.
   *
   * Typically these do not need to be compiled again, and are only used by IDEs
   */
  def compileGeneratedSources: T[os.Path] = Task(persistent = true) { Task.dest }

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
  def compile: T[mill.javalib.api.CompilationResult] = Task(persistent = true) {
    // Prepare an empty `compileGeneratedSources` folder for java annotation processors
    // to write generated sources into, that can then be picked up by IDEs like IntelliJ
    val compileGenSources = compileGeneratedSources()
    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      os.remove.all(compileGenSources)
      os.makeDir.all(compileGenSources)
    }

    val jOpts = JavaCompilerOptions(Seq(
      "-s",
      compileGenSources.toString
    ) ++ javacOptions() ++ mandatoryJavacOptions())

    jvmWorker()
      .internalWorker()
      .compileJava(
        ZincCompileJava(
          upstreamCompileOutput = upstreamCompileOutput(),
          sources = allSourceFiles().map(_.path),
          compileClasspath = compileClasspath().map(_.path),
          javacOptions = jOpts.compiler,
          incrementalCompilation = zincIncrementalCompilation()
        ),
        javaHome = javaHome().map(_.path),
        javaRuntimeOptions = jOpts.runtime,
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems()
      )
  }

  /** Resolves paths relative to the `out` folder. */
  @internal
  private[mill] def resolveRelativeToOut(
      task: Task.Named[?],
      mkPath: os.SubPath => os.SubPath = identity
  ): UnresolvedPath.DestPath =
    UnresolvedPath.DestPath(mkPath(os.sub), task.ctx.segments)

  /** The path where the compiled classes produced by [[compile]] are stored. */
  @internal
  private[mill] def compileClassesPath: UnresolvedPath.DestPath =
    resolveRelativeToOut(compile, _ / "classes")

  /**
   * The path to the compiled classes by [[compile]] without forcing to actually run the compilation.
   * This is safe in an BSP context, as the compilation done later will use the
   * exact same compilation settings, so we can safely use the same path.
   *
   * Keep in sync with [[compile]] and [[bspBuildTargetCompile]].
   *
   * @param needsToMergeResourcesIntoCompileDest
   *   Whether we should copy resources into the compile destination directory.
   *
   *   This is needed because some BSP clients (e.g. Intellij) ignore the resources classpath that we supply for it
   *   when running tests.
   *
   *   Both sbt and maven (and presumably gradle) copy the resources into the compile destination directory, so while it
   *   seems like a hack, this seems to be a working solution.
   *
   *   See also: https://github.com/com-lihaoyi/mill/issues/4427#issuecomment-2908889481
   */
  @internal
  private[mill] def bspCompileClassesPath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): Task[UnresolvedPath] =
    Task.Anon {
      if (needsToMergeResourcesIntoCompileDest) resolveRelativeToOut(bspBuildTargetCompileMerged)
      else compileClassesPath
    }

  /**
   * The part of the [[localClasspath]] which is available "after compilation".
   *
   * Keep in sync with [[bspLocalRunClasspath]]
   */
  override def localRunClasspath: T[Seq[PathRef]] = Task {
    super.localRunClasspath() ++ resources() ++ Seq(compile().classes)
  }

  /**
   * Same as [[localRunClasspath]] but for use in BSP server.
   *
   * Keep in sync with [[localRunClasspath]]
   */
  @internal
  private[mill] def bspLocalRunClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): Task[Seq[UnresolvedPath]] =
    Task.Anon {
      Seq.from(super.localRunClasspath() ++ resources())
        .map(p => UnresolvedPath.ResolvedPath(p.path)) ++
        Seq(bspCompileClassesPath(needsToMergeResourcesIntoCompileDest)())
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
  private[mill] def bspLocalClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): Task[Seq[UnresolvedPath]] =
    Task.Anon {
      localCompileClasspath().map(p => UnresolvedPath.ResolvedPath(p.path)) ++
        bspLocalRunClasspath(needsToMergeResourcesIntoCompileDest)()
    }

  /**
   * [[compileClasspathTask]] for regular compilations.
   *
   * Keep return value in sync with [[bspCompileClasspath]].
   */
  def compileClasspath: T[Seq[PathRef]] = Task { compileClasspathTask(CompileFor.Regular)() }

  /**
   * All classfiles and resources from upstream modules and dependencies
   * necessary to compile this module.
   */
  override private[mill] def compileClasspathTask(compileFor: CompileFor): Task[Seq[PathRef]] =
    Task.Anon {
      resolvedMvnDeps() ++ transitiveCompileClasspathTask(compileFor)() ++ localCompileClasspath()
    }

  /**
   * Same as [[compileClasspath]], but does not trigger compilation targets, if possible.
   *
   * Keep in sync with [[compileClasspath]]
   */
  @internal
  override private[mill] def bspCompileClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  )
      : Task[EvaluatorApi => Seq[String]] = Task.Anon {
    (ev: EvaluatorApi) =>
      (resolvedMvnDeps().map(p => UnresolvedPath.ResolvedPath(p.path)) ++
        bspTransitiveCompileClasspath(needsToMergeResourcesIntoCompileDest)() ++
        localCompileClasspath().map(p => UnresolvedPath.ResolvedPath(p.path))).map(_.resolve(
        os.Path(ev.outPathJava)
      )).map(sanitizeUri)
  }

  /**
   * The *input* classfiles/resources from this module, used during compilation,
   * excluding upstream modules and third-party dependencies
   */
  def localCompileClasspath: T[Seq[PathRef]] = Task {
    compileResources() ++ unmanagedClasspath()
  }

  /**
   * Resolved dependencies
   */
  def resolvedMvnDeps: T[Seq[PathRef]] = Task {
    millResolver().classpath(
      Seq(
        BoundDep(
          coursierDependency.withConfiguration(cs.Configuration.provided),
          force = false
        ),
        BoundDep(coursierDependency, force = false)
      ),
      artifactTypes = Some(artifactTypes()),
      resolutionParamsMapOpt =
        Some { params =>
          params
            .withDefaultConfiguration(coursier.core.Configuration.compile)
            .withDefaultVariantAttributes(
              cs.VariantSelector.AttributesBased(
                params.defaultVariantAttributes.map(_.matchers).getOrElse(Map()) ++ Seq(
                  "org.gradle.usage" -> cs.VariantSelector.VariantMatcher.Api
                )
              )
            )
        }
    )
  }

  override def upstreamIvyAssemblyClasspath: T[Seq[PathRef]] = Task {
    resolvedRunMvnDeps()
  }
  override def upstreamLocalAssemblyClasspath: T[Seq[PathRef]] = Task {
    transitiveLocalClasspath()
  }

  /**
   * All upstream classfiles and resources necessary to build and executable
   * assembly, but without this module's contribution
   */
  def upstreamAssemblyClasspath: T[Seq[PathRef]] = Task {
    resolvedRunMvnDeps() ++ transitiveLocalClasspath()
  }

  def resolvedRunMvnDeps: T[Seq[PathRef]] = Task {
    millResolver().classpath(
      Seq(
        BoundDep(
          coursierDependency.withConfiguration(cs.Configuration.runtime),
          force = false
        )
      ),
      artifactTypes = Some(artifactTypes()),
      resolutionParamsMapOpt =
        Some { params =>
          params
            .withDefaultConfiguration(coursier.core.Configuration.runtime)
            .withDefaultVariantAttributes(
              cs.VariantSelector.AttributesBased(
                params.defaultVariantAttributes.map(_.matchers).getOrElse(Map()) ++ Seq(
                  "org.gradle.usage" -> cs.VariantSelector.VariantMatcher.Runtime
                )
              )
            )
        }
    )
  }

  override def runClasspath: T[Seq[PathRef]] = Task {
    super.runClasspath() ++
      resolvedRunMvnDeps().toSeq ++
      transitiveLocalClasspath() ++
      localClasspath()
  }

  /**
   * A jar containing only this module's resources and compiled classfiles,
   * without those from upstream modules and dependencies
   */
  def jar: T[PathRef] = Task {
    val jar = Task.dest / "out.jar"
    Jvm.createJar(jar, localClasspath().map(_.path).filter(os.exists), manifest())
    PathRef(jar)
  }

  /**
   * Additional options to be used by the javadoc tool.
   * You should not set the `-d` setting for specifying the target directory,
   * as that is done in the [[docJar]] task.
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
  def docResources: T[Seq[PathRef]] = Task.Sources("docs")

  /**
   * Control whether `docJar`-task should use a file to pass command line arguments to the javadoc tool.
   * Defaults to `true` on Windows.
   * Beware: Using an args-file is probably not supported for very old javadoc versions.
   */
  def docJarUseArgsFile: T[Boolean] = Task { scala.util.Properties.isWin }

  def javadocGenerated: T[PathRef] = Task[PathRef] {
    val outDir = Task.dest

    val javadocDir = outDir / "javadoc"
    os.makeDir.all(javadocDir)

    val files = Lib.findSourceFiles(docSources(), Seq("java"))

    if (files.nonEmpty) {
      val javaHome = this.javaHome().map(_.path)

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
          Task.log.debug(
            s"Creating javadoc options file @${argsFile} ..."
          )
          Seq(s"@${argsFile}")
        } else {
          options
        }

      Task.log.info(s"java home: ${javaHome.fold("default")(_.toString)}")
      Task.log.info("options: " + cmdArgs)

      val cmd = Seq(Jvm.jdkTool("javadoc", javaHome)) ++ cmdArgs
      os.call(
        cmd = cmd,
        env = Map(),
        cwd = Task.dest,
        stdin = os.Inherit,
        stdout = os.Inherit
      )
    }
    PathRef(javadocDir)
  }

  /**
   * The documentation jar, containing all the Javadoc/Scaladoc HTML files, for
   * publishing to Maven Central
   */
  def docJar: T[PathRef] = Task[PathRef] {
    PathRef(Jvm.createJar(Task.dest / "out.jar", Seq(javadocGenerated().path)))
  }

  /**
   * The source jar, containing only source code for publishing to Maven Central
   */
  def sourceJar: T[PathRef] = Task {
    PathRef(
      Jvm.createJar(
        Task.dest / "out.jar",
        (allSources() ++ resources() ++ compileResources()).map(_.path).filter(os.exists),
        manifest()
      )
    )
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
  private def renderDepsTree(
      inverse: Boolean,
      additionalDeps: Task[Seq[BoundDep]],
      whatDependsOn: List[JavaOrScalaModule]
  ): Task[String] =
    Task.Anon {
      val dependencies =
        (additionalDeps() ++ Seq(BoundDep(coursierDependency, force = false))).iterator.to(Seq)
      val resolution: Resolution = Lib.resolveDependenciesMetadataSafe(
        allRepositories(),
        dependencies,
        Some(mapDependencies()),
        customizer = resolutionCustomizer(),
        coursierCacheCustomizer = coursierCacheCustomizer(),
        resolutionParams = resolutionParams(),
        checkGradleModules = checkGradleModules(),
        config = coursierConfigModule().coursierConfig()
      ).get

      val roots = whatDependsOn match {
        case List() =>
          val mandatoryModules =
            mandatoryMvnDeps().map(bindDependency()).iterator.map(_.dep.module).toSet
          val (mandatory, main) = resolution.dependenciesOf0(coursierDependency).toTry.get
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
        .replace(s"${JavaModule.internalOrg.value}:", "")
        .pipe(JavaModule.removeInternalVersionRegex.replaceAllIn(_, "$1"))

      processedTree
    }

  /**
   * Command to print the transitive dependency tree to STDOUT.
   */
  def showMvnDepsTree(args: MvnDepsTreeArgs = MvnDepsTreeArgs()): Command[String] = {
    val treeTask = mvnDepsTree(args)
    Task.Command(exclusive = true) {
      val rendered = treeTask()
      Task.log.streams.out.println(rendered)
      rendered
    }
  }

  protected def mvnDepsTree(args: MvnDepsTreeArgs): Task[String] = {
    val (invalidModules, modules) =
      args.whatDependsOn.partitionMap(ModuleParser.javaOrScalaModule)

    if (invalidModules.nonEmpty) {
      Task.Anon {
        val msg = invalidModules.mkString("\n")
        Task.fail(msg)
      }
    } else {
      (args.withCompile, args.withRuntime) match {
        case (Flag(true), Flag(true)) =>
          renderDepsTree(
            args.inverse.value,
            Task.Anon {
              Seq(
                coursierDependency.withConfiguration(cs.Configuration.provided),
                coursierDependency.withConfiguration(cs.Configuration.runtime)
              ).map(BoundDep(_, force = false))
            },
            modules
          )
        case (Flag(true), Flag(false)) =>
          renderDepsTree(
            args.inverse.value,
            Task.Anon {
              Seq(BoundDep(
                coursierDependency.withConfiguration(cs.Configuration.provided),
                force = false
              ))
            },
            modules
          )
        case (Flag(false), Flag(true)) =>
          renderDepsTree(
            args.inverse.value,
            Task.Anon {
              Seq(BoundDep(
                coursierDependency.withConfiguration(cs.Configuration.runtime),
                force = false
              ))
            },
            modules
          )
        case _ =>
          renderDepsTree(
            args.inverse.value,
            Task.Anon {
              Seq.empty[BoundDep]
            },
            modules
          )
      }
    }

  }

  /**
   * Override this to change the published artifact id.
   * For example, by default a scala module foo.baz might be published as foo-baz_2.12 and a java module would be foo-baz.
   * Setting this to baz would result in a scala artifact baz_2.12 or a java artifact baz.
   */
  def artifactName: T[String] = artifactNameParts().mkString("-")

  def artifactNameParts: T[Seq[String]] = moduleSegments.parts

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

  /**
   * Files extensions that need to be managed by Zinc together with class files.
   * This means, if zinc needs to remove a class file, it will also remove files
   * which match the class file basename and a listed file extension.
   */
  def zincAuxiliaryClassFileExtensions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * @param all If `true` fetches also source dependencies
   */
  override def prepareOffline(all: Flag): Command[Seq[PathRef]] = {
    val tasks =
      if (all.value) Seq(
        Task.Anon {
          millResolver().classpath(
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
          millResolver().classpath(
            Seq(coursierDependency.withConfiguration(cs.Configuration.runtime)),
            sources = true
          )
        }
      )
      else Seq()

    Task.Command {
      (
        super.prepareOffline(all)() ++
          resolvedMvnDeps() ++
          classgraphWorkerModule().prepareOffline(all)() ++
          jvmWorker().prepareOffline(all)() ++
          resolvedRunMvnDeps() ++
          Task.sequence(tasks)().flatten
      ).distinct
    }
  }

  @internal
  override def bspBuildTarget: BspBuildTarget = super.bspBuildTarget.copy(
    languageIds = Seq(BspModuleApi.LanguageId.Java),
    canCompile = true,
    canRun = true
  )

  @internal
  private[mill] def bspJvmBuildTargetTask: Task[JvmBuildTarget] = Task.Anon {
    JvmBuildTarget(
      javaHome = javaHome()
        .map(p => BspUri(p.path.toNIO))
        .orElse(Option(System.getProperty("java.home")).map(p => BspUri(os.Path(p).toNIO))),
      javaVersion = Option(System.getProperty("java.version"))
    )
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = Task.Anon {
    Some((JvmBuildTarget.dataKind, bspJvmBuildTargetTask()))
  }

//  @internal
//  private[mill] def bspBuildTargetJavacOptions(
//      needsToMergeResourcesIntoCompileDest: Boolean,
//      clientWantsSemanticDb: Boolean
//  ) = {
//    val classesPathTask = this match {
//      case sem: SemanticDbJavaModule if clientWantsSemanticDb =>
//        sem.bspCompiledClassesAndSemanticDbFiles
//      case _ => bspCompileClassesPath(needsToMergeResourcesIntoCompileDest)
//    }
//    Task.Anon { (ev: EvaluatorApi) =>
//      (
//        classesPathTask().resolve(os.Path(ev.outPathJava)).toNIO,
//        javacOptions() ++ mandatoryJavacOptions(),
//        bspCompileClasspath(needsToMergeResourcesIntoCompileDest).apply().apply(ev)
//      )
//    }
//  }

  def sanitizeUri(uri: String): String =
    if (uri.endsWith("/")) sanitizeUri(uri.substring(0, uri.length - 1)) else uri

  def sanitizeUri(uri: os.Path): String = sanitizeUri(uri.toURI.toString)

  def sanitizeUri(uri: PathRef): String = sanitizeUri(uri.path)

  /**
   * Performs the compilation (via [[compile]]) and merging of [[resources]] needed by
   * [[BspClientType.mergeResourcesIntoClasses]].
   */
  @internal
  private[mill] def bspBuildTargetCompileMerged: T[PathRef] = Task {

    /**
     * Make sure to invoke the [[bspBuildTargetCompileMerged]] of the transitive dependencies. For example, tests
     * should be able to read resources of the module that they are testing.
     */
    val _ = Task.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon(m.bspBuildTargetCompileMerged())
    )()

    // Merge the compile and resources classpaths.
    val compileClasses = compile().classes.path
    // The `compileClasses` can not exist if we had no sources in the module.
    if (os.exists(compileClasses)) os.copy(compileClasses, Task.dest, mergeFolders = true)
    resources().foreach { resource =>
      // The `resource.path` can not exist if we had no resources in the module.
      if (os.exists(resource.path)) os.copy(resource.path, Task.dest, mergeFolders = true)
    }

    PathRef(Task.dest)
  }

  @internal
  override private[mill] def bspBuildTargetCompile(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): Task[java.nio.file.Path] = {
    if (needsToMergeResourcesIntoCompileDest) Task.Anon { bspBuildTargetCompileMerged().path.toNIO }
    else Task.Anon { compile().classes.path.toNIO }
  }

  /**
   * Stable version of [[repositoriesTask]] so it doesn't keep getting
   * recomputed over and over during the recursive traversal
   */
  private lazy val repositoriesTaskStable = Task.Anon {
    val transitive = Task.traverse(recursiveModuleDeps)(_.repositoriesTask)()
    val sup = repositoriesTask0()
    (sup ++ transitive.flatten).distinct
  }

  /**
   * Repositories are transitively aggregated from upstream modules, following
   * the behavior of Maven, Gradle, and SBT
   */
  override def repositoriesTask: Task[Seq[Repository]] = repositoriesTaskStable
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

    // Reversing the sequence before calling toMap, so that earlier elements have precedence
    // over later one, in case they have the same module / version.
    // That's useful for the handling of optional dependencies, where the main project, with
    // initially optional dependencies marked as non-optional, is put upfront, but might still
    // be pulled transitively, in that case without the special handling of optional dependencies.
    private lazy val map = projects.reverseIterator.map(proj => proj.moduleVersion -> proj).toMap

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

  private lazy val removeInternalVersionRegex =
    (":" + Regex.quote(JavaModule.internalVersion) + "(\\w*$|\\n)").r

}

/**
 * A module that consists solely of dependency management
 *
 * To be used by other modules via `JavaModule#bomModuleDeps`
 */
trait BomModule extends JavaModule {
  abstract override def compile: T[CompilationResult] = Task {
    val sources = allSourceFiles()
    if (sources.nonEmpty)
      throw new Exception("A BomModule cannot have sources")
    CompilationResult(Task.dest / "zinc", PathRef(Task.dest / "classes"))
  }

  abstract override def resources: T[Seq[PathRef]] = Task {
    val value = super.resources()
    if (value.nonEmpty)
      throw new Exception("A BomModule cannot have resources")
    Seq.empty[PathRef]
  }

  private def emptyJar: T[PathRef] = Task {
    PathRef(Jvm.createJar(Task.dest / "out.jar", Seq.empty[os.Path]))
  }
  abstract override def jar: T[PathRef] = Task {
    emptyJar()
  }
  abstract override def docJar: T[PathRef] = Task {
    emptyJar()
  }
  abstract override def sourceJar: T[PathRef] = Task {
    emptyJar()
  }
}
