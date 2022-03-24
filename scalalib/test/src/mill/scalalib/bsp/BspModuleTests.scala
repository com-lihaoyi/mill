package mill.scalalib.bsp

import mill.eval.EvaluatorPaths
import mill.{Agg, T}
import mill.scalalib.{DepSyntax, ScalaModule, TestModule}
import mill.util.{TestEvaluator, TestUtil}
import os.{BasePathImpl, FilePath}
import utest.framework.TestPath
import utest.{TestSuite, Tests, test, _}

object BspModuleTests extends TestSuite {

  val testScalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

  trait BspBase extends TestUtil.BaseModule {
    override def millSourcePath: os.Path =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  object MultiBase extends BspBase {
    object HelloBsp extends ScalaModule {
      def scalaVersion = testScalaVersion
      override def ivyDeps = Agg(ivy"org.slf4j:slf4j-api:1.7.34")
    }
    object HelloBsp2 extends ScalaModule {
      def scalaVersion = testScalaVersion
      override def moduleDeps = Seq(HelloBsp)
      override def ivyDeps = Agg(ivy"ch.qos.logback:logback-classic:1.1.10")
    }
  }

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    t(eval)
  }

  override def tests: Tests = Tests {
    test("bspClasspath") {
      test("single module") {
        workspaceTest(MultiBase) { eval =>
          val Right((result, evalCount)) = eval.apply(
            MultiBase.HelloBsp.bspCompileClasspath(T.task(eval.evaluator.pathsResolver))
          )

          assert(
            result.size == 3,
            result.map(_.path.last).toSet == Set(
              "resources",
              "slf4j-api-1.7.34.jar",
              s"scala-library-${testScalaVersion}.jar"
            ),
            evalCount > 0
          )
        }
        test("dependent module") {
          workspaceTest(MultiBase) { eval =>
            val Right((result, evalCount)) = eval.apply(
              MultiBase.HelloBsp2.bspCompileClasspath(T.task(eval.evaluator.pathsResolver))
            )

            val relResults: Seq[FilePath] = result.map { p =>
              val name = p.path.last
              if (name.endsWith(".jar")) os.rel / name
              else p.path
            }.sortBy(_.toString)

            val expected: Seq[FilePath] = Seq(
              MultiBase.HelloBsp.millSourcePath / "resources",
              MultiBase.HelloBsp2.millSourcePath / "resources",
              EvaluatorPaths.resolveDestPaths(eval.outPath, MultiBase.HelloBsp.compile)
                .dest / "classes",
              os.rel / "slf4j-api-1.7.34.jar",
              os.rel / "logback-core-1.1.10.jar",
              os.rel / "logback-classic-1.1.10.jar",
              os.rel / s"scala-library-${testScalaVersion}.jar"
            ).sortBy(_.toString)

            assert(
              result.size == 7,
              relResults == expected,
              evalCount > 0
            )
          }
        }
      }
    }
  }
}
