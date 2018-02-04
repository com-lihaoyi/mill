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

  def repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def moduleDeps = Seq.empty[ScalaModule]
  def depClasspath = T{ Agg.empty[PathRef] }


  def upstreamRunClasspath = T{
    Task.traverse(moduleDeps)(p =>
      T.task(p.runDepClasspath() ++ p.runClasspath())
    )
  }

  def upstreamCompileOutput = T{
    Task.traverse(moduleDeps)(_.compile)
  }
  def upstreamCompileClasspath = T{
    externalCompileDepClasspath() ++
    upstreamCompileOutput().map(_.classes) ++
    Task.traverse(moduleDeps)(_.compileDepClasspath)().flatten
  }

  def resolveDeps(deps: Task[Agg[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      scalaVersion(),
      deps(),
      sources
    )
  }

  def externalCompileDepClasspath: T[Agg[PathRef]] = T{
    Agg.from(Task.traverse(moduleDeps)(_.externalCompileDepClasspath)().flatten) ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())}
    )()
  }

  def externalCompileDepSources: T[Agg[PathRef]] = T{
    Agg.from(Task.traverse(moduleDeps)(_.externalCompileDepSources)().flatten) ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())},
      sources = true
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to compile;
    * might be less than the runtime classpath
    */
  def compileDepClasspath: T[Agg[PathRef]] = T{
    upstreamCompileClasspath() ++
    depClasspath()
  }

  def compilerBridgeSources = T{
    resolveDependencies(
      repositories,
      scalaVersion(),
      Seq(ivy"org.scala-sbt::compiler-bridge:1.1.0"),
      sources = true
    )
  }

  def scalacPluginClasspath: T[Agg[PathRef]] =
    resolveDeps(
      T.task{scalacPluginIvyDeps()}
    )()

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Agg[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())}
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to run
    */
  def runDepClasspath: T[Agg[PathRef]] = T{
    Agg.from(upstreamRunClasspath().flatten) ++
    depClasspath() ++
    resolveDeps(
      T.task{ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())}
    )()
  }

  def prependShellScript: T[String] = T{ "" }

  def sources = T.sources{ millSourcePath / 'src }
  def resources = T.sources{ millSourcePath / 'resources }
  def generatedSources = T.sources()
  def allSources = T{ sources() ++ generatedSources() }

  def compile: T[CompilationResult] = T.persistent{
    mill.scalalib.ScalaWorkerApi.scalaWorker().compileScala(
      scalaVersion(),
      allSources().map(_.path),
      compilerBridgeSources().map(_.path),
      compileDepClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      scalacPluginClasspath().map(_.path),
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }
  
  def runClasspath = T{
    runDepClasspath() ++ resources() ++ Seq(compile().classes)
  }

  def assembly = T{
    createAssembly(
      runClasspath().map(_.path).filter(exists),
      mainClass(),
      prependShellScript = prependShellScript()
    )
  }

  def localClasspath = T{ resources() ++ Seq(compile().classes) }

  def jar = T{
    createJar(
      localClasspath().map(_.path).filter(exists),
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
      compileDepClasspath().filter(_.path.ext != "pom").map(_.path),
      mainArgs = (files ++ options).toSeq
    )

    createJar(Agg(javadocDir))(outDir)
  }

  def sourcesJar = T {
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

    val badStatuses = results.map(_.status).filter(Set("Error", "Failure"))
    if (badStatuses.isEmpty) {
      Result.Success((doneMsg, results))
    } else {
      val grouped = badStatuses.groupBy(x => x).mapValues(_.length).toList.sorted

      Result.Failure(grouped.map{case (k, v) => k + ": " + v}.mkString(","))
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

    mill.scalalib.ScalaWorkerApi.scalaWorker().apply(
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
