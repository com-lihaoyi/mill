package mill
package scalanativelib

import java.net.URLClassLoader

import ammonite.ops.Path
import coursier.Cache
import coursier.maven.MavenRepository
import mill.eval.Result
import mill.modules.Jvm
import mill.scalalib.Lib.resolveDependencies
import mill.scalalib.{DepSyntax, TestModule, TestRunner}
import mill.{PathRef, T}
import mill.util.Loose
import sbt.testing.{AnnotatedFingerprint, Framework, SubclassFingerprint}

import sbt.testing.Fingerprint
import testinterface.ScalaNativeFramework


trait ScalaNativeModule extends scalalib.ScalaModule { outer =>
  def scalaNativeVersion: T[String]
  override def platformSuffix = T{ "_native" + scalaNativeBridgeVersion() }

  trait Tests extends TestScalaNativeModule { testOuter =>
    override def scalaWorker = outer.scalaWorker
    override def scalaVersion = outer.scalaVersion()
    override def scalaNativeVersion = outer.scalaNativeVersion()
    override def moduleDeps = Seq(outer)

    override def testLocal(args: String*) = T.command { test(args:_*) }

    override def test(args: String*) = T.command{
      val outputPath = T.ctx().dest/"out.json"

      // XXX Fix me
      val uTestPaths = resolveDeps(T.task{T.task{Agg(ivy"com.lihaoyi:utest_2.12:0.6.3")}()})().map(_.path)
      println(s"uTestPaths=$uTestPaths")
      val testClassloader = new URLClassLoader(uTestPaths.map(_.toIO.toURI.toURL).toArray, this.getClass.getClassLoader)

      val frameworkInstances = TestRunner.frameworks(testFrameworks())(testClassloader)
      val testBinary = testrunner.nativeLink().toIO
      val envVars = forkEnv()

      val nativeFrameworks =  (cl: ClassLoader) =>
        frameworkInstances.zipWithIndex.map{case(f,id) =>
          new ScalaNativeFramework(f, id, testBinary, envVars): Framework
        }

      val (doneMsg, results) = scalaWorker.worker().runTests(
        nativeFrameworks,
        runClasspath().map(_.path),
        Agg(compile().classes.path),
        args
      )

      TestModule.handleResults(doneMsg, results)
    }


    object testrunner extends ScalaNativeModule {
      override def scalaWorker = testOuter.scalaWorker
      override def scalaVersion = testOuter.scalaVersion()
      override def scalaNativeVersion = testOuter.scalaNativeVersion()
      override def moduleDeps = Seq(testOuter)

      override def ivyDeps = testOuter.ivyDeps() ++ Agg(
        ivy"org.scala-native::test-interface_native0.3.7-SNAPSHOT:0.3.7-SNAPSHOT"
      )
      override def nativeLinkStubs = true

      override def compileClasspath = T{
        (upstreamRunClasspath() ++
          resources() ++
          unmanagedClasspath() ++
          resolveDeps(T.task{compileIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})())
        .filter(!_.path.toString.contains("repo1.maven.org/maven2/org/scala-native")) // XXX remove me
      }

      override def runClasspath = T{
        Agg(compile().classes) ++
          resources() ++
          upstreamAssemblyClasspath()
          .filter(!_.path.toString.contains("repo1.maven.org/maven2/org/scala-native")) // XXX remove me
          .filter(!_.path.toString.contains("repo1.maven.org/maven2/org/scala-lang")) // XXX remove me
      }

      override def generatedSources = T {
        val outDir = T.ctx().dest
        ammonite.ops.write.over(outDir / "TestMain.scala", testOuter.makeTestMain())
        Seq(PathRef(outDir))
      }
    }
  }


  def scalaNativeBridgeVersion = T{ scalaNativeVersion().split('.').take(2).mkString(".") }

  def bridge = T.task{ ScalaNativeBridge.scalaNativeBridge().bridge(bridgeFullClassPath()) }

  def scalaNativeBridgeClasspath = T {
    val snBridgeKey = "MILL_SCALANATIVE_BRIDGE_" + scalaNativeBridgeVersion().replace('.', '_').replace('-', '_')
    val snBridgePath = sys.props(snBridgeKey)
    if (snBridgePath != null) Result.Success(
      Agg(PathRef(Path(snBridgePath), quick = true))
    ) else resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      "2.12.4",
      Seq(
        ivy"com.lihaoyi::mill-scalanativelib-scalanativebridges-${scalaNativeBridgeVersion()}:${sys.props("MILL_VERSION")}"
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
    upstreamRunClasspath() ++
      resources() ++
      unmanagedClasspath() ++
      resolveDeps(T.task{compileIvyDeps() ++ nativeIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  override def upstreamAssemblyClasspath = T{
    upstreamRunClasspath() ++
      unmanagedClasspath() ++
      resolveDeps(T.task{runIvyDeps() ++ nativeIvyDeps() ++ scalaLibraryIvyDeps() ++ transitiveIvyDeps()})()
  }

  def nativeLibIvy = T{ ivy"org.scala-native::nativelib_native${scalaNativeVersion()}:${scalaNativeVersion()}" }

  def nativeIvyDeps = T{
    Seq(nativeLibIvy()) ++
    Seq(
      ivy"org.scala-native::javalib_native${scalaNativeVersion()}:${scalaNativeVersion()}",
      ivy"org.scala-native::auxlib_native${scalaNativeVersion()}:${scalaNativeVersion()}",
      ivy"org.scala-native::scalalib_native${scalaNativeVersion()}:${scalaNativeVersion()}"
    )
  }

  def bridgeFullClassPath = T {
    resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      scalaVersion(),
      toolsIvyDeps(),
      platformSuffix()
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
  def nativeCompileOptions = T{ bridge().discoverCompilationOptions }

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

trait TestScalaNativeModule extends ScalaNativeModule with TestModule {
  def scalaNativeTestDeps = T {
    resolveDeps(T.task {
      Loose.Agg(
        ivy"org.scala-native::test-interface:${scalaNativeVersion()}"
      )
    })
  }

  case class TestDefinition(framework: String, clazz: Class[_], fingerprint: Fingerprint) {
    def name = clazz.getName.reverse.dropWhile(_ == '$').reverse
  }

  def makeTestMain = T{
    val frameworkInstances = TestRunner.frameworks(testFrameworks()) _

    val testClasses =
      Jvm.inprocess(runClasspath().map(_.path), classLoaderOverrideSbtTesting = true, cl => {
        frameworkInstances(cl).flatMap { framework =>
          val df = scalaWorker.worker().discoverTests(cl, framework, Agg(compile().classes.path))
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
