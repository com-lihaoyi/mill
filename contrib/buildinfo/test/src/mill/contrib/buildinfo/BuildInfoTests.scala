package mill.contrib.buildinfo


import mill._
import mill.util.TestEvaluator
import mill.util.TestUtil
import os.Path
import utest._
import utest.framework.TestPath

object BuildInfoTests extends TestSuite {

  val scalaVersionString = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  trait BuildInfoModule extends TestUtil.BaseModule with scalalib.ScalaModule with BuildInfo {
    // override build root to test custom builds/modules
    override def millSourcePath: Path = TestUtil.getSrcPathStatic()
    override def scalaVersion = scalaVersionString
  }

  object EmptyBuildInfo extends BuildInfoModule{
    def buildInfoPackageName = "foo"
  }

  object BuildInfo extends BuildInfoModule {
    def buildInfoPackageName = "foo"
    override def buildInfoMembers = T {
      Map(
        "scalaVersion" -> scalaVersion()
      )
    }
  }

  object BuildInfoSettings extends BuildInfoModule {
    override def buildInfoPackageName = "foo"
    override def buildInfoObjectName = "bar"
    override def buildInfoMembers = T {
      Map(
        "scalaVersion" -> scalaVersion()
      )
    }
  }

  val testModuleSourcesPath: Path =
    os.pwd / "contrib" / "buildinfo" / "test" / "resources" / "buildinfo"

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(testModuleSourcesPath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {

    "notCreateEmptySourcefile" - workspaceTest(EmptyBuildInfo) { eval =>
      val Right((result, evalCount)) =
        eval.apply(EmptyBuildInfo.generatedBuildInfo)
      assert(
        result.isEmpty &&
          !os.exists(
            eval.outPath / "generatedBuildInfo.dest" / "BuildInfo.scala"
          )
      )
    }

    "supportCustomSettings" - workspaceTest(BuildInfoSettings) { eval =>
      val Right((result, evalCount)) = eval.apply(BuildInfoSettings.generatedBuildInfo)
      val path = result.head.path

      assert(os.exists(path / "foo" / "bar.scala"))

      val found = os.read(path / "foo" / "bar.scala").replaceAll("(\r\n)|\r", "\n")
      assert(found.contains("object bar"))
    }

    "compile" - workspaceTest(BuildInfo) { eval =>
      val Right((result, evalCount)) = eval.apply(BuildInfo.compile)
      assert(true)
    }

    "run" - workspaceTest(BuildInfo) { eval =>
      val runResult = eval.outPath / "hello-mill"
      val Right((result, evalCount)) =
        eval.apply(BuildInfo.run(runResult.toString))
      assert(
        os.exists(runResult),
        os.read(runResult) == scalaVersionString
      )
    }

    "generatedSources must be a folder" - workspaceTest(BuildInfo) { eval =>
      val buildInfoGeneratedSourcesFolder = eval.outPath / "generatedBuildInfo.dest"
      val Right((result, evalCount)) = eval.apply(BuildInfo.generatedSources)
      assert(
        result.size == 1,
        os.isDir(result.head.path),
        result.head.path == buildInfoGeneratedSourcesFolder
      )
    }
  }
}
