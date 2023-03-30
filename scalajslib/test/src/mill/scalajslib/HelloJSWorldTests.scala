package mill.scalajslib

import java.util.jar.JarFile
import mill._
import mill.api.Result
import mill.define.Discover
import mill.eval.EvaluatorPaths
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, TestModule}
import mill.testrunner.TestRunner
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{TestEvaluator, TestUtil}
import utest._

import scala.jdk.CollectionConverters._
import mill.scalalib.api.ZincWorkerUtil

object HelloJSWorldTests extends TestSuite {
  val workspacePath = TestUtil.getOutPathStatic() / "hello-js-world"

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val scalaVersions = Seq("2.13.3", "3.0.0-RC1", "2.12.12", "2.11.12")
    val scalaJSVersions = Seq("1.8.0", "1.3.1", "1.0.1")
    val matrix = for {
      scala <- scalaVersions
      scalaJS <- scalaJSVersions
      if !(ZincWorkerUtil.isScala3(scala) && scalaJS != scalaJSVersions.head)
    } yield (scala, scalaJS)

    object helloJsWorld extends Cross[BuildModule](matrix: _*)
    class BuildModule(val crossScalaVersion: String, sjsVersion0: String)
        extends HelloJSWorldModule {
      override def artifactName = "hello-js-world"
      def scalaJSVersion = sjsVersion0
      def pomSettings = PomSettings(
        organization = "com.lihaoyi",
        description = "hello js world ready for real world publishing",
        url = "https://github.com/lihaoyi/hello-world-publish",
        licenses = Seq(License.Common.Apache2),
        versionControl = VersionControl.github("lihaoyi", "hello-world-publish"),
        developers =
          Seq(Developer("lihaoyi", "Li Haoyi", "https://github.com/lihaoyi"))
      )
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix: _*)
    class BuildModuleUtest(crossScalaVersion: String, sjsVersion0: String)
        extends BuildModule(crossScalaVersion, sjsVersion0) {
      object test extends super.Tests with TestModule.Utest {
        override def sources = T.sources { millSourcePath / "src" / "utest" }
        val utestVersion = if (ZincWorkerUtil.isScala3(crossScalaVersion)) "0.7.7" else "0.7.5"
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::$utestVersion"
        )
      }
    }

    object buildScalaTest extends Cross[BuildModuleScalaTest](matrix: _*)
    class BuildModuleScalaTest(
        crossScalaVersion: String,
        sjsVersion0: String
    ) extends BuildModule(crossScalaVersion, sjsVersion0) {
      object test extends super.Tests with TestModule.ScalaTest {
        override def sources = T.sources { millSourcePath / "src" / "scalatest" }
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest::3.1.2"
        )
      }
    }

    object inherited extends ScalaJSModule {
      val (scala, scalaJS) = matrix.head
      def scalacOptions = Seq("-deprecation")
      def mandatoryScalacOptions = Seq("-mandatory")
      def scalaOrganization = "org.example"
      def scalaVersion = scala
      def scalaJSVersion = scalaJS
      object test extends Tests with TestModule.Utest
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalajslib" / "test" / "resources" / "hello-js-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloJSWorld)

  val mainObject = helloWorldEvaluator.outPath / "src" / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()
    test("compile") {
      def testCompileFromScratch(scalaVersion: String, scalaJSVersion: String): Unit = {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).compile)

        val outPath = result.classes.path
        val outputFiles = os.walk(outPath)
        val expectedClassfiles = compileClassfiles(outPath, scalaVersion, scalaJSVersion)
        assert(
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).compile)
        assert(unchangedEvalCount == 0)
      }

      testAllMatrix((scala, scalaJS) => testCompileFromScratch(scala, scalaJS))
    }

    def testRun(
        scalaVersion: String,
        scalaJSVersion: String,
        optimize: Boolean,
        legacy: Boolean
    ): Unit = {
      val module = HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion)
      val jsFile =
        if (legacy) {
          val task = if (optimize) module.fullOpt else module.fastOpt
          val Right((result, evalCount)) = helloWorldEvaluator(task)
          result.path
        } else {
          val task = if (optimize) module.fullLinkJS else module.fastLinkJS
          val Right((report, evalCount)) = helloWorldEvaluator(task)
          report.dest.path / report.publicModules.head.jsFileName
        }
      val output = ScalaJsUtils.runJS(jsFile)
      assert(output == "Hello Scala.js\n")
      val sourceMap = jsFile / os.up / (jsFile.last + ".map")
      assert(sourceMap.toIO.exists()) // sourceMap file was generated
      assert(
        os.read(jsFile).contains(s"//# sourceMappingURL=${sourceMap.toNIO.getFileName}")
      ) // jsFile references sourceMap
      assert(ujson.read(sourceMap.toIO).obj.get("file").exists(
        _.str == jsFile.toNIO.getFileName.toString
      )) // sourceMap references jsFile
    }

    test("fullOpt") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = true)
      )
    }
    test("fastOpt") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = true)
      )
    }
    test("fastLinkJS") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = false)
      )
    }
    test("fullLinkJS") {
      testAllMatrix((scala, scalaJS) =>
        testRun(scala, scalaJS, optimize = true, legacy = false)
      )
    }
    test("jar") {
      test("containsSJSIRs") {
        val (scala, scalaJS) = HelloJSWorld.matrix.head
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scala, scalaJS).jar)
        val jar = result.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("Main$.sjsir"))
      }
    }
    test("publish") {
      def testArtifactId(scalaVersion: String, scalaJSVersion: String, artifactId: String): Unit = {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.helloJsWorld(
          scalaVersion,
          scalaJSVersion
        ).artifactMetadata)
        assert(result.id == artifactId)
      }
      test("artifactId_10") {
        testArtifactId(
          HelloJSWorld.scalaVersions.head,
          "1.0.1",
          "hello-js-world_sjs1_2.13"
        )
      }
      test("artifactId_1") {
        testArtifactId(
          HelloJSWorld.scalaVersions.head,
          HelloJSWorld.scalaJSVersions.head,
          "hello-js-world_sjs1_2.13"
        )
      }
    }

    def runTests(testTask: define.NamedTask[(String, Seq[TestRunner.Result])])
        : Map[String, Map[String, TestRunner.Result]] = {
      val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)

      val (doneMsg, testResults) = res
      testResults
        .groupBy(_.fullyQualifiedName)
        .view
        .mapValues(_.map(e => e.selector -> e).toMap)
        .toMap
    }

    def checkUtest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
      val resultMap = runTests(
        if (!cached) HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion).test.test()
        else HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion).test.testCached
      )

      val mainTests = resultMap("MainTests")
      val argParserTests = resultMap("ArgsParserTests")

      assert(
        mainTests.size == 2,
        mainTests("MainTests.vmName.containJs").status == "Success",
        mainTests("MainTests.vmName.containScala").status == "Success",
        argParserTests.size == 2,
        argParserTests("ArgsParserTests.one").status == "Success",
        argParserTests("ArgsParserTests.two").status == "Failure"
      )
    }

    def checkScalaTest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
      val resultMap = runTests(
        if (!cached) HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion).test.test()
        else HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion).test.testCached
      )

      val mainSpec = resultMap("MainSpec")
      val argParserSpec = resultMap("ArgsParserSpec")

      assert(
        mainSpec.size == 2,
        mainSpec("vmName should contain js").status == "Success",
        mainSpec("vmName should contain Scala").status == "Success",
        argParserSpec.size == 2,
        argParserSpec("parse should one").status == "Success",
        argParserSpec("parse should two").status == "Failure"
      )
    }

    test("test") {
      val cached = false
      testAllMatrix(
        (scala, scalaJS) => checkUtest(scala, scalaJS, cached),
        skipScala = _.startsWith("2.11.")
      )
      testAllMatrix(
        (scala, scalaJS) => checkScalaTest(scala, scalaJS, cached),
        skipScala = ZincWorkerUtil.isScala3
      )
    }

    test("testCached") {
      val cached = false
      testAllMatrix(
        (scala, scalaJS) => checkUtest(scala, scalaJS, cached),
        skipScala = _.startsWith("2.11.")
      )
      testAllMatrix(
        (scala, scalaJS) => checkScalaTest(scala, scalaJS, cached),
        skipScala = ZincWorkerUtil.isScala3
      )
    }

    def checkRun(scalaVersion: String, scalaJSVersion: String): Unit = {
      val task = HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).run()

      val Right((_, evalCount)) = helloWorldEvaluator(task)

      val paths = EvaluatorPaths.resolveDestPaths(helloWorldEvaluator.outPath, task)
      val log = os.read(paths.log)
      assert(
        evalCount > 0,
        log.contains("node")
        // TODO: reenable somehow
        // In Scala.js 1.x, println's are sent to the stdout, not to the logger
        // log.contains("Scala.js")
      )
    }

    test("run") {
      testAllMatrix((scala, scalaJS) => checkRun(scala, scalaJS))
    }

    def checkInheritedTargets[A](target: ScalaJSModule => T[A], expected: A) = {
      val Right((mainResult, _)) = helloWorldEvaluator(target(HelloJSWorld.inherited))
      val Right((testResult, _)) = helloWorldEvaluator(target(HelloJSWorld.inherited.test))
      assert(mainResult == expected)
      assert(testResult == expected)
    }
    test("test-scalacOptions") {
      checkInheritedTargets(_.scalacOptions, Seq("-deprecation"))
    }
    test("test-mandatoryScalacOptions") {
      checkInheritedTargets(_.mandatoryScalacOptions, Seq("-mandatory"))
    }
    test("test-scalaOrganization") {
      checkInheritedTargets(_.scalaOrganization, "org.example")
    }
  }

  def compileClassfiles(parentDir: os.Path, scalaVersion: String, scalaJSVersion: String) = {
    val inAllVersions = Set(
      parentDir / "ArgsParser$.class",
      parentDir / "ArgsParser$.sjsir",
      parentDir / "ArgsParser.class",
      parentDir / "Main.class",
      parentDir / "Main$.class",
      parentDir / "Main$.sjsir"
    )
    val scalaJSVersionSpecific =
      if (scalaJSVersion.startsWith("1.")) Set(
        parentDir / "ArgsParser.sjsir",
        parentDir / "Main.sjsir"
      )
      else Set.empty
    val scalaVersionSpecific =
      if (ZincWorkerUtil.isScala3(scalaVersion)) Set(
        parentDir / "ArgsParser.tasty",
        parentDir / "Main.tasty"
      )
      else Set(
        parentDir / "Main$delayedInit$body.class",
        parentDir / "Main$delayedInit$body.sjsir"
      )

    inAllVersions ++ scalaJSVersionSpecific ++ scalaVersionSpecific
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

  def testAllMatrix(
      f: (String, String) => Unit,
      skipScala: String => Boolean = _ => false,
      skipScalaJS: String => Boolean = _ => false
  ): Unit = {
    for {
      (scala, scalaJS) <- HelloJSWorld.matrix
      if !skipScala(scala)
      if !skipScalaJS(scalaJS)
    } {
      if (scala.startsWith("2.11.")) {
        TestUtil.disableInJava9OrAbove("Scala 2.11 tests don't run under Java 9+")(f(
          scala,
          scalaJS
        ))
      } else {
        f(scala, scalaJS)
      }
    }
  }

}
