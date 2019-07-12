package mill
package scalanativelib

import java.net.URLClassLoader

import coursier.maven.MavenRepository
import mill.define.{Target, Task}
import mill.api.Result
import mill.modules.Jvm
import mill.scalalib.{Dep, DepSyntax, Lib, SbtModule, ScalaModule, TestModule, TestRunner}
import mill.api.Loose.Agg
import sbt.testing.{AnnotatedFingerprint, SubclassFingerprint}
import sbt.testing.Fingerprint
import upickle.default.{ReadWriter => RW, macroRW}
import mill.scalanativelib.api._


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

  def scalaNativeBinaryVersion = T{ scalaNativeVersion().split('.').take(2).mkString(".") }

  // This allows compilation and testing versus SNAPSHOT versions of scala-native
  def scalaNativeToolsVersion = T{
    if (scalaNativeVersion().endsWith("-SNAPSHOT"))
      scalaNativeVersion()
    else
      scalaNativeBinaryVersion()
  }

  def scalaNativeWorker = T.task{ ScalaNativeWorkerApi.scalaNativeWorker().impl(bridgeFullClassPath()) }

  def scalaNativeWorkerClasspath = T {
    val workerKey = "MILL_SCALANATIVE_WORKER_" + scalaNativeBinaryVersion().replace('.', '_').replace('-', '_')
    val workerPath = sys.props(workerKey)
    if (workerPath != null)
      Result.Success(Agg(workerPath.split(',').map(p => PathRef(os.Path(p), quick = true)): _*))
    else
      Lib.resolveDependencies(
        Seq(coursier.LocalRepositories.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
        Lib.depToDependency(_, "2.12.4", ""),
        Seq(ivy"com.lihaoyi::mill-scalanativelib-worker-${scalaNativeBinaryVersion()}:${sys.props("MILL_VERSION")}"),
        ctx = Some(implicitly[mill.util.Ctx.Log])
      )
  }

  def toolsIvyDeps = T{
    Seq(
      ivy"org.scala-native:tools_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:util_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:nir_2.12:${scalaNativeVersion()}"
    )
  }

  override def transitiveIvyDeps: T[Agg[Dep]] = T{
    ivyDeps() ++ nativeIvyDeps() ++ Task.traverse(moduleDeps)(_.transitiveIvyDeps)().flatten
  }

  def nativeLibIvy = T{ ivy"org.scala-native::nativelib_native${scalaNativeToolsVersion()}:${scalaNativeVersion()}" }

  def nativeIvyDeps = T{
    Seq(nativeLibIvy()) ++
    Seq(
      ivy"org.scala-native::javalib_native${scalaNativeToolsVersion()}:${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib_native${scalaNativeToolsVersion()}:${scalaNativeVersion()}",
      ivy"org.scala-native::scalalib_native${scalaNativeToolsVersion()}:${scalaNativeVersion()}"
    )
  }

  def bridgeFullClassPath = T {
    Lib.resolveDependencies(
      Seq(coursier.LocalRepositories.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      Lib.depToDependency(_, scalaVersion(), platformSuffix()),
      toolsIvyDeps(),
      ctx = Some(implicitly[mill.util.Ctx.Log])
    ).map(t => (scalaNativeWorkerClasspath().toSeq ++ t.toSeq).map(_.path))
  }

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++
    Agg(ivy"org.scala-native:nscplugin_${scalaVersion()}:${scalaNativeVersion()}")

  def logLevel: Target[NativeLogLevel] = T{ NativeLogLevel.Info }

  def releaseMode: Target[ReleaseMode] = T { ReleaseMode.Debug }

  def nativeWorkdir = T{ T.ctx().dest }

  // Location of the clang compiler
  def nativeClang = T{ scalaNativeWorker().discoverClang }

  // Location of the clang++ compiler
  def nativeClangPP = T{ scalaNativeWorker().discoverClangPP }

  // GC choice, either "none", "boehm" or "immix"
  def nativeGC = T{
    Option(System.getenv.get("SCALANATIVE_GC"))
      .getOrElse(scalaNativeWorker().defaultGarbageCollector)
  }

  def nativeTarget = T{ scalaNativeWorker().discoverTarget(nativeClang(), nativeWorkdir()) }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = T{ scalaNativeWorker().discoverCompileOptions }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = T{ scalaNativeWorker().discoverLinkingOptions }

  // Whether to link `@stub` methods, or ignore them
  def nativeLinkStubs = T { false }


  def nativeLibJar = T{
    resolveDeps(T.task{Agg(nativeLibIvy())})()
      .filter{p => p.toString.contains("scala-native") && p.toString.contains("nativelib")}
      .toList
      .head
  }

  def nativeConfig = T.task {
    val classpath = runClasspath().map(_.path).filter(_.toIO.exists).toList

    scalaNativeWorker().config(
      nativeLibJar().path,
      finalMainClass(),
      classpath,
      nativeWorkdir(),
      nativeClang(),
      nativeClangPP(),
      nativeTarget(),
      nativeCompileOptions(),
      nativeLinkingOptions(),
      nativeGC(),
      nativeLinkStubs(),
      releaseMode(),
      logLevel())
  }

  // Generates native binary
  def nativeLink = T{ scalaNativeWorker().nativeLink(nativeConfig(), (T.ctx().dest / 'out)) }

  // Runs the native binary
  override def run(args: String*) = T.command{
    Jvm.baseInteractiveSubprocess(
      Vector(nativeLink().toString) ++ args,
      forkEnv(),
      workingDir = ammonite.ops.pwd)
  }
}


trait TestScalaNativeModule extends ScalaNativeModule with TestModule { testOuter =>
  case class TestDefinition(framework: String, clazz: Class[_], fingerprint: Fingerprint) {
    def name = clazz.getName.reverse.dropWhile(_ == '$').reverse
  }

  override def testLocal(args: String*) = T.command { test(args:_*) }

  override def test(args: String*) = T.command{
    val outputPath = T.ctx().dest / "out.json"

    // The test frameworks run under the JVM and communicate with the native binary over a socket
    // therefore the test framework is loaded from a JVM classloader
    val testClassloader =
    new URLClassLoader(runClasspath().map(_.path.toIO.toURI.toURL).toArray,
      this.getClass.getClassLoader)
    val frameworkInstances = TestRunner.frameworks(testFrameworks())(testClassloader)
    val testBinary = testRunnerNative.nativeLink().toIO
    val envVars = forkEnv()

    val nativeFrameworks = (cl: ClassLoader) =>
      frameworkInstances.zipWithIndex.map { case (f, id) =>
        scalaNativeWorker().newScalaNativeFrameWork(f, id, testBinary, logLevel(), envVars)
      }

    val (doneMsg, results) = TestRunner.runTests(
      nativeFrameworks,
      runClasspath().map(_.path),
      Agg(compile().classes.path),
      args,
      T.ctx.bsp
    )

    TestModule.handleResults(doneMsg, results)
  }

  // creates a specific binary used for running tests - has a different (generated) main class
  // which knows the names of all the tests and references to invoke them
  object testRunnerNative extends ScalaNativeModule {
    override def zincWorker = testOuter.zincWorker
    override def scalaOrganization = testOuter.scalaOrganization()
    override def scalaVersion = testOuter.scalaVersion()
    override def scalaNativeVersion = testOuter.scalaNativeVersion()
    override def moduleDeps = Seq(testOuter)
    override def releaseMode = testOuter.releaseMode()
    override def logLevel = testOuter.logLevel()
    override def nativeLinkStubs = true

    override def ivyDeps = testOuter.ivyDeps() ++ Agg(
      ivy"org.scala-native::test-interface_native${scalaNativeToolsVersion()}:${scalaNativeVersion()}"
    )

    override def mainClass = Some("scala.scalanative.testinterface.TestMain")

    override def generatedSources = T {
      val outDir = T.ctx().dest
      ammonite.ops.write.over(outDir / "TestMain.scala", makeTestMain())
      Seq(PathRef(outDir))
    }
  }

  // generate a main class for the tests
  def makeTestMain = T{
    val frameworkInstances = TestRunner.frameworks(testFrameworks()) _

    val testClasses =
      Jvm.inprocess(runClasspath().map(_.path), classLoaderOverrideSbtTesting = true, isolated = true, closeContextClassLoaderWhenDone = true,
        cl => {
          frameworkInstances(cl).flatMap { framework =>
            val df = Lib.discoverTests(cl, framework, Agg(compile().classes.path))
            df.map(d => TestDefinition(framework.getClass.getName, d._1, d._2))
          }
        }
      )

    val frameworks = testClasses.map(_.framework).distinct

    val frameworksList =
      if (frameworks.nonEmpty) frameworks.mkString("List(new _root_.", ", new _root_.", ")")
      else {
        throw new Exception(
          "Cannot find any tests; make sure you defined the test framework correctly, " +
          "and extend whatever trait or annotation necessary to mark your test suites"
        )
      }


    val testsMap = makeTestsMap(testClasses)

    s"""package scala.scalanative.testinterface
       |object TestMain extends TestMainBase {
       |  override val frameworks = $frameworksList
       |  override val tests = Map[String, AnyRef]($testsMap)
       |  def main(args: Array[String]): Unit =
       |    testMain(args)
       |}""".stripMargin
  }

  private def makeTestsMap(tests: Seq[TestDefinition]): String = {
    tests
      .map { t =>
        val isModule = t.fingerprint match {
          case af: AnnotatedFingerprint => af.isModule
          case sf: SubclassFingerprint  => sf.isModule
        }

        val inst =
          if (isModule) s"_root_.${t.name}" else s"new _root_.${t.name}"
        s""""${t.name}" -> $inst"""
      }
      .mkString(", ")
  }
}


trait SbtNativeModule extends ScalaNativeModule with SbtModule

