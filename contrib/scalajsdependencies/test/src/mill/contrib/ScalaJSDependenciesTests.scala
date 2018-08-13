package mill.contrib

import ammonite.ops._
import java.util.jar.JarFile
import mill._
import mill.scalalib._
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

object ScalaJSDependenciesTests extends TestSuite {

  val scalaVersionString = "2.12.4"
  val scalaJSVersionString = "0.6.22"
  trait ScalaJSDependenciesModule extends TestUtil.BaseModule with scalajslib.ScalaJSModule with ScalaJSDependencies {
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
    def scalaVersion = scalaVersionString
    def scalaJSVersion = scalaJSVersionString
    def ivyDeps = Agg(ivy"org.singlespaced::scalajs-d3::0.3.4")
  }


  object ScalaJSDependencies extends ScalaJSDependenciesModule

  object ScalaJSDependenciesSettings extends ScalaJSDependenciesModule {
    def jsdependenciesOutputFileName = T{ "foo.js" }
  }

  val resourcePath = pwd / 'contrib / 'scalajsdependencies / 'test / 'resources / 'scalajsdependencies

  def workspaceTest[T, M <: TestUtil.BaseModule](m: M, resourcePath: Path = resourcePath)
                                                (t: TestEvaluator[M] => T)
                                                (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {

    val expected = "!function("
    'scalajsdependencies - {
      'packageJSDependencies - workspaceTest(ScalaJSDependencies){ eval =>
        val Right((result, evalCount)) = eval.apply(ScalaJSDependencies.packageJSDependencies)
        println(result.path)
        assert(
          result.path == eval.outPath / 'packageJSDependencies / 'dest / "out-deps.js" &&
            exists(result.path) &&
            (read! result.path).take(10) == expected
        )
      }
      'triggeredByFastOptJS - workspaceTest(ScalaJSDependencies){ eval =>
        // val Right((result, evalCount)) = eval.apply(ScalaJSDependencies.fastOpt)
        val Left(Result.Exception(result, evalCount)) = eval.apply(ScalaJSDependencies.fastOpt)
        result.printStackTrace
        assert(
            (read! eval.outPath / 'packageJSDependencies / 'dest / "out-deps.js").take(10) == expected
        )
        assert(false)
      }
      'triggeredByfullOptJS - workspaceTest(ScalaJSDependencies){ eval =>
        val Right((result, evalCount)) = eval.apply(ScalaJSDependencies.fullOpt)
        assert(
            (read! eval.outPath / 'packageJSDependencies / 'dest / "out-deps.js").take(10) == expected
        )
      }
    }
  }
}
