package mill
package scalanativelib

import java.net.URLClassLoader

import ammonite.ops.Path
import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.Result
import mill.modules.Jvm
import mill.scalalib.{DepSyntax, Lib, ScalaModule, TestModule, TestRunner}
import mill.{PathRef, T}
import mill.util.Loose
import sbt.testing.{AnnotatedFingerprint, Framework, SubclassFingerprint}
import sbt.testing.Fingerprint
import testinterface.ScalaNativeFramework


trait ScalaNativeModule extends scalalib.ScalaModule { outer =>
  def scalaNativeVersion: T[String]
  override def platformSuffix = T{ "_native" + scalaNativeBinaryVersion() }

  trait Tests extends ScalaNativeModule with TestModule { testOuter =>
    case class TestDefinition(framework: String, clazz: Class[_], fingerprint: Fingerprint) {
      def name = clazz.getName.reverse.dropWhile(_ == '$').reverse
    }

    override def scalaWorker = outer.scalaWorker
    override def scalaVersion = outer.scalaVersion()
    override def scalaNativeVersion = outer.scalaNativeVersion()
    override def moduleDeps = Seq(outer)

    override def testLocal(args: String*) = T.command { test(args:_*) }

    override def test(args: String*) = T.command{
      val outputPath = T.ctx().dest/"out.json"

      // The test frameworks run under the JVM and communicate with the native binary over a socket
      // therefore the test framework is loaded from a JVM classloader
      val testClassloader =
        new URLClassLoader(testRunnerJvm.runClasspath().map(_.path.toIO.toURI.toURL).toArray,
          this.getClass.getClassLoader)
      val frameworkInstances = TestRunner.frameworks(testFrameworks())(testClassloader)
      val testBinary = testRunnerNative.nativeLink().toIO
      val envVars = forkEnv()

      val nativeFrameworks = (cl: ClassLoader) =>
        frameworkInstances.zipWithIndex.map { case (f, id) =>
          new ScalaNativeFramework(f, id, testBinary, envVars): Framework
        }

      val (doneMsg, results) = Lib.runTests(
        nativeFrameworks,
        testRunnerJvm.runClasspath().map(_.path),
        Agg(compile().classes.path),
        args
      )

      TestModule.handleResults(doneMsg, results)
    }

    // this is a dummy project used for extracting the JVM runClasspath()
    // that is, it evaluates the ivyDeps of the test project in a JVM scala context
    // ie get the JVM files from uTest, ScalaTest etc
    // (it is resilient to changes un runClasspath() implementation)
    object testRunnerJvm extends ScalaModule {
      override def scalaVersion = outer.scalaVersion()
      override def ivyDeps = testOuter.ivyDeps()
      override def moduleDeps = Seq(testOuter)
    }

    // creates a specific binary used for running tests - has a different (generated) main class
    // which knows the names of all the tests and references to invoke them
    object testRunnerNative extends ScalaNativeModule {
      override def scalaWorker = testOuter.scalaWorker
      override def scalaVersion = testOuter.scalaVersion()
      override def scalaNativeVersion = testOuter.scalaNativeVersion()
      override def moduleDeps = Seq(testOuter)

      override def ivyDeps = testOuter.ivyDeps() ++ Agg(
        ivy"org.scala-native::test-interface::${scalaNativeVersion()}"
      )

      override def nativeLinkStubs = true

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
        Jvm.inprocess(testRunnerJvm.runClasspath().map(_.path), classLoaderOverrideSbtTesting = true, cl => {
          frameworkInstances(cl).flatMap { framework =>
            val df = Lib.discoverTests(cl, framework, Agg(compile().classes.path))
            df.map(d => TestDefinition(framework.getClass.getName, d._1, d._2))
          }
        })

      val frameworks = testClasses.map(_.framework).distinct

      val frameworksList =
        if (frameworks.isEmpty)
          "Nil"
        else
          frameworks.mkString("List(new _root_.", ", new _root_.", ")")

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


  def scalaNativeBinaryVersion = T{ scalaNativeVersion().split('.').take(2).mkString(".") }

  def bridge = T.task{ ScalaNativeBridge.scalaNativeBridge().bridge(bridgeFullClassPath()) }

  def scalaNativeBridgeClasspath = T {
    val snBridgeKey = "MILL_SCALANATIVE_BRIDGE_" + scalaNativeBinaryVersion().replace('.', '_').replace('-', '_')
    val snBridgePath = sys.props(snBridgeKey)
    if (snBridgePath != null) Result.Success(
      Agg(PathRef(Path(snBridgePath), quick = true))
    ) else Lib.resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      Lib.depToDependency(_, "2.12.4", ""),
      Seq(
        ivy"com.lihaoyi::mill-scalanativelib-scalanativebridges-${scalaNativeBinaryVersion()}:${sys.props("MILL_VERSION")}"
      )
    ).map(_.filter(_.path.toString.contains("mill-scalanativelib-scalanativebridges")))
  }

  def toolsIvyDeps = T{
    Seq(
      ivy"org.scala-native:tools_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:util_2.12:${scalaNativeVersion()}",
      ivy"org.scala-native:nir_2.12:${scalaNativeVersion()}"
    )
  }

  override def compileClasspath = T{
    transitiveLocalClasspath() ++
    resources() ++
    unmanagedClasspath() ++
    resolveDeps(T.task{compileIvyDeps() ++ nativeIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
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
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      Lib.depToDependency(_, scalaVersion(), platformSuffix()),
      toolsIvyDeps()
    ).map(t => (scalaNativeBridgeClasspath().toSeq ++ t.toSeq).map(_.path))
  }

  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++
    Agg(ivy"org.scala-native:nscplugin_${scalaVersion()}:${scalaNativeVersion()}")

  def releaseMode = T { false }

  def nativeWorkdir = T{ T.ctx().dest }

  // Location of the clang compiler
  def nativeClang = T{ bridge().discoverClang }

  // Location of the clang++ compiler
  def nativeClangPP = T{ bridge().discoverClangPP }

  // GC choice, either "none", "boehm" or "immix"
  def nativeGC = T{
    Option(System.getenv.get("SCALANATIVE_GC"))
      .getOrElse(bridge().defaultGarbageCollector)
  }

  def nativeTarget = T{ bridge().discoverTarget(nativeClang(), nativeWorkdir()) }

  // Options that are passed to clang during compilation
  def nativeCompileOptions = T{ bridge().discoverCompileOptions }

  // Options that are passed to clang during linking
  def nativeLinkingOptions = T{ bridge().discoverLinkingOptions }

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

    bridge().config(
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
      releaseMode())
  }

  // Generates native binary
  def nativeLink = T{ bridge().nativeLink(nativeConfig(), (T.ctx().dest / 'out)) }

  // Runs the native binary
  override def run(args: String*) = T.command{
    Jvm.baseInteractiveSubprocess(
      Vector(nativeLink().toString) ++ args,
      forkEnv(),
      workingDir = ammonite.ops.pwd)
  }
}
