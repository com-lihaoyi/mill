package mill
package scalaplugin

import ammonite.ops._
import coursier.{Cache, MavenRepository, Repository}
import mill.define.Task
import mill.define.Task.{Module, TaskModule}
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar, interactiveSubprocess, subprocess}

import Lib._
trait TestScalaModule extends ScalaModule with TaskModule {
  override def defaultCommandName() = "test"
  def testFramework: T[String]

  def forkWorkingDir = ammonite.ops.pwd
  def forkArgs = T{ Seq.empty[String] }
  def forkTest(args: String*) = T.command{
    val outputPath = tmp.dir()/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalaplugin.TestRunner",
      classPath = Jvm.gatherClassloaderJars(),
      jvmOptions = forkArgs(),
      options = Seq(
        testFramework(),
        (runDepClasspath().map(_.path) :+ compile().classes.path).mkString(" "),
        Seq(compile().classes.path).mkString(" "),
        args.mkString(" "),
        outputPath.toString
      ),
      workingDir = forkWorkingDir
    )
    upickle.default.read[Option[String]](ammonite.ops.read(outputPath)) match{
      case Some(errMsg) => Result.Failure(errMsg)
      case None => Result.Success(())
    }
  }
  def test(args: String*) = T.command{
    TestRunner(
      testFramework(),
      runDepClasspath().map(_.path) :+ compile().classes.path,
      Seq(compile().classes.path),
      args
    ) match{
      case Some(errMsg) => Result.Failure(errMsg)
      case None => Result.Success(())
    }
  }
}

trait ScalaModule extends Module with TaskModule { outer =>
  def defaultCommandName() = "run"
  trait Tests extends TestScalaModule{
    def scalaVersion = outer.scalaVersion()
    override def projectDeps = Seq(outer)
  }
  def scalaVersion: T[String]
  def mainClass: T[Option[String]] = None

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ Seq[Dep]() }
  def compileIvyDeps = T{ Seq[Dep]() }
  def scalacPluginIvyDeps = T{ Seq[Dep]() }
  def runIvyDeps = T{ Seq[Dep]() }
  def basePath: Path

  def scalacOptions = T{ Seq.empty[String] }
  def javacOptions = T{ Seq.empty[String] }

  def repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def projectDeps = Seq.empty[ScalaModule]
  def depClasspath = T{ Seq.empty[PathRef] }


  def upstreamRunClasspath = T{
    Task.traverse(projectDeps)(p =>
      T.task(p.runDepClasspath() ++ Seq(p.compile().classes, p.resources()))
    )
  }

  def upstreamCompileOutput = T{
    Task.traverse(projectDeps)(_.compile)
  }
  def upstreamCompileClasspath = T{
    externalCompileDepClasspath() ++
    upstreamCompileOutput().map(_.classes) ++
    Task.traverse(projectDeps)(_.compileDepClasspath)().flatten
  }

  def resolveDeps(deps: Task[Seq[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      deps(),
      sources
    )
  }

  def externalCompileDepClasspath: T[Seq[PathRef]] = T{
    Task.traverse(projectDeps)(_.externalCompileDepClasspath)().flatten ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())}
    )()
  }

  def externalCompileDepSources: T[Seq[PathRef]] = T{
    Task.traverse(projectDeps)(_.externalCompileDepSources)().flatten ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())},
      sources = true
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to compile;
    * might be less than the runtime classpath
    */
  def compileDepClasspath: T[Seq[PathRef]] = T{
    upstreamCompileClasspath() ++
    depClasspath()
  }

  /**
    * Strange compiler-bridge jar that the Zinc incremental compile needs
    */
  def compilerBridge: T[PathRef] = T{
    val compilerBridgeKey = "MILL_COMPILER_BRIDGE_" + scalaVersion().replace('.', '_')
    val compilerBridgePath = sys.props(compilerBridgeKey)
    if (compilerBridgePath != null) PathRef(Path(compilerBridgePath), quick = true)
    else {
      val dep = compilerBridgeIvyDep(scalaVersion())
      val classpath = resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        Seq(dep)
      )
      classpath match {
        case Result.Success(resolved) =>
          resolved.filterNot(_.path.ext == "pom") match {
            case Seq(single) => PathRef(single.path, quick = true)
            case Seq() => throw new Exception(dep + " resolution failed") // TODO: find out, is it possible?
            case _ => throw new Exception(dep + " resolution resulted in more than one file")
          }
        case f: Result.Failure => throw new Exception(dep + s" resolution failed.\n + ${f.msg}") // TODO: remove, resolveDependencies will take care of this.
      }
    }
  }

  def scalacPluginClasspath: T[Seq[PathRef]] =
    resolveDeps(
      T.task{scalacPluginIvyDeps()}
    )()

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[Seq[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())}
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to run
    */
  def runDepClasspath: T[Seq[PathRef]] = T{
    upstreamRunClasspath().flatten ++
    depClasspath() ++
    resolveDeps(
      T.task{ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())},
    )()
  }

  def prependShellScript: T[String] = T{ "" }

  def sources = T.source{ basePath / 'src }
  def resources = T.source{ basePath / 'resources }
  def allSources = T{ Seq(sources()) }
  def compile: T[CompilationResult] = T.persistent{
    compileScala(
      ZincWorker(),
      scalaVersion(),
      allSources().map(_.path),
      compileDepClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      scalacPluginClasspath().map(_.path),
      compilerBridge().path,
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }
  def assemblyClasspath = T{
    (runDepClasspath().filter(_.path.ext != "pom") ++
    Seq(resources(), compile().classes)).map(_.path).filter(exists)
  }

  def assembly = T{
    createAssembly(assemblyClasspath(), prependShellScript = prependShellScript())
  }

  def classpath = T{ Seq(resources(), compile().classes) }

  def jar = T{
    createJar(
      Seq(resources(), compile().classes).map(_.path).filter(exists),
      mainClass()
    )
  }

  def docsJar = T {
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val options = {
      val files = ls.rec(sources().path).filter(_.isFile).map(_.toNIO.toString)
      files ++ Seq("-d", javadocDir.toNIO.toString, "-usejavacp")
    }

    subprocess(
      "scala.tools.nsc.ScalaDoc",
      compileDepClasspath().filterNot(_.path.ext == "pom").map(_.path),
      options = options
    )

    createJar(Seq(javadocDir))(outDir / "javadoc.jar")
  }

  def sourcesJar = T {
    createJar(Seq(sources(), resources()).map(_.path).filter(exists))(T.ctx().dest / "sources.jar")
  }

  def run() = T.command{
    val main = mainClass().getOrElse(throw new RuntimeException("No mainClass provided!"))
    subprocess(main, runDepClasspath().map(_.path) :+ compile().classes.path)
  }

  def runMain(mainClass: String) = T.command{
    subprocess(mainClass, runDepClasspath().map(_.path) :+ compile().classes.path)
  }

  def console() = T.command{
    interactiveSubprocess(
      mainClass = "scala.tools.nsc.MainGenericRunner",
      classPath = externalCompileDepClasspath().map(_.path) :+ compile().classes.path,
      options = Seq("-usejavacp")
    )
  }
}

trait PublishModule extends ScalaModule { outer =>
  import mill.scalaplugin.publish._

  def publishName: T[String] = basePath.last.toString
  def publishVersion: T[String] = "0.0.1-SNAPSHOT"
  def pomSettings: T[PomSettings]

  // publish artifact with name "mill_2.12.4" instead of "mill_2.12"
  def publishWithFullScalaVersion: Boolean = false

  def artifactScalaVersion: T[String] = T {
    if (publishWithFullScalaVersion) scalaVersion()
    else scalaBinaryVersion()
  }

  def pom = T {
    val dependencies =
      ivyDeps().map(Artifact.fromDep(_, scalaVersion(), scalaBinaryVersion()))
    val pom = Pom(artifact(), dependencies, publishName(), pomSettings())

    val pomPath = T.ctx().dest / s"${publishName()}_${artifactScalaVersion()}-${publishVersion()}.pom"
    write.over(pomPath, pom)
    PathRef(pomPath)
  }

  def ivy = T {
    val dependencies =
      ivyDeps().map(Artifact.fromDep(_, scalaVersion(), scalaBinaryVersion()))
    val ivy = Ivy(artifact(), dependencies)
    val ivyPath = T.ctx().dest / "ivy.xml"
    write.over(ivyPath, ivy)
    PathRef(ivyPath)
  }

  def artifact: T[Artifact] = T {
    Artifact(pomSettings().organization, s"${publishName()}_${artifactScalaVersion()}", publishVersion())
  }

  def publishLocal(): define.Command[Unit] = T.command {
    LocalPublisher.publish(
      jar = jar().path,
      sourcesJar = sourcesJar().path,
      docsJar = docsJar().path,
      pom = pom().path,
      ivy = ivy().path,
      artifact = artifact()
    )
  }

  def sonatypeUri: String = "https://oss.sonatype.org/service/local"

  def sonatypeSnapshotUri: String = "https://oss.sonatype.org/content/repositories/snapshots"

  def publish(credentials: String, gpgPassphrase: String): define.Command[Unit] = T.command {
    val baseName = s"${publishName()}_${artifactScalaVersion()}-${publishVersion()}"
    val artifacts = Seq(
      jar().path -> s"${baseName}.jar",
      sourcesJar().path -> s"${baseName}-sources.jar",
      docsJar().path -> s"${baseName}-javadoc.jar",
      pom().path -> s"${baseName}.pom"
    )
    new SonatypePublisher(
      sonatypeUri,
      sonatypeSnapshotUri,
      credentials,
      gpgPassphrase,
      T.ctx().log
    ).publish(artifacts, artifact())
  }

}

trait SbtScalaModule extends ScalaModule { outer =>
  def basePath: Path
  override def sources = T.source{ basePath / 'src / 'main / 'scala }
  override def resources = T.source{ basePath / 'src / 'main / 'resources }
  trait Tests extends super.Tests{
    def basePath = outer.basePath
    override def sources = T.source{ basePath / 'src / 'test / 'scala }
    override def resources = T.source{ basePath / 'src / 'test / 'resources }
  }
}
