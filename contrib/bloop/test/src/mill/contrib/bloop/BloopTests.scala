package mill.contrib.bloop

import bloop.config.{Config => BloopConfig}
import bloop.config.Config.{File => BloopFile}
import mill._
import mill.scalajslib.api.ModuleKind
import mill.scalalib._
import mill.scalanativelib.api.ReleaseMode
import mill.util.{TestEvaluator, TestUtil}
import os.Path
import upickle.default._
import utest._
import bloop.config.Config.Platform.Jvm

object BloopTests extends TestSuite {
  import BloopFormats._

  val workdir = os.pwd / 'target / 'workspace / "bloop"
  val testEvaluator = TestEvaluator.static(build)
  val testBloop = new BloopImpl(() => testEvaluator.evaluator, workdir)

  object build extends TestUtil.BaseModule {

    override def millSourcePath = BloopTests.workdir

    object scalaModule extends scalalib.ScalaModule with testBloop.Module {
      def scalaVersion = "2.12.8"
      val bloopVersion = "1.4.0-RC1"
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

    object scalajsModule extends scalajslib.ScalaJSModule with testBloop.Module {
      override def scalaVersion = "2.12.8"
      override def scalaJSVersion = "0.6.28"
      override def linkerMode = T(Some(_root_.bloop.config.Config.LinkerMode.Release))
      override def moduleKind = T(ModuleKind.CommonJSModule)
    }

    object scalanativeModule extends scalanativelib.ScalaNativeModule with testBloop.Module {
      override def scalaVersion = "2.11.12"
      override def scalaNativeVersion = "0.3.8"
      override def releaseMode = T(ReleaseMode.Release)
    }

    object skippedModule extends scalalib.ScalaModule with testBloop.Module {
      def scalaVersion = "2.12.8"
      override def skipBloop: Boolean = true
    }


  }

  def readBloopConf(jsonFile: String) =
    _root_.bloop.config.read((workdir / ".bloop" / jsonFile).toNIO)
      .right
      .get

  def tests: Tests = Tests {
    'genBloopTests - {

      testEvaluator(testBloop.install())
      val scalaModuleConfig = readBloopConf("scalaModule.json")
      val scalaModule2Config = readBloopConf("scalaModule2.json")
      val testModuleConfig = readBloopConf("scalaModule.test.json")
      val scalajsModuleConfig = readBloopConf("scalajsModule.json")
      val scalanativeModuleConfig = readBloopConf("scalanativeModule.json")

      'scalaModule - {
        val p = scalaModuleConfig.project
        val name = p.name
        val workspaceDir = p.workspaceDir
        val sources = p.sources.map(Path(_))
        val options = p.scala.get.options
        val version = p.scala.get.version
        val classpath = p.classpath.map(_.toString)
        val platform = p.platform.get.name
        val jvmOptions = p.platform.get.asInstanceOf[Jvm].config.options
        val mainCLass = p.platform.get.mainClass.get
        val resolution = p.resolution.get.modules

        assert(name == "scalaModule")
        assert(workspaceDir == Some(workdir.wrapped))
        assert(sources == List(workdir / "scalaModule" / "src"))
        assert(options.contains("-language:higherKinds"))
        assert(version == "2.12.8")
        assert(classpath.exists(_.contains(s"bloop-config_2.12-${build.scalaModule.bloopVersion}.jar")))
        assert(platform == "jvm")
        assert(mainCLass == "foo.bar.Main")
        assert(jvmOptions.contains(s"-Duser.dir=$workdir"))

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
        val workspaceDir = p.workspaceDir
        val sources = p.sources.map(Path(_))
        val framework = p.test.get.frameworks.head.names.head
        val dep = p.dependencies.head
        val mainModuleClasspath = scalaModuleConfig.project.classpath
        assert(name == "scalaModule.test")
        assert(workspaceDir == Some(workdir.wrapped))
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
      'scalajsModule - {
        val p = scalajsModuleConfig.project
        val name = p.name
        val workspaceDir = p.workspaceDir
        val sources = p.sources.map(Path(_))
        val version = p.scala.get.version
        val platform = p.platform.get.asInstanceOf[BloopConfig.Platform.Js]

        assert(name == "scalajsModule")
        assert(workspaceDir == Some(workdir.wrapped))
        assert(sources == List(workdir / "scalajsModule" / "src"))
        assert(version == "2.12.8")
        assert(platform.config.emitSourceMaps)
        assert(platform.config.kind == BloopConfig.ModuleKindJS.CommonJSModule)
        assert(platform.config.mode == BloopConfig.LinkerMode.Release)
      }
      'scalanativeModule - {
        val p = scalanativeModuleConfig.project
        val name = p.name
        val workspaceDir = p.workspaceDir
        val sources = p.sources.map(Path(_))
        val version = p.scala.get.version
        val platform = p.platform.get.asInstanceOf[BloopConfig.Platform.Native]

        val (clang, _) = testEvaluator(build.scalanativeModule.nativeClang).asSuccess.get.value.right.get

        assert(name == "scalanativeModule")
        assert(workspaceDir == Some(workdir.wrapped))
        assert(sources == List(workdir / "scalanativeModule" / "src"))
        assert(version == "2.11.12")
        assert(platform.config.mode == BloopConfig.LinkerMode.Release)
        assert(platform.config.clang == clang.toNIO)
      }
      'skipped - {
        val exists = os.exists(workdir / ".bloop" / "skippedModule.json")
        assert(exists == false)
      }
    }
  }

}
