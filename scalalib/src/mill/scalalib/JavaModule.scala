package mill
package scalalib

import ammonite.ops._
import coursier.{Dependency, Repository}
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
trait JavaModule extends mill.Module with TaskModule { outer =>
  def defaultCommandName() = "run"

  def mainClass: T[Option[String]] = None

  def finalMainClassOpt: T[Either[String, String]] = T{
    mainClass() match{
      case Some(m) => Right(m)
      case None => Left("No main class specified or found")
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

  def moduleDeps = Seq.empty[JavaModule]


  def transitiveModuleDeps: Seq[JavaModule] = {
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

  def mapDependencies(d: coursier.Dependency) = d

  def resolveDeps(deps: Task[Agg[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      "???",
      deps(),
      platformSuffix(),
      sources,
      mapDependencies = Some(mapDependencies)
    )
  }


  def repositories: Seq[Repository] = ScalaWorkerModule.repositories

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
//    scalaWorker.worker().compileScala(
//      scalaVersion(),
//      allSourceFiles().map(_.path),
//      scalaCompilerBridgeSources(),
//      compileClasspath().map(_.path),
//      scalaCompilerClasspath().map(_.path),
//      scalacOptions(),
//      scalacPluginClasspath().map(_.path),
//      javacOptions(),
//      upstreamCompileOutput()
//    )
    Result.Failure[CompilationResult]("???", None)
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

  def docJar = T[PathRef] {
//    val outDir = T.ctx().dest
//
//    val javadocDir = outDir / 'javadoc
//    mkdir(javadocDir)
//
//    val files = for{
//      ref <- allSources()
//      if exists(ref.path)
//      p <- ls.rec(ref.path)
//      if p.isFile
//    } yield p.toNIO.toString
//
//    val options = Seq("-d", javadocDir.toNIO.toString, "-usejavacp")
//
//    if (files.nonEmpty) subprocess(
//      "scala.tools.nsc.ScalaDoc",
//      scalaCompilerClasspath().map(_.path) ++ compileClasspath().filter(_.path.ext != "pom").map(_.path),
//      mainArgs = (files ++ options).toSeq
//    )
//
//
//    createJar(Agg(javadocDir))(outDir)
    Result.Failure[PathRef]("", None)
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
      repositories, "???", ivyDeps(), platformSuffix(), Some(mapDependencies)
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

  // publish artifact with name "mill_2.12.4" instead of "mill_2.12"

  def artifactName: T[String] = millModuleSegments.parts.mkString("-")

  def artifactSuffix: T[String] = T { "" }
}