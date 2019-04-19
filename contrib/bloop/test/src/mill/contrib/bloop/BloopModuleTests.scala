package mill.contrib.bloop

import bloop.config.Config.{File => BloopFile}
import bloop.config.ConfigEncoderDecoders._
import mill._
import mill.scalalib._
import mill.contrib.bloop.CirceCompat._
import mill.util.{TestEvaluator, TestUtil}
import os.Path
import upickle.default._
import utest._

object BloopModuleTests extends TestSuite {

  val workdir = os.pwd / 'target / 'workspace / "bloop"

  object build extends TestUtil.BaseModule {

    override def millSourcePath = BloopModuleTests.workdir

    object scalaModule extends scalalib.ScalaModule {
      def scalaVersion = "2.12.8"
      override def mainClass = Some("foo.bar.Main")

      override def ivyDeps = Agg(
        ivy"ch.epfl.scala::bloop-config:1.2.5"
      )
      override def scalacOptions = Seq(
        "-language:higherKinds"
      )

      object test extends super.Tests {
        def testFrameworks = Seq("utest.runner.Framework")
      }
    }

  }

  val testEvaluator = TestEvaluator.static(build)
  val bloopModule = new BloopModuleImpl(testEvaluator.evaluator, workdir)

  def readBloopConf(jsonFile: String) =
    read[BloopFile](os.read(workdir / ".bloop" / jsonFile)).project

  def tests: Tests = Tests {
    'genBloopTests - {

      testEvaluator(bloopModule.install)
      val scalaModule = readBloopConf("scalaModule.json")
      val testModule = readBloopConf("scalaModule.test.json")

      'scalaModule - {
        val name = scalaModule.name
        val sources = scalaModule.sources.map(Path(_))
        val options = scalaModule.scala.get.options
        val version = scalaModule.scala.get.version
        val classpath = scalaModule.classpath.map(_.toString)
        val platform = scalaModule.platform.get.name
        val mainCLass = scalaModule.platform.get.mainClass.get
        assert(name == "scalaModule")
        assert(sources == List(workdir / "scalaModule" / "src"))
        assert(options.contains("-language:higherKinds"))
        assert(version == "2.12.8")
        assert(classpath.exists(_.contains("bloop-config_2.12-1.2.5.jar")))
        assert(platform == "jvm")
        assert(mainCLass == "foo.bar.Main")
      }
      'scalaModuleTest - {

        val name = testModule.name
        val sources = testModule.sources.map(Path(_))
        val framework = testModule.test.get.frameworks.head.names.head
        val dep = testModule.dependencies.head
        assert(name == "scalaModule.test")
        assert(sources == List(workdir / "scalaModule" / "test" / "src"))
        assert(framework == "utest.runner.Framework")
        assert(dep == "scalaModule")
        assert(scalaModule.classpath.forall(testModule.classpath.contains))
      }
    }
  }

}
