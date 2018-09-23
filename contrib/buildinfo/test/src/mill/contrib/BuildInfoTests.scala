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

  val booleanVal: Boolean = true
  val byteVal: Byte = 127
  val shortVal: Short = 32767
  val intVal: Int = 2147483647
  val longVal: Long = 9223372036854775807l
  val floatVal: Float = 3.40282346638528860e+38f
  val doubleVal: Double = 1.79769313486231570e+308d
  val charVal: Char = 'Æ'
  val stringVal: String = "2.12.4"

  val packageName = "foo"
  val objectName = "bar"
  trait BuildInfoModule extends TestUtil.BaseModule with scalalib.ScalaModule with BuildInfo {
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    def scalaVersion = "2.12.6"
  }

  object EmptyBuildInfo extends BuildInfoModule

  object BuildInfo extends BuildInfoModule {
    def buildInfoMembers =
      Map(
        "booleanVal" -> booleanVal,
        "byteVal" -> byteVal,
        "shortVal" -> shortVal,
        "intVal" -> intVal,
        "longVal" -> longVal,
        "floatVal" -> floatVal,
        "doubleVal" -> doubleVal,
        "charVal" -> charVal,
        "stringVal" -> stringVal
      )
  }

  object BuildInfoSettings extends BuildInfoModule {
    def buildInfoPackageName = Some(packageName)
    def buildInfoObjectName = objectName
    def buildInfoMembers =
      Map(
        "booleanVal" -> booleanVal,
        "byteVal" -> byteVal,
        "shortVal" -> shortVal,
        "intVal" -> intVal,
        "longVal" -> longVal,
        "floatVal" -> floatVal,
        "doubleVal" -> doubleVal,
        "charVal" -> charVal,
        "stringVal" -> stringVal
      )
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
        val Right((result, evalCount)) = eval.apply(BuildInfo.buildInfo)
        assert(
          result.head.path == eval.outPath / 'buildInfo / 'dest / "BuildInfo.scala" &&
            exists(result.head.path))
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
          s"""|package $packageName
              |object $objectName {""".stripMargin
        val Right((result, evalCount)) = eval.apply(BuildInfoSettings.buildInfo)
        assert(
          result.head.path == eval.outPath / 'buildInfo / 'dest / "BuildInfo.scala" &&
            exists(result.head.path) &&
            (read! result.head.path).startsWith(expected)
        )
      }

      'compile - workspaceTest(BuildInfo){ eval =>
        val Right((result, evalCount)) = eval.apply(BuildInfo.compile)
        assert(true)
      }

      'run - workspaceTest(BuildInfo){ eval =>
        val runResult = eval.outPath / "hello-mill"
        val Right((result, evalCount)) = eval.apply(BuildInfo.run(runResult.toString))
        val expected = Array(
          s"$booleanVal",
          s"$byteVal",
          s"$shortVal",
          s"$intVal",
          s"$longVal",
          s"$floatVal",
          s"$doubleVal",
          s"$charVal",
          s"$stringVal")
        val output = read(runResult).trim.split("\n")
        assert(
          exists(runResult),
          output.sameElements(expected))
      }
    }
  }
}
