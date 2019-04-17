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

  def tests: Tests = Tests {
    'genBloopTests - {
      testEvaluator(bloopModule.install)
      'scalaModule - {
        val bloopFile =
          read[BloopFile](os.read(workdir / ".bloop" / "scalaModule.json"))

        val name = bloopFile.project.name
        val sources = bloopFile.project.sources.map(Path(_))
        val options = bloopFile.project.scala.get.options
        val version = bloopFile.project.scala.get.version
        val classpath = bloopFile.project.classpath.map(_.toString)
        assert(name == "scalaModule")
        assert(sources == List(workdir / "scalaModule" / "src"))
        assert(options == List("-language:higherKinds"))
        assert(version == "2.12.8")
        assert(classpath.exists(_.contains("bloop-config_2.12-1.2.5.jar")))
      }
      'scalaModuleTest - {
        val bloopFile =
          read[BloopFile](os.read(workdir / ".bloop" / "scalaModule.test.json"))

        val name = bloopFile.project.name
        val sources = bloopFile.project.sources.map(Path(_))
        val framework = bloopFile.project.test.get.frameworks.head.names.head
        val dep = bloopFile.project.dependencies.head
        val classpath = bloopFile.project.classpath.map(_.toString)
        assert(name == "scalaModule.test")
        assert(sources == List(workdir / "scalaModule" / "test" / "src"))
        assert(framework == "utest.runner.Framework")
        assert(dep == "scalaModule")
        assert(classpath.exists(_.contains("bloop-config_2.12-1.2.5.jar")))
      }
    }
  }

}
