package mill.contrib.bloop

import bloop.config.Config.{File => BloopFile}
import bloop.config.ConfigEncoderDecoders._
import mill._
import mill.contrib.bloop.CirceCompat._
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import os.Path
import upickle.default._
import utest._

object BloopTests extends TestSuite {

  val workdir = os.pwd / 'target / 'workspace / "bloop"
  val testEvaluator = TestEvaluator.static(build)
  val testBloop = new BloopImpl(() => testEvaluator.evaluator, workdir)

  object build extends TestUtil.BaseModule {

    override def millSourcePath = BloopTests.workdir

    object scalaModule extends scalalib.ScalaModule with testBloop.Module {
      def scalaVersion = "2.12.8"
      val bloopVersion = "1.2.5"
      override def mainClass = Some("foo.bar.Main")

      override def ivyDeps = Agg(
        ivy"ch.epfl.scala::bloop-config:$bloopVersion"
      )
      override def scalacOptions = Seq(
        "-language:higherKinds"
      )

      object test extends super.Tests {
        def testFrameworks = Seq("utest.runner.Framework")
      }
    }

    object scalaModule2 extends scalalib.ScalaModule {
      def scalaVersion = "2.12.8"
    }

  }

  def readBloopConf(jsonFile: String) =
    read[BloopFile](os.read(workdir / ".bloop" / jsonFile))

  def tests: Tests = Tests {
    'genBloopTests - {

      testEvaluator(testBloop.install)
      val scalaModuleConfig = readBloopConf("scalaModule.json")
      val scalaModule2Config = readBloopConf("scalaModule2.json")
      val testModuleConfig = readBloopConf("scalaModule.test.json")

      'scalaModule - {
        val p = scalaModuleConfig.project
        val name = p.name
        val sources = p.sources.map(Path(_))
        val options = p.scala.get.options
        val version = p.scala.get.version
        val classpath = p.classpath.map(_.toString)
        val platform = p.platform.get.name
        val mainCLass = p.platform.get.mainClass.get
        val resolution = p.resolution.get.modules
        val sdb = testBloop.semanticDBVersion
        val sdbOpts = testBloop.semanticDBOptions

        assert(name == "scalaModule")
        assert(sources == List(workdir / "scalaModule" / "src"))
        assert(options.contains("-language:higherKinds"))
        assert(options.exists(_.contains(s"semanticdb-scalac_2.12.8-$sdb.jar")))
        assert(sdbOpts.forall(options.contains))
        assert(version == "2.12.8")
        assert(classpath.exists(_.contains("bloop-config_2.12-1.2.5.jar")))
        assert(platform == "jvm")
        assert(mainCLass == "foo.bar.Main")

        val bloopConfigDep = resolution.find(_.name == "bloop-config_2.12").get
        val artifacts = bloopConfigDep.artifacts
        assert(bloopConfigDep.version == build.scalaModule.bloopVersion)
        assert(bloopConfigDep.organization == "ch.epfl.scala")
        assert(artifacts.map(_.name).distinct == List("bloop-config_2.12"))
        assert(artifacts.flatMap(_.classifier).contains("sources"))
      }
      'scalaModuleTest - {
        val p = testModuleConfig.project
        val name = p.name
        val sources = p.sources.map(Path(_))
        val framework = p.test.get.frameworks.head.names.head
        val dep = p.dependencies.head
        val mainModuleClasspath = scalaModuleConfig.project.classpath
        assert(name == "scalaModule.test")
        assert(sources == List(workdir / "scalaModule" / "test" / "src"))
        assert(framework == "utest.runner.Framework")
        assert(dep == "scalaModule")
        assert(mainModuleClasspath.forall(p.classpath.contains))
      }
      'configAccessTest - {
        val (accessedConfig, _) =
          testEvaluator(build.scalaModule.bloop.config).asSuccess.get.value.right.get
        assert(accessedConfig == scalaModuleConfig)
      }
      'noDepTest - {
        val cp = scalaModule2Config.project.classpath.map(_.toString)
        assert(cp.exists(_.contains("scala-library-2.12.8")))
      }
    }
  }

}
