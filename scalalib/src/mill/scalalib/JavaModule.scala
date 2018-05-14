package mill
package scalalib


import ammonite.ops._
import coursier.Repository
import mill.define.Task
import mill.define.Persistent
import mill.define.Target
import mill.define.Input
import mill.define.TaskModule
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar}
import Lib._
import mill.scalalib.publish.{Artifact, Scope}
import mill.util.Loose.Agg

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait JavaModule extends mill.Module with TaskModule with PackageWarSimple { outer =>
  trait Tests extends TestModule{
    override def moduleDeps = Seq(outer)
    override def repositories = outer.repositories
    override def javacOptions = outer.javacOptions
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
      resolveCoursierDependency().apply(_),
      deps(),
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

  def compile: T[CompilationResult] = T{
    Lib.compileJava(
      allSourceFiles().map(_.path.toIO).toArray,
      compileClasspath().map(_.path.toIO).toArray,
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
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val files = for{
      ref <- allSources()
      if exists(ref.path)
      p <- ls.rec(ref.path)
      if p.isFile
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
      repositories, resolveCoursierDependency().apply(_), transitiveIvyDeps(), Some(mapDependencies)
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
      workingDir = ammonite.ops.pwd
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
      workingDir = ammonite.ops.pwd
    )) catch { case e: InteractiveShelloutException =>
      Result.Failure("subprocess failed")
    }
  }

  // publish artifact with name "mill_2.12.4" instead of "mill_2.12"

  def artifactName: T[String] = millModuleSegments.parts.mkString("-")

  def artifactId: T[String] = artifactName()

  def intellijModulePath: Path = millSourcePath
}

trait TestModule extends JavaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFrameworks: T[Seq[String]]

  def forkWorkingDir = ammonite.ops.pwd

  def test(args: String*) = T.command{
    val outputPath = T.ctx().dest/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalalib.worker.ScalaWorker",
      classPath = ScalaWorkerModule.classpath(),
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

    Lib.runTests(
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

trait PackageWar extends mill.Module {
  self: JavaModule =>

  private def mkDir(dir: Path): Path = {
    mkdir ! dir
    dir
  }

  // persistent folder to prepare war layout
  def warPreparationFolder: Persistent[Path] = T.persistent {
    T.ctx().dest
  }

  def baseDir: Target[Path] = T {
    mkDir(warPreparationFolder() / "WEB-INF")
  }

  def libDir: Target[Path] = T {
    mkDir(baseDir() / 'lib)
  }

  def classDir: Target[Path] = T {
    mkDir(baseDir() / 'classes)
  }

  def warFileName: Target[String] = T {
    "out.war"
  }

  // disabling war compression yields significant speedup for minor space gains.
  // production war files might want to use compression
  def warUseCompression: Target[Boolean] = T {
    false
  }

  def webAssets = T.sources {
    Seq.empty
  }

  // web.xml is not really needed anymore but let's support it
  def webXmlFile = T.sources {
    Seq.empty
  }

  def manifestEntries: Target[Map[java.util.jar.Attributes.Name, String]] = T {
    Map(
      java.util.jar.Attributes.Name.MANIFEST_VERSION -> "1.0",
      new java.util.jar.Attributes.Name("Created-By") -> s"mill version ${System.getProperty("MILL_VERSION")}"
    )
  }

  def manifestEntriesFile: Target[PathRef] = T {
    val manifestEntriesFile = T.ctx().dest / 'manifest_entries
    write.over(
      manifestEntriesFile,
      manifestEntries()
        .map({ case (k, v) => s"${k.toString}: $v" })
        .mkString("", ammonite.util.Util.newLine, ammonite.util.Util.newLine))
    PathRef(manifestEntriesFile)
  }


  def copyWebXml: Target[Long] = T {
    val file = webXmlFile()
    assert(file.size <= 1)
    file
      .headOption
      .map(pr => copyMtime(pr.path, baseDir() / pr.path.name))
      .getOrElse(0l)
  }

  def copyJar: Target[PathRef] = T {
    // rename jar into artifact name
    val fileToWrite = libDir() / s"${artifactName()}.jar"
    cp.over(jar().path, fileToWrite)
    PathRef(fileToWrite, true)
  }

  // select one of the two available strategies for a changed classpath
  def copyClasspath: Target[Long] = T {
    copyFullClasspath()
  }

  def copyModuleDeps = T {
    Task
      .traverse(moduleDeps)(m =>
        T.task {
          (s"${m.artifactName()}.jar", m.jar())
        })()
      .map(aj => {
        // properly name artifacts before copying
        val fileToWrite = libDir() / aj._1
        cp.over(aj._2.path, fileToWrite)
        PathRef(fileToWrite, true)
      })
  }

  def copyWebAssets = T {
    copyChanges(webAssets(), warPreparationFolder(), filterTarget = p => !(p.startsWith(baseDir())))
  }

  def packageWar: Target[PathRef] = T {
    // needs to be first as it might remove the libdir for better performance
    copyClasspath() // TODO: copy classfiles from the classpath into class folder
    copyModuleDeps()
    copyJar() // TODO: add option to copy class files instead of creating a jar
    copyWebXml()
    copyWebAssets()
    val params = "cmf" + (if (warUseCompression()) "" else "0")
    %%("jar", params, manifestEntriesFile().path.toString, T.ctx().dest / warFileName(), ".")(warPreparationFolder())
    PathRef(T.ctx().dest / warFileName())
  }


  // if anything changes, just copy everything
  // fastest but lots IO
  // uses change time instead of PathRef as computing the signature of a large classpath can take significant time
  def copyFullClasspath: Target[Long] = T {
    val lDir = libDir()
    val cDir = classDir()
    rm ! lDir
    rm ! cDir
    mkdir ! lDir
    mkdir ! cDir
    runClasspath()
      .filter(p => exists(p.path) && p.path.name.endsWith(".jar"))
      .foreach(pr => copyMtime(pr.path, lDir / pr.path.name))
    lDir.mtime.toMillis
  }

  def copyChangedClassPath: Target[Long] = T {
    // TODO: class files in classpath?
    copyChanges(runClasspath(), libDir(), _.path.name.endsWith(".jar"))
  }

  def compareChanges: (Path, PathRef) => Boolean = compareMtimeAndSize

  val compareMtimeAndSize = (p: Path, pr: PathRef) => {
    p.size == pr.path.size && p.mtime == pr.path.mtime
  }

  def comparePathRef(quick: Boolean): (Path, PathRef) => Boolean = (p: Path, pr: PathRef) => {
    val lpr = if (pr.quick == quick) {
      pr
    } else {
      PathRef(pr.path, quick)
    }
    PathRef(p, quick).sig == lpr.sig
  }

  // if anything changes, only copy the added deps and delete the removed ones
  // uses change time instead of PathRef as computing the signature of a large classpath can take significant time
  private def copyChanges(src: Seq[PathRef], target: Path, filterSrc: PathRef => Boolean = _ => true, filterTarget: Path => Boolean = _ => true) = {
    // speed up lookups by using a map
    val existingFilesMap =
      ls
        .rec(target)
        .filter(filterTarget)
        .map(p => (p.name, p))
        .toMap
    val newFilesMap =
      src
        .filter(p => exists(p.path) && filterSrc(p))
        .map(p => (p.path.name, p))
        .toMap
    // delete existing files no longer needed
    existingFilesMap
      .filterKeys(!newFilesMap.contains(_))
      .values
      .foreach(rm ! _)
    // copy files from classpath not yet existing
    // -> either file not present or present but differs
    newFilesMap
      .filterNot(kv => {
        existingFilesMap
          .get(kv._1)
          .map(p => compareChanges(p, kv._2))
          .getOrElse(false)
      })
      .values
      .foreach(pr => {
        if (pr.path.isDir) {
          ls
            .rec(pr.path)
            .foreach(p => copyMtime(p, target / p.relativeTo(pr.path)))
        } else {
          copyMtime(pr.path, target / pr.path.name)
        }
      })
    System.currentTimeMillis()
  }

  private def copyMtime(from: Path, to: Path): Long = {
    cp.over(from, to)
    to.toIO.setLastModified(from.mtime.toMillis)
    from.mtime.toMillis
  }

  implicit val rw: upickle.default.ReadWriter[java.util.jar.Attributes.Name] =
    upickle.default.readwriter[String].bimap[java.util.jar.Attributes.Name](
      attName => attName.toString,
      str => new java.util.jar.Attributes.Name(str))

}

trait PackageWarSimple extends mill.Module {
  self: JavaModule =>

  def warFileName: Target[String] = T {
    "out.war"
  }

  // disabling war compression yields significant speedup for minor space gains.
  // production war files might want to use compression
  def warUseCompression: Target[Boolean] = T {
    false
  }

  def webAssets = T.sources {
    Seq.empty
  }

  // web.xml is not really needed anymore but let's support it
  def webXmlFile: Input[Option[PathRef]] = T.input {
    None
  }

  def manifestEntries: Target[Map[java.util.jar.Attributes.Name, String]] = T {
    Map(
      java.util.jar.Attributes.Name.MANIFEST_VERSION -> "1.0",
      new java.util.jar.Attributes.Name("Created-By") -> s"mill version ${System.getProperty("MILL_VERSION")}"
    )
  }

  def packageWar: Target[PathRef] = T {
    // folder layout:
    // war  /  WEB-INF /  lib      /  <jars>
    //                 /  classes  /  <classfiles>
    //                 /  web.xml
    //                 /  <resources>
    //     / manifest_entries
    val baseDir = T.ctx().dest / 'webapp
    val webInfDir = baseDir / "WEB-INF"
    val libDir = webInfDir / 'lib
    mkdir ! libDir
    // copy jars from classpath into META-INF/lib
    // TODO: copy clases
    runClasspath()
      .filter(p => exists(p.path) && p.path.name.endsWith(".jar"))
      .foreach(pr => cp.into(pr.path, libDir))
    // copy module deps into META-INF/lib
    Task
      .traverse(moduleDeps)(m =>
        T.task {
          (m.jar().path, m.artifactName())
        })()
      .foreach(pn => cp.over(pn._1, libDir / s"${pn._2}.jar"))
    // copy jar into into META-INF/lib
    cp.over(jar().path, libDir / s"${artifactName()}.jar")
    // copy web.xml into META-INF
    webXmlFile()
      .foreach(pr => cp.into(pr.path, webInfDir))
    // copy assets into root of war - allows to overwrite anything?
    webAssets()
      .foreach(pr => {
        if (pr.path.isDir)
          ls.rec(pr.path).foreach(p => {
            // skip already existings dirs
            if (!(p.isDir && exists(p)))
              cp.over(p, baseDir / p.relativeTo(pr.path))
          })
        else
          cp(pr.path, baseDir)
      })
    // prepare manifest entries - not part of the war itself
    val manifestEntriesFile = T.ctx().dest / 'manifest_entries
    write(
      manifestEntriesFile,
      manifestEntries()
        .iterator
        .map({ case (k, v) => s"${k.toString}: $v" })
        .mkString("", ammonite.util.Util.newLine, ammonite.util.Util.newLine))
    // build war file
    val params = "cmf" + (if (warUseCompression()) "" else "0")
    %%("jar", params, manifestEntriesFile.toString, T.ctx().dest / warFileName(), ".")(baseDir)
    PathRef(T.ctx().dest / warFileName())
  }

  implicit val rw: upickle.default.ReadWriter[java.util.jar.Attributes.Name] =
    upickle.default.readwriter[String].bimap[java.util.jar.Attributes.Name](
      attName => attName.toString,
      str => new java.util.jar.Attributes.Name(str))

}
