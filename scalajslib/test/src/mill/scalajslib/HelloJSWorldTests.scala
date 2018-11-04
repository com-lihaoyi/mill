package mill.scalajslib

import java.util.jar.JarFile
import mill._
import mill.define.Discover
import mill.eval.{Evaluator, Result}
import mill.scalalib.{CrossScalaModule, DepSyntax, Lib, PublishModule, TestRunner}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{TestEvaluator, TestUtil}
import utest._


import scala.collection.JavaConverters._


object HelloJSWorldTests extends TestSuite {
  val workspacePath =  TestUtil.getOutPathStatic() / "hello-js-world"

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq("2.11.8", "2.12.3", "2.12.4")
      scalaJS <- Seq("0.6.22", "1.0.0-M2")
    } yield (scala, scalaJS)

    object helloJsWorld extends Cross[BuildModule](matrix:_*)
    class BuildModule(val crossScalaVersion: String, sjsVersion0: String) extends HelloJSWorldModule {
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

    object buildUTest extends Cross[BuildModuleUtest](matrix:_*)
    class BuildModuleUtest(crossScalaVersion: String, sjsVersion0: String)
      extends BuildModule(crossScalaVersion, sjsVersion0) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'utest }
        def testFrameworks = Seq("utest.runner.Framework")
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::0.6.3"
        )
      }
    }

    object buildScalaTest extends Cross[BuildModuleScalaTest](matrix:_*)
    class BuildModuleScalaTest(crossScalaVersion: String, sjsVersion0: String)
      extends BuildModule(crossScalaVersion, sjsVersion0) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'scalatest }
        def testFrameworks = Seq("org.scalatest.tools.Framework")
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest::3.0.4"
        )
      }
    }
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.pwd / 'scalajslib / 'test / 'resources / "hello-js-world"

  val helloWorldEvaluator = TestEvaluator.static(HelloJSWorld)


  val mainObject = helloWorldEvaluator.outPath / 'src / "Main.scala"

  def tests: Tests = Tests {
    prepareWorkspace()
    'compile - {
      def testCompileFromScratch(scalaVersion: String,
                          scalaJSVersion: String): Unit = {
        val Right((result, evalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).compile)

        val outPath = result.classes.path
        val outputFiles = os.walk(outPath)
        val expectedClassfiles = compileClassfiles(outPath)
        assert(
          outputFiles.toSet == expectedClassfiles,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) =
          helloWorldEvaluator(HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).compile)
        assert(unchangedEvalCount == 0)
      }

      'fromScratch_2124_0622 - testCompileFromScratch("2.12.4", "0.6.22")
      'fromScratch_2123_0622 - testCompileFromScratch("2.12.3", "0.6.22")
      'fromScratch_2118_0622 - TestUtil.disableInJava9OrAbove(testCompileFromScratch("2.11.8", "0.6.22"))
      'fromScratch_2124_100M2 - testCompileFromScratch("2.12.4", "1.0.0-M2")
    }

    def testRun(scalaVersion: String,
                scalaJSVersion: String,
                mode: OptimizeMode): Unit = {
      val task = mode match {
        case FullOpt => HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).fullOpt
        case FastOpt => HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).fastOpt
      }
      val Right((result, evalCount)) = helloWorldEvaluator(task)
      val output = ScalaJsUtils.runJS(result.path)
      assert(output == "Hello Scala.js")
    }

    'fullOpt - {
      'run_2124_0622 - TestUtil.disableInJava9OrAbove(testRun("2.12.4", "0.6.22", FullOpt))
      'run_2123_0622 - TestUtil.disableInJava9OrAbove(testRun("2.12.3", "0.6.22", FullOpt))
      'run_2118_0622 - TestUtil.disableInJava9OrAbove(testRun("2.11.8", "0.6.22", FullOpt))
      'run_2124_100M2 - TestUtil.disableInJava9OrAbove(testRun("2.12.4", "1.0.0-M2", FullOpt))
    }
    'fastOpt - {
      'run_2124_0622 - TestUtil.disableInJava9OrAbove(testRun("2.12.4", "0.6.22", FastOpt))
      'run_2123_0622 - TestUtil.disableInJava9OrAbove(testRun("2.12.3", "0.6.22", FastOpt))
      'run_2118_0622 - TestUtil.disableInJava9OrAbove(testRun("2.11.8", "0.6.22", FastOpt))
      'run_2124_100M2 - TestUtil.disableInJava9OrAbove(testRun("2.12.4", "1.0.0-M2", FastOpt))
    }
    'jar - {
      'containsSJSIRs - {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.helloJsWorld("2.12.4", "0.6.22").jar)
        val jar = result.path
        val entries = new JarFile(jar.toIO).entries().asScala.map(_.getName)
        assert(entries.contains("Main$.sjsir"))
      }
    }
    'publish - {
      def testArtifactId(scalaVersion: String,
                         scalaJSVersion: String,
                         artifactId: String): Unit = {
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).artifactMetadata)
        assert(result.id == artifactId)
      }
      'artifactId_0622 - testArtifactId("2.12.4", "0.6.22", "hello-js-world_sjs0.6_2.12")
      'artifactId_100M2 - testArtifactId("2.12.4", "1.0.0-M2", "hello-js-world_sjs1.0.0-M2_2.12")
    }
    'test - {
      def runTests(testTask: define.Command[(String, Seq[TestRunner.Result])]): Map[String, Map[String, TestRunner.Result]] = {
        val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)

        val (doneMsg, testResults) = res
        testResults
          .groupBy(_.fullyQualifiedName)
          .mapValues(_.map(e => e.selector -> e).toMap)
      }

      def checkUtest(scalaVersion: String, scalaJSVersion: String) = {
        val resultMap = runTests(HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion).test.test())

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

      def checkScalaTest(scalaVersion: String, scalaJSVersion: String) = {
        val resultMap = runTests(HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion).test.test())

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

      'utest_2118_0622 - TestUtil.disableInJava9OrAbove(checkUtest("2.11.8", "0.6.22"))
      'utest_2124_0622 - checkUtest("2.12.4", "0.6.22")
      'utest_2118_100M2 - TestUtil.disableInJava9OrAbove(checkUtest("2.11.8", "1.0.0-M2"))
      'utest_2124_100M2 - checkUtest("2.12.4", "1.0.0-M2")

      'scalaTest_2118_0622 - TestUtil.disableInJava9OrAbove(checkScalaTest("2.11.8", "0.6.22"))
      'scalaTest_2124_0622 - checkScalaTest("2.12.4", "0.6.22")
//      No scalatest artifact for scala.js 1.0.0-M2 published yet
//      'scalaTest_2118_100M2 - checkScalaTest("2.11.8", "1.0.0-M2")
//      'scalaTest_2124_100M2 - checkScalaTest("2.12.4", "1.0.0-M2")
    }

    def checkRun(scalaVersion: String, scalaJSVersion: String): Unit = {
      val task = HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).run()

      val Right((_, evalCount)) = helloWorldEvaluator(task)

      val paths = Evaluator.resolveDestPaths(
        helloWorldEvaluator.outPath,
        task.ctx.segments
      )
      val log = os.read(paths.log)
      assert(
        evalCount > 0,
        log.contains("node"),
        log.contains("Scala.js")
      )
    }

    'run - {
      'run_2118_0622  - TestUtil.disableInJava9OrAbove(checkRun("2.11.8", "0.6.22"))
      'run_2124_0622  - checkRun("2.12.4", "0.6.22")
      'run_2118_100M2 - TestUtil.disableInJava9OrAbove(checkRun("2.11.8", "1.0.0-M2"))
      'run_2124_100M2 - checkRun("2.12.4", "1.0.0-M2")
    }
  }

  def compileClassfiles(parentDir: os.Path) = Set(
    parentDir / "ArgsParser$.class",
    parentDir / "ArgsParser$.sjsir",
    parentDir / "ArgsParser.class",
    parentDir / "Main.class",
    parentDir / "Main$.class",
    parentDir / "Main$delayedInit$body.class",
    parentDir / "Main$.sjsir",
    parentDir / "Main$delayedInit$body.sjsir"
  )

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

}
