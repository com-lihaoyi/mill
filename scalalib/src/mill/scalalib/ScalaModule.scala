package mill
package scalalib

import ammonite.ops._
import coursier.{Cache, MavenRepository, Repository}
import mill.define.{Cross, Task}
import mill.define.TaskModule
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar, interactiveSubprocess, runLocal, subprocess}
import Lib._
import mill.define.Cross.Resolver
import mill.util.Loose.Agg
import mill.util.Strict

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait ScalaModule extends mill.Module with TaskModule { outer =>
  def defaultCommandName() = "run"
  trait Tests extends TestModule{
    def scalaVersion = outer.scalaVersion()
    override def moduleDeps = Seq(outer)
  }
  def scalaVersion: T[String]
  def mainClass: T[Option[String]] = None

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

  def upstreamRunClasspath: T[Agg[PathRef]] = T{
    Task.traverse(moduleDeps)(_.runClasspath)().flatten
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


  def repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def platformSuffix = T{ "" }

  def scalaCompilerBridgeSources = T{
    resolveDependencies(
      repositories,
      scalaVersion(),
      Seq(ivy"org.scala-sbt::compiler-bridge:1.1.0"),
      sources = true
    )
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


  def prependShellScript: T[String] = T{ "" }

  def sources = T.sources{ millSourcePath / 'src }
  def resources = T.sources{ millSourcePath / 'resources }
  def generatedSources = T{ Seq.empty[PathRef] }
  def allSources = T{ sources() ++ generatedSources() }

  def compile: T[CompilationResult] = T.persistent{
    mill.scalalib.ScalaWorkerApi.scalaWorker().compileScala(
      scalaVersion(),
      allSources().map(_.path),
      scalaCompilerBridgeSources().map(_.path),
      compileClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }

  def compileClasspath = T{
    upstreamRunClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{compileIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  def runClasspath = T{
    upstreamRunClasspath() ++
    Agg(compile().classes) ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  def assembly = T{
    createAssembly(
      runClasspath().map(_.path).filter(exists),
      mainClass(),
      prependShellScript = prependShellScript()
    )
  }


  def jar = T{
    createJar(
      (resources() ++ Seq(compile().classes)).map(_.path).filter(exists),
      mainClass()
    )
  }

  def docsJar = T {
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
      runClasspath().filter(_.path.ext != "pom").map(_.path),
      mainArgs = (files ++ options).toSeq
    )

    createJar(Agg(javadocDir))(outDir)
  }

  def sourceJar = T {
    createJar((allSources() ++ resources()).map(_.path).filter(exists))
  }

  def forkArgs = T{ Seq.empty[String] }

  def forkEnv = T{ sys.env.toMap }

  def runLocal(args: String*) = T.command {
    Jvm.runLocal(
      mainClass().getOrElse(throw new RuntimeException("No mainClass provided!")),
      runClasspath().map(_.path),
      args
    )
  }

  def run(args: String*) = T.command{
    Jvm.interactiveSubprocess(
      mainClass().getOrElse(throw new RuntimeException("No mainClass provided!")),
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      args,
      workingDir = ammonite.ops.pwd)
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
    Jvm.interactiveSubprocess(
      mainClass = "scala.tools.nsc.MainGenericRunner",
      classPath = runClasspath().map(_.path),
      mainArgs = Seq("-usejavacp")
    )
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
      val suffix = if (badTests.length == 1) "" else "and " + (badTests.length-1) + " more"

      Result.Failure(
        badTests.head.fullyQualifiedName + " " + badTests.head.selector + suffix,
        Some((doneMsg, results))
      )
    }
  }
}
trait TestModule extends ScalaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFramework: T[String]

  def forkWorkingDir = ammonite.ops.pwd

  def test(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalaworker.ScalaWorker",
      classPath = mill.scalalib.ScalaWorkerApi.scalaWorkerClasspath(),
      jvmArgs = forkArgs(),
      envArgs = forkEnv(),
      mainArgs = Seq(
        testFramework(),
        runClasspath().map(_.path).mkString(" "),
        Seq(compile().classes.path).mkString(" "),
        args.mkString(" "),
        outputPath.toString,
        T.ctx().log.colored.toString
      ),
      workingDir = forkWorkingDir
    )

    val jsonOutput = upickle.json.read(outputPath.toIO)
    val (doneMsg, results) = upickle.default.readJs[(String, Seq[TestRunner.Result])](jsonOutput)
    TestModule.handleResults(doneMsg, results)

  }
  def testLocal(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    mill.scalalib.ScalaWorkerApi.scalaWorker().runTests(
      TestRunner.framework(testFramework()),
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args
    )

    val jsonOutput = upickle.json.read(outputPath.toIO)
    val (doneMsg, results) = upickle.default.readJs[(String, Seq[TestRunner.Result])](jsonOutput)
    TestModule.handleResults(doneMsg, results)

  }
}
