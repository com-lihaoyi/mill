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

  def scalaNativeBinaryVersion = T { mill.scalalib.api.Util.scalaNativeBinaryVersion(scalaNativeVersion()) }

  def scalaNativeWorkerVersion = T{ mill.scalalib.api.Util.scalaNativeWorkerVersion(scalaNativeVersion()) }

  def scalaNativeWorker = T.task{
    mill.scalanativelib.ScalaNativeWorkerApi.scalaNativeWorker().impl(bridgeFullClassPath())
  }

  def scalaNativeWorkerClasspath = T {
    val workerKey = "MILL_SCALANATIVE_WORKER_" + scalaNativeWorkerVersion().replace('.', '_')
    mill.modules.Util.millProjectModule(
      workerKey,
      s"mill-scalanativelib-worker-${scalaNativeWorkerVersion()}",
      repositories,
      resolveFilter = _.toString.contains("mill-scalanativelib-worker"),
      artifactSuffix = "_2.12"
    )
  }

  def toolsIvyDeps = T{
    Seq(
      ivy"org.scala-native:tools_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:util_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:nir_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:test-runner_2.12:${scalaNativeVersion()}"
    )
  }

  override def transitiveIvyDeps: T[Agg[Dep]] = T{
    ivyDeps() ++ nativeIvyDeps() ++ Task.traverse(moduleDeps)(_.transitiveIvyDeps)().flatten
  }

  def nativeLibIvy = T{ ivy"org.scala-native::nativelib::${scalaNativeVersion()}" }

  def nativeIvyDeps = T{
    Seq(nativeLibIvy()) ++
    Seq(
      ivy"org.scala-native::javalib::${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib::${scalaNativeVersion()}",
      ivy"org.scala-native::scalalib::${scalaNativeVersion()}"
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

  def nativeWorkdir = T{ T.dest }

  // Location of the clang compiler
  def nativeClang = T{ os.Path(scalaNativeWorker().discoverClang) }

  // Location of the clang++ compiler
  def nativeClangPP = T{ os.Path(scalaNativeWorker().discoverClangPP) }

  // GC choice, either "none", "boehm" or "immix"
  def nativeGC = T{
    Option(System.getenv.get("SCALANATIVE_GC"))
      .getOrElse(scalaNativeWorker().defaultGarbageCollector)
  }

  def nativeTarget = T{
    scalaNativeWorker().discoverTarget(nativeClang().toIO, nativeWorkdir().toIO)
  }

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
      nativeLibJar().path.toIO,
      finalMainClass(),
      classpath.toArray.map(_.toIO),
      nativeWorkdir().toIO,
      nativeClang().toIO,
      nativeClangPP().toIO,
      nativeTarget(),
      nativeCompileOptions(),
      nativeLinkingOptions(),
      nativeGC(),
      nativeLinkStubs(),
      releaseMode(),
      logLevel())
  }

  // Generates native binary
  def nativeLink = T{
    os.Path(scalaNativeWorker().nativeLink(nativeConfig(), (T.dest / 'out).toIO))
  }

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

  override protected def testTask(args: Task[Seq[String]]): Task[(String, Seq[TestRunner.Result])] = T.task {
    val outputPath = T.dest / "out.json"
    val (frameworks, testsMap) = frameworksAndTestsMap()
    val (doneMsg, results) = if(frameworks.nonEmpty && testsMap.nonEmpty) {
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
          import collection.JavaConverters._
          scalaNativeWorker().newScalaNativeFrameWork(
            f, id, testBinary, logLevel(), envVars.asJava
          )
        }

      TestRunner.runTests(
        nativeFrameworks,
        runClasspath().map(_.path),
        Agg(compile().classes.path),
        args(),
        T.testReporter
      )
    } else ("No tests were executed", Seq.empty)

    TestModule.handleResults(doneMsg, results)
  }

  private val testMainClassName = "ScalaNativeTestMain"

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
    override def nativeLinkingOptions = testOuter.nativeLinkingOptions()
    override def nativeCompileOptions = testOuter.nativeCompileOptions()

    override def ivyDeps = testOuter.ivyDeps() ++ Agg(
      ivy"org.scala-native::test-interface::${scalaNativeVersion()}"
    )

    override def mainClass = Some(testMainClassName)

    override def generatedSources = T {
      val outDir = T.dest
      ammonite.ops.write.over(outDir / s"$testMainClassName.scala", makeTestMain())
      Seq(PathRef(outDir))
    }
  }

  private def fullClassName(name: String): String = {
    val isInAPackage = name.contains(".")
    if (isInAPackage) s"_root_.$name" else name
  }

  private def frameworksAndTestsMap = T{
    val frameworkInstances = TestRunner.frameworks(testFrameworks()) _

    val testClasses = Jvm.inprocess(runClasspath().map(_.path), classLoaderOverrideSbtTesting = true, isolated = true, closeContextClassLoaderWhenDone = true,
      cl => {
        frameworkInstances(cl).flatMap { framework =>
          val df = Lib.discoverTests(cl, framework, Agg(compile().classes.path))
          df.map{ case (clazz, fingerprint) => TestDefinition(framework.getClass.getName, clazz, fingerprint) }
        }
      }
    )

    val frameworks = testClasses.map(_.framework).distinct

    val testsMap = testClasses
      .map { t =>
        val isModule = t.fingerprint match {
          case af: AnnotatedFingerprint => af.isModule
          case sf: SubclassFingerprint  => sf.isModule
        }
        val fullName = fullClassName(t.name)
        val inst     = if (isModule) fullName else s"new $fullName"
        s""""${t.name}" -> $inst"""
      }
      .mkString(", ")
    
      (frameworks, testsMap)
  }

  // generate a main class for the tests
  def makeTestMain = T{
    val (frameworks, testsMap) = frameworksAndTestsMap()

    val frameworksList = frameworks
      .map(f => s"new ${fullClassName(f)}")
      .mkString("List(", ", ", ")")

    s"""object $testMainClassName extends scala.scalanative.testinterface.TestMainBase {
       |  override val frameworks = $frameworksList
       |  override val tests = Map[String, AnyRef]($testsMap)
       |  def main(args: Array[String]): Unit =
       |    testMain(args)
       |}""".stripMargin
  }
}

trait SbtNativeModule extends ScalaNativeModule with SbtModule
