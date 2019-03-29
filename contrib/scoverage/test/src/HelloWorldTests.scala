package mill.contrib.scoverage

import mill._
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object HelloWorldTests extends utest.TestSuite {
  val resourcePath = os.pwd / 'contrib / 'scoverage / 'test / 'resources / "hello-world"
  trait HelloBase extends TestUtil.BaseModule {
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  object HelloWorld extends HelloBase {
    object core extends ScoverageModule {
      def scalaVersion = "2.11.8"
      def scoverageVersion = "1.3.1"

      object test extends ScoverageTests {
        override def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.5")
        def testFrameworks = Seq("org.scalatest.tools.Framework")
      }
    }
  }

  def workspaceTest[T](m: TestUtil.BaseModule, resourcePath: os.Path = resourcePath)
                      (t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: utest.Tests = utest.Tests {
    "HelloWorld" - {
      "core" - {
        "scoverageVersion" - workspaceTest(HelloWorld) { eval =>
          val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverageVersion)

          assert(
            result == "1.3.1",
            evalCount > 0
          )
        }
        "scoverage" - {
          "ivyDeps" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.ivyDeps)

            assert(
              result == Agg(ivy"org.scoverage::scalac-scoverage-runtime:1.3.1"),
              evalCount > 0
            )
          }
          "scalacPluginIvyDeps" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.scalacPluginIvyDeps)

            assert(
              result == Agg(ivy"org.scoverage::scalac-scoverage-plugin:1.3.1"),
              evalCount > 0
            )
          }
          "dataDir" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.dataDir)

            assert(
              result.toString.endsWith("mill/target/workspace/mill/contrib/scoverage/HelloWorldTests/eval/HelloWorld/core/scoverage/dataDir/core/scoverage/data"),
              evalCount > 0
            )
          }
          "htmlReport" - workspaceTest(HelloWorld) { eval =>
             eval.apply(HelloWorld.core.scoverage.htmlReport)
             assert(true)
          }
        }
        "test" - {
          "ivyDeps" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.test.ivyDeps)

            assert(
              result == Agg(
                ivy"org.scoverage::scalac-scoverage-runtime:1.3.1",
                ivy"org.scalatest::scalatest:3.0.5"
              ),
              evalCount > 0
            )
          }
        }
      }
    }
  }
}
