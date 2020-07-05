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
import mill.scalajslib.api._

object HelloJSWorldTests extends TestSuite {
  val workspacePath =  TestUtil.getOutPathStatic() / "hello-js-world"

  trait HelloJSWorldModule extends CrossScalaModule with ScalaJSModule with PublishModule {
    override def millSourcePath = workspacePath
    def publishVersion = "0.0.1-SNAPSHOT"
    override def mainClass = Some("Main")
  }

  object HelloJSWorld extends TestUtil.BaseModule {
    val scalaVersions = Seq("2.13.2", "2.12.11", "2.11.12")
    val scalaJSVersions = Seq("1.1.1", "1.0.1", "0.6.33")
    val matrix = for {
      scala <- scalaVersions
      scalaJS <- scalaJSVersions
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
          ivy"com.lihaoyi::utest::0.7.4"
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
          ivy"org.scalatest::scalatest::3.1.2"
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
        val expectedClassfiles = compileClassfiles(outPath, scalaJSVersion)
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

    def testRun(scalaVersion: String,
                scalaJSVersion: String,
                mode: OptimizeMode): Unit = {
      val task = mode match {
        case FullOpt => HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).fullOpt
        case FastOpt => HelloJSWorld.helloJsWorld(scalaVersion, scalaJSVersion).fastOpt
      }
      val Right((result, evalCount)) = helloWorldEvaluator(task)
      val jsFile = result.path
      val output = ScalaJsUtils.runJS(jsFile)
      assert(output == "Hello Scala.js")
      val sourceMap= jsFile / os.up / (jsFile.last + ".map")
      assert(sourceMap.toIO.exists()) // sourceMap file was generated
      assert(os.read(jsFile).contains(s"//# sourceMappingURL=${sourceMap.toNIO.getFileName}")) // jsFile references sourceMap
      assert(ujson.read(sourceMap.toIO).obj.get("file").exists(_.str == jsFile.toNIO.getFileName.toString)) // sourceMap references jsFile
    }

    'fullOpt - {
      testAllMatrix((scala, scalaJS) => TestUtil.disableInJava9OrAbove(testRun(scala, scalaJS, FullOpt)))
    }
    'fastOpt - {
      testAllMatrix((scala, scalaJS) => TestUtil.disableInJava9OrAbove(testRun(scala, scalaJS, FastOpt)))
    }
    'jar - {
      'containsSJSIRs - {
        val (scala, scalaJS) = HelloJSWorld.matrix.head
        val Right((result, evalCount)) = helloWorldEvaluator(HelloJSWorld.helloJsWorld(scala, scalaJS).jar)
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
      'artifactId_06 - testArtifactId(HelloJSWorld.scalaVersions.head, "0.6.33", "hello-js-world_sjs0.6_2.13")
      'artifactId_10 - testArtifactId(HelloJSWorld.scalaVersions.head, "1.0.1", "hello-js-world_sjs1_2.13")
      'artifactId_1 - testArtifactId(HelloJSWorld.scalaVersions.head, "1.1.1", "hello-js-world_sjs1_2.13")
    }

    def runTests(testTask: define.NamedTask[(String, Seq[TestRunner.Result])]): Map[String, Map[String, TestRunner.Result]] = {
      val Left(Result.Failure(_, Some(res))) = helloWorldEvaluator(testTask)

      val (doneMsg, testResults) = res
      testResults
        .groupBy(_.fullyQualifiedName)
        .mapValues(_.map(e => e.selector -> e).toMap)
        .toMap
    }

    def checkUtest(scalaVersion: String, scalaJSVersion: String, cached: Boolean) = {
      val resultMap = runTests(
        if(!cached) HelloJSWorld.buildUTest(scalaVersion, scalaJSVersion).test.test()
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
        if(!cached) HelloJSWorld.buildScalaTest(scalaVersion, scalaJSVersion).test.test()
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

    'test - {
      val cached = false
      testAllMatrix((scala, scalaJS) => checkUtest(scala, scalaJS, cached), skipScala = _.startsWith("2.11."))
      testAllMatrix((scala, scalaJS) => checkScalaTest(scala, scalaJS, cached))
    }

    'testCached - {
      val cached = false
      testAllMatrix((scala, scalaJS) => checkUtest(scala, scalaJS, cached), skipScala = _.startsWith("2.11."))
      testAllMatrix((scala, scalaJS) => checkScalaTest(scala, scalaJS, cached))
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
        // In Scala.js 1.x, println's are sent to the stdout, not to the logger
        !scalaJSVersion.startsWith("0.6.") || log.contains("Scala.js")
      )
    }

    'run - {
      testAllMatrix((scala, scalaJS) => checkRun(scala, scalaJS))
    }
  }

  def compileClassfiles(parentDir: os.Path, scalaJSVersion: String) = {
    val inAllVersions = Set(
      parentDir / "ArgsParser$.class",
      parentDir / "ArgsParser$.sjsir",
      parentDir / "ArgsParser.class",
      parentDir / "Main.class",
      parentDir / "Main$.class",
      parentDir / "Main$delayedInit$body.class",
      parentDir / "Main$.sjsir",
      parentDir / "Main$delayedInit$body.sjsir"
    )

    if (scalaJSVersion.startsWith("1.")) {
      inAllVersions ++ Set(
        parentDir / "ArgsParser.sjsir",
        parentDir / "Main.sjsir"
      )
    } else {
      inAllVersions
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }
  
  def testAllMatrix(f: (String, String) => Unit,
                    skipScala: String => Boolean = _ => false,
                    skipScalaJS: String => Boolean = _ => false): Unit = {
    for {
      (scala, scalaJS) <- HelloJSWorld.matrix
      if !skipScala(scala)
      if !skipScalaJS(scalaJS)
    } {
      if(scala.startsWith("2.11.")) {
        TestUtil.disableInJava9OrAbove(f(scala,scalaJS))
      } else {
        f(scala,scalaJS)
      }
    }
  }

}
