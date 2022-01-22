package mill
package scalanativelib

import ch.epfl.scala.bsp4j.{BuildTargetDataKind, ScalaBuildTarget, ScalaPlatform}
import coursier.maven.MavenRepository
import mill.api.Loose.Agg
import mill.api.{internal, Result}
import mill.define.{Target, Task}
import mill.modules.Jvm
import mill.scalalib.api.Util.{isScala3, scalaBinaryVersion}
import mill.scalalib.{Dep, DepSyntax, Lib, SbtModule, ScalaModule, TestModule}
import mill.testrunner.TestRunner
import mill.scalanativelib.api._

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import upickle.default.{macroRW, ReadWriter => RW}

trait ScalaNativeModule extends ScalaModule { outer =>
  def scalaNativeVersion: T[String]
  override def platformSuffix = s"_native${scalaNativeBinaryVersion()}"
  override def artifactSuffix: T[String] = s"${platformSuffix()}_${artifactScalaVersion()}"

  trait Tests extends TestScalaNativeModule {
    override def zincWorker = outer.zincWorker
    override def scalaOrganization = outer.scalaOrganization()
    override def scalaVersion = outer.scalaVersion()
    override def scalaNativeVersion = outer.scalaNativeVersion()
    override def releaseMode = outer.releaseMode()
    override def logLevel = outer.logLevel()
    override def moduleDeps = Seq(outer)
  }

  def scalaNativeBinaryVersion =
    T { mill.scalalib.api.Util.scalaNativeBinaryVersion(scalaNativeVersion()) }

  def scalaNativeWorkerVersion =
    T { mill.scalalib.api.Util.scalaNativeWorkerVersion(scalaNativeVersion()) }

  def scalaNativeWorker = T.task {
    mill.scalanativelib.ScalaNativeWorkerApi.scalaNativeWorker().impl(bridgeFullClassPath())
  }

  def scalaNativeWorkerClasspath = T {
    val workerKey = "MILL_SCALANATIVE_WORKER_" + scalaNativeWorkerVersion().replace('.', '_')
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-scalanativelib-worker-${scalaNativeWorkerVersion()}",
      repositoriesTask(),
      resolveFilter = _.toString.contains("mill-scalanativelib-worker")
    )
  }

  def toolsIvyDeps = T {
    Agg(
      ivy"org.scala-native::tools:${scalaNativeVersion()}",
      ivy"org.scala-native::test-runner:${scalaNativeVersion()}"
    )
  }

  def nativeIvyDeps: T[Agg[Dep]] = T {
    val scalaVersionSpecific =
      if (isScala3(scalaVersion()))
        Agg(ivy"org.scala-native::scala3lib::${scalaNativeVersion()}")
      else
        Agg(ivy"org.scala-native::scalalib::${scalaNativeVersion()}")

    Agg(
      ivy"org.scala-native::nativelib::${scalaNativeVersion()}",
      ivy"org.scala-native::javalib::${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib::${scalaNativeVersion()}"
    ) ++ scalaVersionSpecific
  }

  override def scalaLibraryIvyDeps = T {
    if (isScala3(scalaVersion())) Agg.empty[Dep] else super.scalaLibraryIvyDeps()
  }

  /** Adds [[nativeIvyDeps]] as mandatory dependencies. */
  override def mandatoryIvyDeps = T {
    super.mandatoryIvyDeps() ++ nativeIvyDeps()
  }

  def bridgeFullClassPath: T[Agg[os.Path]] = T {
    Lib.resolveDependencies(
      repositoriesTask(),
      Lib.depToDependency(_, mill.BuildInfo.scalaVersion, ""),
      toolsIvyDeps(),
      ctx = Some(T.log)
    ).map(t => (scalaNativeWorkerClasspath() ++ t).map(_.path))
  }

  override def scalacPluginIvyDeps: T[Agg[Dep]] = T {
    super.scalacPluginIvyDeps() ++ Agg(
      ivy"org.scala-native:::nscplugin:${scalaNativeVersion()}"
    )
  }

  def logLevel: Target[NativeLogLevel] = T { NativeLogLevel.Info }

  private def readEnvVariable[T](
      env: Map[String, String],
      envVariable: String,
      values: Seq[T],
      valueOf: T => String
  ): Result[Option[T]] = {
    env.get(envVariable) match {
      case Some(value) =>
        values.find(valueOf(_) == value) match {
          case None =>
            Result.Failure(
              s"$envVariable=$value is not valid. Allowed values are: [${values.map(valueOf).mkString(", ")}]"
            )
          case Some(value) => Result.Success(Some(value))
        }
      case None => Result.Success(None)
    }
  }

  protected def releaseModeInput: Target[Option[ReleaseMode]] = T.input {
    readEnvVariable[ReleaseMode](T.env, "SCALANATIVE_MODE", ReleaseMode.values, _.value)
  }

  def releaseMode: Target[ReleaseMode] = T {
    releaseModeInput().getOrElse(ReleaseMode.Debug)
  }

  def nativeWorkdir = T { T.dest }

  // Location of the clang compiler
  def nativeClang = T { os.Path(scalaNativeWorker().discoverClang) }

  // Location of the clang++ compiler
  def nativeClangPP = T { os.Path(scalaNativeWorker().discoverClangPP) }

  // GC choice, either "none", "boehm", "immix" or "commix"
  protected def nativeGCInput: Target[Option[String]] = T.input {
    T.env.get("SCALANATIVE_GC")
  }

  def nativeGC = T {
    nativeGCInput().getOrElse(scalaNativeWorker().defaultGarbageCollector)
  }

  def nativeTarget: Target[Option[String]] = T { None }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = T { scalaNativeWorker().discoverCompileOptions }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = T { scalaNativeWorker().discoverLinkingOptions }

  // Whether to link `@stub` methods, or ignore them
  def nativeLinkStubs = T { false }

  // The LTO mode to use used during a release build
  protected def nativeLTOInput: Target[Option[LTO]] = T.input {
    readEnvVariable[LTO](T.env, "SCALANATIVE_LTO", LTO.values, _.value)
  }

  def nativeLTO: Target[LTO] = T { nativeLTOInput().getOrElse(LTO.None) }

  // Shall we optimize the resulting NIR code?
  protected def nativeOptimizeInput: Target[Option[Boolean]] = T.input {
    readEnvVariable[Boolean](T.env, "SCALANATIVE_OPTIMIZE", Seq(true, false), _.toString)
  }

  def nativeOptimize: Target[Boolean] = T { nativeOptimizeInput().getOrElse(true) }

  def nativeConfig = T.task {
    val classpath = runClasspath().map(_.path).filter(_.toIO.exists).toList

    scalaNativeWorker().config(
      finalMainClass(),
      classpath.toArray.map(_.toIO),
      nativeWorkdir().toIO,
      nativeClang().toIO,
      nativeClangPP().toIO,
      nativeTarget().toJava,
      nativeCompileOptions(),
      nativeLinkingOptions(),
      nativeGC(),
      nativeLinkStubs(),
      nativeLTO(),
      releaseMode(),
      nativeOptimize(),
      logLevel()
    )
  }

  // Generates native binary
  def nativeLink = T {
    os.Path(scalaNativeWorker().nativeLink(nativeConfig(), (T.dest / "out").toIO))
  }

  // Runs the native binary
  override def run(args: String*) = T.command {
    Jvm.runSubprocess(
      commandArgs = Vector(nativeLink().toString) ++ args,
      envArgs = forkEnv(),
      workingDir = forkWorkingDir()
    )
  }

  @internal
  override def bspBuildTargetData: Task[Option[(String, AnyRef)]] = T.task {
    Some((
      BuildTargetDataKind.SCALA,
      new ScalaBuildTarget(
        scalaOrganization(),
        scalaVersion(),
        scalaBinaryVersion(scalaVersion()),
        ScalaPlatform.NATIVE,
        scalaCompilerClasspath().map(_.path.toNIO.toUri.toString).iterator.toSeq.asJava
      )
    ))
  }
}

trait TestScalaNativeModule extends ScalaNativeModule with TestModule {
  override def testLocal(args: String*) = T.command { test(args: _*) }
  override protected def testTask(
      args: Task[Seq[String]],
      globSeletors: Task[Seq[String]]
  ): Task[(String, Seq[TestRunner.Result])] = T.task {

    val getFrameworkResult = scalaNativeWorker().getFramework(
      nativeLink().toIO,
      forkEnv().asJava,
      logLevel(),
      testFramework()
    )
    val framework = getFrameworkResult.framework
    val close = getFrameworkResult.close

    val (doneMsg, results) = TestRunner.runTestFramework(
      _ => framework,
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args(),
      T.testReporter,
      TestRunner.globFilter(globSeletors())
    )
    val res = TestModule.handleResults(doneMsg, results)
    // Hack to try and let the Scala Native subprocess finish streaming it's stdout
    // to the JVM. Without this, the stdout can still be streaming when `close()`
    // is called, and some of the output is dropped onto the floor.
    Thread.sleep(100)
    close.run()
    res
  }
  override def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"org.scala-native::test-interface::${scalaNativeVersion()}"
  )
  override def mainClass: T[Option[String]] = Some("scala.scalanative.testinterface.TestMain")
}

trait SbtNativeModule extends ScalaNativeModule with SbtModule
