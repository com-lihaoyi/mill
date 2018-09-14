package mill
package scalalib


import ammonite.ops._
import coursier.Repository
import mill.define.Task
import mill.define.TaskModule
import mill.eval.{PathRef, Result}
import mill.modules.{Assembly, Jvm}
import mill.modules.Jvm.{createAssembly, createJar}
import Lib._
import mill.scalalib.publish.{Artifact, Scope}
import mill.util.Loose.Agg

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait JavaModule extends mill.Module with TaskModule { outer =>
  def zincWorker: ZincWorkerModule = mill.scalalib.ZincWorkerModule

  trait Tests extends TestModule{
    override def moduleDeps = Seq(outer)
    override def repositories = outer.repositories
    override def javacOptions = outer.javacOptions
    override def zincWorker = outer.zincWorker
  }
  def defaultCommandName() = "run"

  def resolvePublishDependency: Task[Dep => publish.Dependency] = T.task{
    Artifact.fromDepJava(_: Dep)
  }
  def resolveCoursierDependency: Task[Dep => coursier.Dependency] = T.task{
    Lib.depToDependencyJava(_: Dep)
  }

  def mainClass: T[Option[String]] = None

  def finalMainClassOpt: T[Either[String, String]] = T{
    mainClass() match{
      case Some(m) => Right(m)
      case None =>
        zincWorker.worker().discoverMainClasses(compile())match {
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

  def finalMainClass: T[String] = T{
    finalMainClassOpt() match {
      case Right(main) => Result.Success(main)
      case Left(msg)   => Result.Failure(msg)
    }
  }

  def ivyDeps = T{ Agg.empty[Dep] }
  def compileIvyDeps = T{ Agg.empty[Dep] }
  def runIvyDeps = T{ Agg.empty[Dep] }

  def javacOptions = T{ Seq.empty[String] }

  /** The direct dependencies of this module */
  def moduleDeps = Seq.empty[JavaModule]

  /** The direct and indirect dependencies of this module */
  def recursiveModuleDeps: Seq[JavaModule] = {
    moduleDeps.flatMap(_.transitiveModuleDeps).distinct
  }

  /** Like `recursiveModuleDeps` but also include the module itself */
  def transitiveModuleDeps: Seq[JavaModule] = {
    Seq(this) ++ recursiveModuleDeps
  }

  def unmanagedClasspath = T{ Agg.empty[PathRef] }


  def transitiveIvyDeps: T[Agg[Dep]] = T{
    ivyDeps() ++ Task.traverse(moduleDeps)(_.transitiveIvyDeps)().flatten
  }

  def upstreamCompileOutput = T{
    Task.traverse(recursiveModuleDeps)(_.compile)
  }

  def transitiveLocalClasspath: T[Agg[PathRef]] = T{
    Task.traverse(moduleDeps)(m =>
      T.task{m.localClasspath() ++ m.transitiveLocalClasspath()}
    )().flatten
  }

  def mapDependencies = T.task{ d: coursier.Dependency => d }

  def resolveDeps(deps: Task[Agg[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      resolveCoursierDependency().apply(_),
      deps(),
      sources,
      mapDependencies = Some(mapDependencies())
    )
  }


  def repositories: Seq[Repository] = zincWorker.repositories

  def platformSuffix = T{ "" }

  private val Milestone213 = raw"""2.13.(\d+)-M(\d+)""".r

  def prependShellScript: T[String] = T{
    mainClass() match{
      case None => ""
      case Some(cls) =>
        val isWin = scala.util.Properties.isWin
        mill.modules.Jvm.launcherUniversalScript(
          cls,
          Agg("$0"), Agg("%~dpnx0"),
          forkArgs()
        )
    }
  }

  def assemblyRules: Seq[Assembly.Rule] = Assembly.defaultRules

  def sources = T.sources{ millSourcePath / 'src }
  def resources = T.sources{ millSourcePath / 'resources }
  def generatedSources = T{ Seq.empty[PathRef] }
  def allSources = T{ sources() ++ generatedSources() }

  def allSourceFiles = T{
    def isHiddenFile(path: Path) = path.segments.last.startsWith(".")
    for {
      root <- allSources()
      if exists(root.path)
      path <- (if (root.path.isDir) ls.rec(root.path) else Seq(root.path))
      if path.isFile && ((path.ext == "scala" || path.ext == "java") && !isHiddenFile(path))
    } yield PathRef(path)
  }

  def compile: T[CompilationResult] = T.persistent{
    zincWorker.worker().compileJava(
      upstreamCompileOutput(),
      allSourceFiles().map(_.path),
      compileClasspath().map(_.path),
      javacOptions()
    )
  }

  def localClasspath = T{
    resources() ++ Agg(compile().classes)
  }
  def compileClasspath = T{
    transitiveLocalClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{compileIvyDeps() ++ transitiveIvyDeps()})()
  }

  def upstreamAssemblyClasspath = T{
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{runIvyDeps() ++ transitiveIvyDeps()})()
  }

  def runClasspath = T{
    localClasspath() ++
    upstreamAssemblyClasspath()
  }

  /**
    * Build the assembly for upstream dependencies separate from the current classpath
    *
    * This should allow much faster assembly creation in the common case where
    * upstream dependencies do not change
    */
  def upstreamAssembly = T{
    createAssembly(
      upstreamAssemblyClasspath().map(_.path),
      mainClass(),
      assemblyRules = assemblyRules
    )
  }

  def assembly = T{
    createAssembly(
      Agg.from(localClasspath().map(_.path)),
      mainClass(),
      prependShellScript(),
      Some(upstreamAssembly().path),
      assemblyRules
    )
  }

  def jar = T{
    createJar(
      localClasspath().map(_.path).filter(exists),
      mainClass()
    )
  }

  def docJar = T[PathRef] {
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val files = for{
      ref <- allSources()
      if exists(ref.path)
      p <- (if (ref.path.isDir) ls.rec(ref.path) else Seq(ref.path))
      if p.isFile && (p.ext == "java")
    } yield p.toNIO.toString

    val options = Seq("-d", javadocDir.toNIO.toString)

    if (files.nonEmpty) Jvm.baseInteractiveSubprocess(
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
      workingDir = T.ctx().dest
    )

    createJar(Agg(javadocDir))(outDir)
  }

  def sourceJar = T {
    createJar((allSources() ++ resources()).map(_.path).filter(exists))
  }

  def forkArgs = T{ Seq.empty[String] }

  def forkEnv = T{ sys.env.toMap }

  def launcher = T{
    Result.Success(
      Jvm.createLauncher(
        finalMainClass(),
        runClasspath().map(_.path),
        forkArgs()
      )
    )
  }

  def ivyDepsTree(inverse: Boolean = false) = T.command {
    val (flattened, resolution) = Lib.resolveDependenciesMetadata(
      repositories,
      resolveCoursierDependency().apply(_),
      transitiveIvyDeps(),
      Some(mapDependencies())
    )

    println(coursier.util.Print.dependencyTree(flattened, resolution,
      printExclusions = false, reverse = inverse))

    Result.Success()
  }

  def runLocal(args: String*) = T.command {
    Jvm.runLocal(
      finalMainClass(),
      runClasspath().map(_.path),
      args
    )
  }

  def run(args: String*) = T.command{
    try Result.Success(Jvm.interactiveSubprocess(
      finalMainClass(),
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      args,
      workingDir = forkWorkingDir()
    )) catch { case e: InteractiveShelloutException =>
       Result.Failure("subprocess failed")
    }
  }

  private[this] def backgroundSetup(dest: Path) = {
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
    while({
      if (exists(procTombstone)) {
        Thread.sleep(10)
        rm(procTombstone)
        true
      } else {
        Thread.sleep(10)
        System.currentTimeMillis() - start < 100
      }
    })()

    write(procId, token)
    write(procTombstone, token)
    (procId, procTombstone, token)
  }
  def runBackground(args: String*) = T.command{
    val (procId, procTombstone, token) = backgroundSetup(T.ctx().dest)
    try Result.Success(Jvm.interactiveSubprocess(
      "mill.scalalib.backgroundwrapper.BackgroundWrapper",
      (runClasspath() ++ zincWorker.backgroundWrapperClasspath()).map(_.path),
      forkArgs(),
      forkEnv(),
      Seq(procId.toString, procTombstone.toString, token, finalMainClass()) ++ args,
      workingDir = forkWorkingDir(),
      background = true
    )) catch { case e: InteractiveShelloutException =>
       Result.Failure("subprocess failed")
    }
  }

  def runMainBackground(mainClass: String, args: String*) = T.command{
    val (procId, procTombstone, token) = backgroundSetup(T.ctx().dest)
    try Result.Success(Jvm.interactiveSubprocess(
      "mill.scalalib.backgroundwrapper.BackgroundWrapper",
      (runClasspath() ++ zincWorker.backgroundWrapperClasspath()).map(_.path),
      forkArgs(),
      forkEnv(),
      Seq(procId.toString, procTombstone.toString, token, mainClass) ++ args,
      workingDir = forkWorkingDir(),
      background = true
    )) catch { case e: InteractiveShelloutException =>
      Result.Failure("subprocess failed")
    }
  }

  def runMainLocal(mainClass: String, args: String*) = T.command {
    Jvm.runLocal(
      mainClass,
      runClasspath().map(_.path),
      args
    )
  }

  def runMain(mainClass: String, args: String*) = T.command{
    try Result.Success(Jvm.interactiveSubprocess(
      mainClass,
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      args,
      workingDir = forkWorkingDir()
    )) catch { case e: InteractiveShelloutException =>
      Result.Failure("subprocess failed")
    }
  }

  // publish artifact with name "mill_2.12.4" instead of "mill_2.12"

  def artifactName: T[String] = millModuleSegments.parts.mkString("-")

  def artifactId: T[String] = artifactName()

  def intellijModulePath: Path = millSourcePath

  def forkWorkingDir = T{ ammonite.ops.pwd }
}

trait TestModule extends JavaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFrameworks: T[Seq[String]]

  def test(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalalib.TestRunner",
      classPath = zincWorker.scalalibClasspath().map(_.path),
      jvmArgs = forkArgs(),
      envArgs = forkEnv(),
      mainArgs =
        Seq(testFrameworks().length.toString) ++
        testFrameworks() ++
        Seq(runClasspath().length.toString) ++
        runClasspath().map(_.path.toString) ++
        Seq(args.length.toString) ++
        args ++
        Seq(outputPath.toString, T.ctx().log.colored.toString, compile().classes.path.toString, T.ctx().home.toString),
      workingDir = forkWorkingDir()
    )

    try {
      val jsonOutput = ujson.read(outputPath.toIO)
      val (doneMsg, results) = upickle.default.readJs[(String, Seq[TestRunner.Result])](jsonOutput)
      TestModule.handleResults(doneMsg, results)
    }catch{case e: Throwable =>
      Result.Failure("Test reporting failed: " + e)
    }

  }
  def testLocal(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    val (doneMsg, results) = TestRunner.runTests(
      TestRunner.frameworks(testFrameworks()),
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args
    )

    TestModule.handleResults(doneMsg, results)

  }
}

object TestModule{
  def handleResults(doneMsg: String, results: Seq[TestRunner.Result]) = {

    val badTests = results.filter(x => Set("Error", "Failure").contains(x.status))
    if (badTests.isEmpty) Result.Success((doneMsg, results))
    else {
      val suffix = if (badTests.length == 1) "" else " and " + (badTests.length-1) + " more"

      Result.Failure(
        badTests.head.fullyQualifiedName + " " + badTests.head.selector + suffix,
        Some((doneMsg, results))
      )
    }
  }
}
