package mill.contrib

import ammonite.ops._
import java.util.jar.JarFile
import mill._
import mill.define.Target
import mill.eval.Result._
import mill.eval.{Evaluator, Result}
import mill.modules.Assembly
import mill.scalalib.publish.VersionControl
import mill.scalalib.publish._
import mill.util.{TestEvaluator, TestUtil}
import scala.collection.JavaConverters._
import utest._
import utest.framework.TestPath


object BuildInfoTests extends TestSuite {

  val scalaVersionString = "2.12.4"
  trait BuildInfoModule extends TestUtil.BaseModule with scalalib.ScalaModule with BuildInfo {
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    def scalaVersion = scalaVersionString
  }

  object EmptyBuildInfo extends BuildInfoModule

  object BuildInfo extends BuildInfoModule {
    def buildInfoMembers=T{
      Map(
        "scalaVersion" -> scalaVersion(),
      )
    }
  }

  object BuildInfoSettings extends BuildInfoModule {
    def buildInfoPackageName = Some("foo")
    def buildInfoObjectName = "bar"
    def buildInfoMembers=T{
      Map(
        "scalaVersion" -> scalaVersion()
      )
    }
  }

  val resourcePath = pwd / 'contrib / 'buildinfo / 'test / 'resources / "buildinfo"

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: Path = resourcePath)
                      (t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {

    'buildinfo - {
      'createSourcefile - workspaceTest(BuildInfo){ eval =>
        val expected = 
          s"""|
              |object BuildInfo {
              |  def scalaVersion = "2.12.4"
              |}""".stripMargin
        val Right((result, evalCount)) = eval.apply(BuildInfo.buildInfo)
        assert(
          result.head.path == eval.outPath / 'buildInfo / 'dest / "BuildInfo.scala" &&
            exists(result.head.path) &&
            read! result.head.path == expected
        )
      }

      'notCreateEmptySourcefile - workspaceTest(EmptyBuildInfo){ eval =>
        val Right((result, evalCount)) = eval.apply(EmptyBuildInfo.buildInfo)
        assert(
          result.isEmpty &&
            !exists(eval.outPath / 'buildInfo / 'dest / "BuildInfo.scala")
        )
      }

      'supportCustomSettings - workspaceTest(BuildInfoSettings){ eval =>
        val expected = 
          s"""|package foo
              |object bar {
              |  def scalaVersion = "2.12.4"
              |}""".stripMargin
        val Right((result, evalCount)) = eval.apply(BuildInfoSettings.buildInfo)
        assert(
          result.head.path == eval.outPath / 'buildInfo / 'dest / "BuildInfo.scala" &&
            exists(result.head.path) &&
            read! result.head.path == expected
        )
      }

      'compile - workspaceTest(BuildInfo){ eval =>
        val Right((result, evalCount)) = eval.apply(BuildInfo.compile)
        assert(true)
      }

      'run - workspaceTest(BuildInfo){ eval =>
        val runResult = eval.outPath / "hello-mill"
        val Right((result, evalCount)) = eval.apply(BuildInfo.run(runResult.toString))
        assert(
          exists(runResult),
          read(runResult) == scalaVersionString)
      }
    }
  }
}
