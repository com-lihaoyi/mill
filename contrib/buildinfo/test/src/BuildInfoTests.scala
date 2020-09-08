package mill.contrib.buildinfo

import mill._
import mill.define.Sources
import mill.define.Target
import mill.scalalib.ScalaModule
import mill.util.TestEvaluator
import mill.util.TestUtil
import os.Path
import utest._
import utest.framework.TestPath

object BuildInfoTests extends TestSuite {

  val scalaVersionString = "2.12.4"
  trait BuildInfoModule
      extends TestUtil.BaseModule
      with scalalib.ScalaModule
      with BuildInfo {
    // override build root to test custom builds/modules
    override def millSourcePath: Path = TestUtil.getSrcPathStatic()
    override def scalaVersion = scalaVersionString
  }

  object EmptyBuildInfo extends BuildInfoModule

  object BuildInfo extends BuildInfoModule {
    def buildInfoMembers = T {
      Map(
        "scalaVersion" -> scalaVersion(),
      )
    }
  }

  object BuildInfoSettings extends BuildInfoModule {
    def buildInfoPackageName = Some("foo")
    def buildInfoObjectName = "bar"
    def buildInfoMembers = T {
      Map(
        "scalaVersion" -> scalaVersion()
      )
    }
  }

  val testModuleSourcesPath
    : Path = os.pwd / 'contrib / 'buildinfo / 'test / 'resources / "buildinfo"

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)(
      implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(testModuleSourcesPath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {

    'buildinfo - {
      'createSourcefile - workspaceTest(BuildInfo) { eval =>
        val expected =
          s"""|
              |object BuildInfo {
              |  def scalaVersion = "2.12.4"
              |
              |  val toMap = Map[String, String](
              |    "scalaVersion" -> scalaVersion)
              |}""".stripMargin
        val Right(((result, _), evalCount)) =
          eval.apply(BuildInfo.generatedBuildInfo)
        assert(
          result.head.path == eval.outPath / 'generatedBuildInfo / 'dest / "BuildInfo.scala" &&
            os.exists(result.head.path) &&
            os.read(result.head.path) == expected
        )
      }

      'notCreateEmptySourcefile - workspaceTest(EmptyBuildInfo) { eval =>
        val Right(((result, _), evalCount)) =
          eval.apply(EmptyBuildInfo.generatedBuildInfo)
        assert(
          result.isEmpty &&
            !os.exists(
              eval.outPath / 'generatedBuildInfo / 'dest / "BuildInfo.scala")
        )
      }

      'supportCustomSettings - workspaceTest(BuildInfoSettings) { eval =>
        val expected =
          s"""|package foo
              |
              |object bar {
              |  def scalaVersion = "2.12.4"
              |
              |  val toMap = Map[String, String](
              |    "scalaVersion" -> scalaVersion)
              |}""".stripMargin
        val Right(((result, _), evalCount)) =
          eval.apply(BuildInfoSettings.generatedBuildInfo)
        assert(
          result.head.path == eval.outPath / 'generatedBuildInfo / 'dest / "BuildInfo.scala" &&
            os.exists(result.head.path) &&
            os.read(result.head.path) == expected
        )
      }

      'compile - workspaceTest(BuildInfo) { eval =>
        val Right((result, evalCount)) = eval.apply(BuildInfo.compile)
        assert(true)
      }

      'run - workspaceTest(BuildInfo) { eval =>
        val runResult = eval.outPath / "hello-mill"
        val Right((result, evalCount)) =
          eval.apply(BuildInfo.run(runResult.toString))
        assert(
          os.exists(runResult),
          os.read(runResult) == scalaVersionString
        )
      }

      "generatedSources must be a folder" - workspaceTest(BuildInfo) { eval =>
        val buildInfoGeneratedSourcesFolder = eval.outPath / 'generatedBuildInfo / 'dest
        val Right((result, evalCount)) = eval.apply(BuildInfo.generatedSources)
        assert(
          result.size == 1,
          os.isDir(result.head.path),
          result.head.path == buildInfoGeneratedSourcesFolder
        )
      }
    }
  }
}
