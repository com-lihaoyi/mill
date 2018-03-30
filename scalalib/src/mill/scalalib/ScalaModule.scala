package mill
package scalalib

import ammonite.ops._
import coursier.Repository
import mill.define.Task
import mill.define.TaskModule
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar, subprocess}
import Lib._
import mill.util.Loose.Agg
import mill.util.DummyInputStream

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait ScalaModule extends mill.Module with TaskModule { outer =>
  def defaultCommandName() = "run"
  trait Tests extends TestModule{
    def scalaVersion = outer.scalaVersion()
    override def repositories = outer.repositories
    override def scalacPluginIvyDeps = outer.scalacPluginIvyDeps
    override def scalacOptions = outer.scalacOptions
    override def scalaWorker = outer.scalaWorker
    override def moduleDeps = Seq(outer)
  }
  def scalaVersion: T[String]

  def mainClass: T[Option[String]] = None

  def scalaWorker: ScalaWorkerModule = mill.scalalib.ScalaWorkerModule

  def finalMainClassOpt: T[Either[String, String]] = T{
    mainClass() match{
      case Some(m) => Right(m)
      case None =>
        scalaWorker.worker().discoverMainClasses(compile())match {
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
  def scalacPluginIvyDeps = T{ Agg.empty[Dep] }
  def runIvyDeps = T{ Agg.empty[Dep] }

  def scalacOptions = T{ Seq.empty[String] }
  def javacOptions = T{ Seq.empty[String] }

  def moduleDeps = Seq.empty[ScalaModule]


  def transitiveModuleDeps: Seq[ScalaModule] = {
    Seq(this) ++ moduleDeps.flatMap(_.transitiveModuleDeps).distinct
  }
  def unmanagedClasspath = T{ Agg.empty[PathRef] }


  def transitiveIvyDeps: T[Agg[Dep]] = T{
    ivyDeps() ++ Task.traverse(moduleDeps)(_.transitiveIvyDeps)().flatten
  }

  def upstreamCompileOutput = T{
    Task.traverse(moduleDeps)(_.compile)
  }

  def transitiveLocalClasspath: T[Agg[PathRef]] = T{
    Task.traverse(moduleDeps)(m =>
      T.task{m.localClasspath() ++ m.transitiveLocalClasspath()}
    )().flatten
  }

  def resolveDeps(deps: Task[Agg[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      scalaVersion(),
      deps(),
      platformSuffix(),
      sources
    )
  }


  def repositories: Seq[Repository] = scalaWorker.repositories

  def platformSuffix = T{ "" }

  private val Milestone213 = raw"""2.13.(\d+)-M(\d+)""".r

  def scalaCompilerBridgeSources = T {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion() match {
      case Milestone213(_, _) => ("2.13.0-M2", "2.13.0-M2")
      case _ => (scalaVersion(), Lib.scalaBinaryVersion(scalaVersion()))
    }

    resolveDependencies(
      repositories,
      scalaVersion0,
      Seq(ivy"org.scala-sbt::compiler-bridge:1.1.0"),
      sources = true
    ).map(_.find(_.path.last == s"compiler-bridge_${scalaBinaryVersion0}-1.1.0-sources.jar").map(_.path).get)
  }

  def scalacPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(scalacPluginIvyDeps)()
  }

  def scalaLibraryIvyDeps = T{ scalaRuntimeIvyDeps(scalaVersion()) }
  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Agg[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())}
    )()
  }


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

  def sources = T.sources{ millSourcePath / 'src }
  def resources = T.sources{ millSourcePath / 'resources }
  def generatedSources = T{ Seq.empty[PathRef] }
  def allSources = T{ sources() ++ generatedSources() }

  def allSourceFiles = T{
    for {
      root <- allSources()
      if exists(root.path)
      path <- ls.rec(root.path)
      if path.isFile && (path.ext == "scala" || path.ext == "java")
    } yield PathRef(path)
  }

  def compile: T[CompilationResult] = T.persistent{
    scalaWorker.worker().compileScala(
      scalaVersion(),
      allSourceFiles().map(_.path),
      scalaCompilerBridgeSources(),
      compileClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }
  def localClasspath = T{
    resources() ++ Agg(compile().classes)
  }
  def compileClasspath = T{
    transitiveLocalClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{compileIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  def upstreamAssemblyClasspath = T{
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
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
    createAssembly(upstreamAssemblyClasspath().map(_.path), mainClass())
  }

  def assembly = T{
    createAssembly(
      Agg.from(localClasspath().map(_.path)),
      mainClass(),
      prependShellScript(),
      Some(upstreamAssembly().path)
    )
  }


  def jar = T{
    createJar(
      localClasspath().map(_.path).filter(exists),
      mainClass()
    )
  }

  def docJar = T {
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val files = for{
      ref <- allSources()
      if exists(ref.path)
      p <- ls.rec(ref.path)
      if p.isFile
    } yield p.toNIO.toString

    val options = Seq("-d", javadocDir.toNIO.toString, "-usejavacp")

    if (files.nonEmpty) subprocess(
      "scala.tools.nsc.ScalaDoc",
      scalaCompilerClasspath().map(_.path) ++ compileClasspath().filter(_.path.ext != "pom").map(_.path),
      mainArgs = (files ++ options).toSeq
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
    import coursier.{Cache, Fetch, Resolution}

    val flattened = ivyDeps().map(depToDependency(_, scalaVersion(), platformSuffix())).toSeq
    val start = Resolution(flattened.toSet)
    val fetch = Fetch.from(repositories, Cache.fetch())
    val resolution = start.process.run(fetch).unsafePerformSync

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
    Jvm.interactiveSubprocess(
      finalMainClass(),
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      args,
      workingDir = ammonite.ops.pwd
    )
  }


  def runMainLocal(mainClass: String, args: String*) = T.command {
    Jvm.runLocal(
      mainClass,
      runClasspath().map(_.path),
      args
    )
  }

  def runMain(mainClass: String, args: String*) = T.command{
    Jvm.interactiveSubprocess(
      mainClass,
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      args,
      workingDir = ammonite.ops.pwd
    )
  }

  def console() = T.command{
    if (T.ctx().log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.interactiveSubprocess(
        mainClass = "scala.tools.nsc.MainGenericRunner",
        classPath = runClasspath().map(_.path) ++ scalaCompilerClasspath().map(_.path),
        mainArgs = Seq("-usejavacp"),
        workingDir = pwd
      )
      Result.Success()
    }
  }

  def ammoniteReplClasspath = T{
    resolveDeps(T.task{Agg(ivy"com.lihaoyi:::ammonite:1.1.0-7-33b728c")})()
  }
  def repl() = T.command{
    if (T.ctx().log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.interactiveSubprocess(
        mainClass = "ammonite.Main",
        classPath = runClasspath().map(_.path) ++ ammoniteReplClasspath().map(_.path),
        mainArgs = Nil,
        workingDir = pwd
      )
      Result.Success()
    }

  }

  // publish artifact with name "mill_2.12.4" instead of "mill_2.12"
  def crossFullScalaVersion: T[Boolean] = false

  def artifactScalaVersion: T[String] = T {
    if (crossFullScalaVersion()) scalaVersion()
    else Lib.scalaBinaryVersion(scalaVersion())
  }
  def artifactName: T[String] = millModuleSegments.parts.mkString("-")

  def artifactSuffix: T[String] = T { s"_${artifactScalaVersion()}" }
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
trait TestModule extends ScalaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFrameworks: T[Seq[String]]

  def forkWorkingDir = ammonite.ops.pwd

  def test(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalaworker.ScalaWorker",
      classPath = scalaWorker.classpath(),
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
      workingDir = forkWorkingDir
    )

    val jsonOutput = ujson.read(outputPath.toIO)
    val (doneMsg, results) = upickle.default.readJs[(String, Seq[TestRunner.Result])](jsonOutput)
    TestModule.handleResults(doneMsg, results)

  }
  def testLocal(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    scalaWorker.worker().runTests(
      TestRunner.frameworks(testFrameworks()),
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args
    )

    val jsonOutput = ujson.read(outputPath.toIO)
    val (doneMsg, results) = upickle.default.readJs[(String, Seq[TestRunner.Result])](jsonOutput)
    TestModule.handleResults(doneMsg, results)

  }
}
