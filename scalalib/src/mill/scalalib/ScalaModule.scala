package mill
package scalalib

import ammonite.ops._
import coursier.Repository
import mill.define.Task
import mill.define.TaskModule
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createJar, subprocess}
import Dep.isDotty
import Lib._
import mill.util.Loose.Agg
import mill.util.DummyInputStream

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait ScalaModule extends JavaModule { outer =>
  trait Tests extends TestModule with ScalaModule{
    override def scalaOrganization = outer.scalaOrganization()
    def scalaVersion = outer.scalaVersion()
    override def repositories = outer.repositories
    override def scalacPluginIvyDeps = outer.scalacPluginIvyDeps
    override def scalacOptions = outer.scalacOptions
    override def javacOptions = outer.javacOptions
    override def scalaWorker = outer.scalaWorker
    override def moduleDeps: Seq[JavaModule] = Seq(outer)
  }

  def scalaOrganization: T[String] = T {
    if (isDotty(scalaVersion()))
      "ch.epfl.lamp"
    else
      "org.scala-lang"
  }

  def scalaVersion: T[String]

  override def mapDependencies = T.task{ d: coursier.Dependency =>
    val artifacts =
      if (isDotty(scalaVersion()))
        Set("dotty-library", "dotty-compiler")
      else
        Set("scala-library", "scala-compiler", "scala-reflect")
    if (!artifacts(d.module.name)) d
    else d.copy(module = d.module.copy(organization = scalaOrganization()), version = scalaVersion())
  }

  override def resolveCoursierDependency: Task[Dep => coursier.Dependency] = T.task{
    Lib.depToDependency(_: Dep, scalaVersion(), platformSuffix())
  }

  override def resolvePublishDependency: Task[Dep => publish.Dependency] = T.task{
    publish.Artifact.fromDep(
      _: Dep,
      scalaVersion(),
      Lib.scalaBinaryVersion(scalaVersion()),
      platformSuffix()
    )
  }

  override def finalMainClassOpt: T[Either[String, String]] = T{
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


  def scalacPluginIvyDeps = T{ Agg.empty[Dep] }

  def scalacOptions = T{ Seq.empty[String] }

  override def repositories: Seq[Repository] = scalaWorker.repositories

  private val Milestone213 = raw"""2.13.(\d+)-M(\d+)""".r

  def scalaCompilerBridgeSources = T {
    val (scalaVersion0, scalaBinaryVersion0) = scalaVersion() match {
      case Milestone213(_, _) => ("2.13.0-M2", "2.13.0-M2")
      case _ => (scalaVersion(), Lib.scalaBinaryVersion(scalaVersion()))
    }

    val (bridgeDep, bridgeName, bridgeVersion) =
      if (isDotty(scalaVersion0)) {
        val org = scalaOrganization()
        val name = "dotty-sbt-bridge"
        val version = scalaVersion()
        (ivy"$org:$name:$version", name, version)
      } else {
        val org = "org.scala-sbt"
        val name = "compiler-bridge"
        val version = Versions.zinc
        (ivy"$org::$name:$version", s"${name}_$scalaBinaryVersion0", version)
      }

    resolveDependencies(
      repositories,
      Lib.depToDependency(_, scalaVersion0, platformSuffix()),
      Seq(bridgeDep),
      sources = true
    ).map(deps =>
      grepJar(deps.map(_.path), bridgeName, bridgeVersion, sources = true)
    )
  }

  def scalacPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(scalacPluginIvyDeps)()
  }

  def scalaLibraryIvyDeps = T{ scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion()) }
  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Agg[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaOrganization(), scalaVersion()) ++
        scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion())}
    )()
  }
  override def compileClasspath = T{
    transitiveLocalClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{compileIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def upstreamAssemblyClasspath = T{
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def compile: T[CompilationResult] = T.persistent{
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

  override def docJar = T {
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val files = for{
      ref <- allSources()
      if exists(ref.path)
      p <- (if (ref.path.isDir) ls.rec(ref.path) else Seq(ref.path))
      if (p.isFile && ((p.ext == "scala") || (p.ext == "java")))
    } yield p.toNIO.toString

    val pluginOptions = scalacPluginClasspath().map(pluginPathRef => s"-Xplugin:${pluginPathRef.path}")
    val options = Seq("-d", javadocDir.toNIO.toString, "-usejavacp") ++ pluginOptions ++ scalacOptions()

    if (files.nonEmpty) subprocess(
      "scala.tools.nsc.ScalaDoc",
      scalaCompilerClasspath().map(_.path) ++ compileClasspath().filter(_.path.ext != "pom").map(_.path),
      mainArgs = (files ++ options).toSeq
    )

    createJar(Agg(javadocDir))(outDir)
  }

  def console() = T.command{
    if (T.ctx().log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.interactiveSubprocess(
        mainClass =
          if (isDotty(scalaVersion()))
            "dotty.tools.repl.Main"
          else
            "scala.tools.nsc.MainGenericRunner",
        classPath = runClasspath().map(_.path) ++ scalaCompilerClasspath().map(_.path),
        mainArgs = Seq("-usejavacp"),
        workingDir = pwd
      )
      Result.Success()
    }
  }

  def ammoniteReplClasspath = T{
    localClasspath() ++
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{
      runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps() ++
      Agg(ivy"com.lihaoyi:::ammonite:1.1.2")
    })()
  }

  def repl(replOptions: String*) = T.command{
    if (T.ctx().log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.interactiveSubprocess(
        mainClass = "ammonite.Main",
        classPath = ammoniteReplClasspath().map(_.path),
        mainArgs = replOptions,
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

  def artifactSuffix: T[String] = s"_${artifactScalaVersion()}"

  override def artifactId: T[String] = artifactName() + artifactSuffix()

}
