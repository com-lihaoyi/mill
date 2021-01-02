package mill
package scalalib

import coursier.Repository
import mill.define.{Command, Target, Task, TaskModule}
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.createJar
import mill.scalalib.api.Util.{ isDotty, isDottyOrScala3, isScala3 }
import Lib._
import mill.api.Loose.Agg
import mill.api.DummyInputStream

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait ScalaModule extends JavaModule { outer =>

  trait ScalaModuleTests extends JavaModuleTests with ScalaModule {
    override def scalaOrganization = outer.scalaOrganization()
    def scalaVersion = outer.scalaVersion()
    override def scalacPluginIvyDeps = outer.scalacPluginIvyDeps
    override def scalacPluginClasspath = outer.scalacPluginClasspath
    override def scalacOptions = outer.scalacOptions
  }
  trait Tests extends ScalaModuleTests

  /**
    * What Scala organization to use
    * @return
    */
  def scalaOrganization: T[String] = T {
    if (isDotty(scalaVersion()))
      "ch.epfl.lamp"
    else
      "org.scala-lang"
  }

  /**
   * All individual source files fed into the Zinc compiler.
   */
  override def allSourceFiles = T{
    def isHiddenFile(path: os.Path) = path.last.startsWith(".")
    for {
      root <- allSources()
      if os.exists(root.path)
      path <- if (os.isDir(root.path)) os.walk(root.path) else Seq(root.path)
      if os.isFile(path) && ((path.ext == "scala" || path.ext == "java") && !isHiddenFile(path))
    } yield PathRef(path)
  }

  /**
    * What version of Scala to use
    */
  def scalaVersion: T[String]

  override def mapDependencies = T.task{ d: coursier.Dependency =>
    val artifacts =
      if (isDotty(scalaVersion()))
        Set("dotty-library", "dotty-compiler")
      else if (isScala3(scalaVersion()))
        Set("scala3-library", "scala3-compiler")
      else
        Set("scala-library", "scala-compiler", "scala-reflect")
    if (!artifacts(d.module.name.value)) d
    else d.withModule(
      d.module.withOrganization(
        coursier.Organization(scalaOrganization())
      )
    ).withVersion(scalaVersion())
  }

  override def resolveCoursierDependency: Task[Dep => coursier.Dependency] = T.task{
    Lib.depToDependency(_: Dep, scalaVersion(), platformSuffix())
  }

  override def resolvePublishDependency: Task[Dep => publish.Dependency] = T.task{
    publish.Artifact.fromDep(
      _: Dep,
      scalaVersion(),
      mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion()),
      platformSuffix()
    )
  }

  /**
    * Allows you to make use of Scala compiler plugins from maven central
    */
  def scalacPluginIvyDeps = T{ Agg.empty[Dep] }

  def scalaDocPluginIvyDeps = T{ scalacPluginIvyDeps() }

  /**
    * Command-line options to pass to the Scala compiler
    */
  def scalacOptions = T{ Seq.empty[String] }

  def scalaDocOptions: T[Seq[String]] = T{
    val defaults = if (isDottyOrScala3(scalaVersion())) Seq(
      "-project", artifactName()
    ) else Seq()
    scalacOptions() ++ defaults
  }

  /**
    * The local classpath of Scala compiler plugins on-disk; you can add
    * additional jars here if you have some copiler plugin that isn't present
    * on maven central
    */
  def scalacPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(scalacPluginIvyDeps)()
  }

  /**
    * Classpath of the scaladoc (or dottydoc) tool.
    */
  def scalaDocClasspath: T[Agg[PathRef]] = T {
    resolveDeps(
      T.task{scalaDocIvyDeps(scalaOrganization(), scalaVersion())}
    )()
  }

  /**
    * The ivy coordinates of Scala's own standard library
    */
  def scalaDocPluginClasspath: T[Agg[PathRef]] = T {
    resolveDeps(scalaDocPluginIvyDeps)()
  }

  def scalaLibraryIvyDeps = T{ scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion()) }

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Agg[PathRef]] = T{
    resolveDeps(
      T.task{
        scalaCompilerIvyDeps(scalaOrganization(), scalaVersion()) ++
        scalaRuntimeIvyDeps(scalaOrganization(), scalaVersion())
      }
    )()
  }

  override def resolvedIvyDeps: T[Agg[PathRef]] = T {
    resolveDeps(T.task{transitiveCompileIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def resolvedRunIvyDeps: T[Agg[PathRef]] = T {
    resolveDeps(T.task{runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def compile: T[mill.scalalib.api.CompilationResult] = T.persistent {

    zincWorker.worker().compileMixed(
      upstreamCompileOutput(),
      allSourceFiles().map(_.path),
      compileClasspath().map(_.path),
      javacOptions(),
      scalaVersion(),
      scalaOrganization(),
      scalacOptions(),
      scalaCompilerClasspath().map(_.path),
      scalacPluginClasspath().map(_.path),
      T.reporter.apply(hashCode)
    )
  }

  override def docJar = T {
    val outDir = T.dest

    val javadocDir = outDir / 'javadoc
    os.makeDir.all(javadocDir)

    if (isDottyOrScala3(scalaVersion())) {
      // merge all docSources into one directory by copying all children
      for {
        ref <- docSources()
        docSource = ref.path
        if os.exists(docSource) && os.isDir(docSource)
        children = os.walk(docSource)
        child <- children
        if os.isFile(child)
      } {
        os.copy.over(child, javadocDir / (child.subRelativeTo(docSource)), createFolders = true)
      }
    }

    val files = allSourceFiles().map(_.path.toString)

    val outputOptions =
      if (isDottyOrScala3(scalaVersion()))
        Seq("-siteroot", javadocDir.toNIO.toString)
      else
        Seq("-d", javadocDir.toNIO.toString)

    val pluginOptions = scalaDocPluginClasspath().map(pluginPathRef => s"-Xplugin:${pluginPathRef.path}")
    val compileCp = compileClasspath().filter(_.path.ext != "pom").map(_.path)
    val options = Seq(
      "-classpath", compileCp.mkString(java.io.File.pathSeparator)
    ) ++
      outputOptions ++
      pluginOptions ++
      scalaDocOptions() // user options come last, so they can override any other settings

    if (files.isEmpty) Result.Success(createJar(Agg(javadocDir))(outDir))
    else {
      zincWorker.worker().docJar(
        scalaVersion(),
        scalaOrganization(),
        scalaDocClasspath().map(_.path),
        scalacPluginClasspath().map(_.path),
        files ++ options
      ) match{
        case true =>
          val inputPath =
            if (isDottyOrScala3(scalaVersion())) javadocDir / '_site
            else javadocDir
          Result.Success(createJar(Agg(inputPath))(outDir))
        case false => Result.Failure("docJar generation failed")
      }
    }
  }

  /**
    * Opens up a Scala console with your module and all dependencies present,
    * for you to test and operate your code interactively
    */
  def console() = T.command{
    if (T.log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.runSubprocess(
        mainClass =
          if (isDottyOrScala3(scalaVersion()))
            "dotty.tools.repl.Main"
          else
            "scala.tools.nsc.MainGenericRunner",
        classPath = runClasspath().map(_.path) ++ scalaCompilerClasspath().map(_.path),
        mainArgs = Seq("-usejavacp"),
        workingDir = os.pwd
      )
      Result.Success()
    }
  }

  /**
   * Ammonite's version used in the `repl` command is by default
   * set to the one Mill is built against.
   */
  def ammoniteVersion = T(Versions.ammonite)

  /**
    * Dependencies that are necessary to run the Ammonite Scala REPL
    */
  def ammoniteReplClasspath = T{
    localClasspath() ++
    transitiveLocalClasspath() ++
    unmanagedClasspath() ++
    resolvedAmmoniteReplIvyDeps()
  }

  def resolvedAmmoniteReplIvyDeps = T{
    resolveDeps(T.task{
      runIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps() ++
        Agg(ivy"com.lihaoyi:::ammonite:${ammoniteVersion()}")
    })()
  }

  /**
    * Opens up an Ammonite Scala REPL with your module and all dependencies present,
    * for you to test and operate your code interactively
    */
  def repl(replOptions: String*) = T.command{
    if (T.log.inStream == DummyInputStream){
      Result.Failure("repl needs to be run with the -i/--interactive flag")
    }else{
      Jvm.runSubprocess(
        mainClass = "ammonite.Main",
        classPath = ammoniteReplClasspath().map(_.path),
        mainArgs = replOptions,
        workingDir = os.pwd
      )
      Result.Success()
    }

  }

  /**
    * Whether to publish artifacts with name "mill_2.12.4" instead of "mill_2.12"
    */
  def crossFullScalaVersion: T[Boolean] = false

  /**
    * What Scala version string to use when publishing
    */
  def artifactScalaVersion: T[String] = T {
    if (crossFullScalaVersion()) scalaVersion()
    else mill.scalalib.api.Util.scalaBinaryVersion(scalaVersion())
  }

  /**
    * The suffix appended to the artifact IDs during publishing
    */
  def artifactSuffix: T[String] = s"_${artifactScalaVersion()}"

  override def artifactId: T[String] = artifactName() + artifactSuffix()

  override def prepareOffline(): Command[Unit] = T.command {
    super.prepareOffline()
    resolveDeps(scalacPluginIvyDeps)()
    resolveDeps(scalaDocPluginIvyDeps)()
    resolvedAmmoniteReplIvyDeps()
    ()
  }
}
