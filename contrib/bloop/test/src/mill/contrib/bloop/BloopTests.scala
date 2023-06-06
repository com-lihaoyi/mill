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
import scala.util.Properties.isWin

object BloopTests extends TestSuite {
  import BloopFormats._

  val workdir = os.pwd / "target" / "workspace" / "bloop"
  val testEvaluator = TestEvaluator.static(build)
  val testBloop = new BloopImpl(() => testEvaluator.evaluator, workdir)

  object build extends TestUtil.BaseModule {

    override def millSourcePath = BloopTests.workdir

    object scalaModule extends scalalib.ScalaModule with testBloop.Module {
      def scalaVersion = "2.12.8"
      val bloopVersion = mill.contrib.bloop.Versions.bloop
      override def mainClass = Some("foo.bar.Main")

      override def ivyDeps = Agg(
        ivy"ch.epfl.scala::bloop-config:$bloopVersion"
      )
      override def scalacOptions = Seq(
        "-language:higherKinds"
      )

      override def compileIvyDeps = Agg(
        ivy"org.reactivestreams:reactive-streams:1.0.3"
      )

      override def runIvyDeps = Agg(
        ivy"org.postgresql:postgresql:42.3.3"
      )

      object test extends ScalaTests with TestModule.Utest
    }

    object scalaModule2 extends scalalib.ScalaModule {
      def scalaVersion = "2.12.8"
    }

    object scalajsModule extends scalajslib.ScalaJSModule with testBloop.Module {
      val sv = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      val sjsv = sys.props.getOrElse("TEST_SCALAJS_VERSION", ???)
      override def scalaVersion = sv
      override def scalaJSVersion = sjsv
      override def linkerMode = T(Some(_root_.bloop.config.Config.LinkerMode.Release))
      override def moduleKind = T(ModuleKind.CommonJSModule)
    }

    object scalanativeModule extends scalanativelib.ScalaNativeModule with testBloop.Module {
      val sv = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
      override def skipBloop: Boolean = isWin
      override def scalaVersion = sv
      override def scalaNativeVersion = sys.props.getOrElse("TEST_SCALANATIVE_VERSION", ???)
      override def releaseMode = T(ReleaseMode.Debug)
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
    "genBloopTests" - {

      testEvaluator(testBloop.install())
      val scalaModuleConfig = readBloopConf("scalaModule.json")
      val scalaModule2Config = readBloopConf("scalaModule2.json")
      val testModuleConfig = readBloopConf("scalaModule.test.json")
      val scalajsModuleConfig = readBloopConf("scalajsModule.json")
      // skipped on Windows
      val scalanativeModuleConfig =
        if (scala.util.Properties.isWin) None else Some(readBloopConf("scalanativeModule.json"))

      "no-compilation" - {
        val workspaceOut =
          os.pwd / "target" / "workspace" / "mill" / "contrib" / "bloop" / "BloopTests" / "testEvaluator"

        // Ensuring that bloop config generation didn't trigger compilation
        assert(os.exists(workspaceOut / "scalaModule"))
        assert(!os.exists(workspaceOut / "scalaModule" / "compile"))
        assert(os.exists(workspaceOut / "scalaModule2"))
        assert(!os.exists(workspaceOut / "scalaModule2" / "compile"))
      }

      "scalaModule" - {
        val p = scalaModuleConfig.project
        val name = p.name
        val workspaceDir = p.workspaceDir
        val sources = p.sources.map(Path(_))
        val options = p.scala.get.options
        val version = p.scala.get.version
        val compileClasspath = p.classpath.map(_.toString)
        val platform = p.platform.get.asInstanceOf[Jvm]
        val jvmOptions = platform.config.options
        val runtimeClasspath = platform.classpath.get.map(_.toString)
        val resolution = p.resolution.get.modules

        assert(name == "scalaModule")
        assert(workspaceDir == Some(workdir.wrapped))
        assert(sources == List(workdir / "scalaModule" / "src"))
        assert(options.contains("-language:higherKinds"))
        assert(version == "2.12.8")
        assert(
          compileClasspath.exists(
            _.contains(s"bloop-config_2.12-${build.scalaModule.bloopVersion}.jar")
          )
        )
        assert(compileClasspath.exists(_.contains("reactive-streams-1.0.3.jar")))
        assert(
          compileClasspath.filterNot(_.contains("reactive-streams-1.0.3.jar")).forall(
            runtimeClasspath.contains
          )
        )
        assert(
          runtimeClasspath.exists(_.contains("postgresql-42.3.3.jar"))
        )
        assert(platform.name == "jvm")
        assert(platform.mainClass.get == "foo.bar.Main")
        assert(jvmOptions.contains(s"-Duser.dir=$workdir"))

        val bloopConfigDep = resolution.find(_.name == "bloop-config_2.12").get
        val artifacts = bloopConfigDep.artifacts
        assert(bloopConfigDep.version == build.scalaModule.bloopVersion)
        assert(bloopConfigDep.organization == "ch.epfl.scala")
        assert(artifacts.map(_.name).distinct == List("bloop-config_2.12"))
        assert(artifacts.flatMap(_.classifier).contains("sources"))
      }
      "scalaModuleTest" - {
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
        assert(
          mainModuleClasspath.filterNot(_.toString.contains("reactive-streams-1.0.3.jar")).forall(
            p.classpath.contains
          )
        )
      }
      "configAccessTest" - {
        val (accessedConfig, _) =
          testEvaluator(build.scalaModule.bloop.config).asSuccess.get.value.right.get
        assert(accessedConfig == scalaModuleConfig)
      }
      "noDepTest" - {
        val cp = scalaModule2Config.project.classpath.map(_.toString)
        assert(cp.exists(_.contains("scala-library-2.12.8")))
      }
      "scalajsModule" - {
        val p = scalajsModuleConfig.project
        val name = p.name
        val workspaceDir = p.workspaceDir
        val sources = p.sources.map(Path(_))
        val version = p.scala.get.version
        val platform = p.platform.get.asInstanceOf[BloopConfig.Platform.Js]

        assert(name == "scalajsModule")
        assert(workspaceDir == Some(workdir.wrapped))
        assert(sources == List(workdir / "scalajsModule" / "src"))
        assert(version == build.scalajsModule.sv)
        assert(platform.config.emitSourceMaps)
        assert(platform.config.kind == BloopConfig.ModuleKindJS.CommonJSModule)
        assert(platform.config.mode == BloopConfig.LinkerMode.Release)
      }
      "scalanativeModule" - {
        scalanativeModuleConfig match {
          case None =>
            val exists = os.exists(workdir / ".bloop" / "scalanativeModule.json")
            assert(exists == false)
          case Some(scalanativeModuleConfig) =>
            val p = scalanativeModuleConfig.project
            val name = p.name
            val workspaceDir = p.workspaceDir
            val sources = p.sources.map(Path(_))
            val version = p.scala.get.version
            val platform = p.platform.get.asInstanceOf[BloopConfig.Platform.Native]

            val (clang, _) =
              testEvaluator(build.scalanativeModule.nativeClang).asSuccess.get.value.right.get

            assert(name == "scalanativeModule")
            assert(workspaceDir == Some(workdir.wrapped))
            assert(sources == List(workdir / "scalanativeModule" / "src"))
            assert(version == build.scalanativeModule.sv)
            assert(platform.config.mode == BloopConfig.LinkerMode.Debug)
            assert(platform.config.clang == clang.toNIO)
        }
      }
      "skipped" - {
        val exists = os.exists(workdir / ".bloop" / "skippedModule.json")
        assert(exists == false)
      }
    }
    "regenerateAfterBloopDirRemoval" - {
      testEvaluator(testBloop.install())
      val bloopDir = workdir / ".bloop"
      val files = os.list(bloopDir)
      val size = (if (isWin) 4 else 5)
      assert(files.size == size)
      os.remove.all(bloopDir)
      testEvaluator(testBloop.install())
      val files2 = os.list(bloopDir)
      assert(files2.size == size)
      assert(files2 == files)
    }
  }

}
