package mill.contrib.scoverage

import mill._
import mill.api.Result
import mill.scalalib._
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

object HelloWorldTests extends utest.TestSuite {
  val resourcePath = os.pwd / 'contrib / 'scoverage / 'test / 'resources / "hello-world"
  val sbtResourcePath = os.pwd / 'contrib / 'scoverage / 'test / 'resources / "hello-world-sbt"
  trait HelloBase extends TestUtil.BaseModule {
    def millSourcePath =  TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  object HelloWorld extends HelloBase {
    object core extends ScoverageModule {
      def scalaVersion = "2.12.4"
      def scoverageVersion = "1.3.1"

      object test extends ScoverageTests {
        override def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.5")
        def testFrameworks = Seq("org.scalatest.tools.Framework")
      }
    }
  }

  object HelloWorldSbt extends HelloBase { outer =>
    object core extends ScoverageModule {
      def scalaVersion = "2.12.4"
      def scoverageVersion = "1.3.1"
      override def sources = T.sources(
        millSourcePath / 'src / 'main / 'scala,
        millSourcePath / 'src / 'main / 'java
      )
      override def resources = T.sources{ millSourcePath / 'src / 'main / 'resources }

      object test extends ScoverageTests {
        override def ivyDeps = Agg(ivy"org.scalatest::scalatest:3.0.5")
        def testFrameworks = Seq("org.scalatest.tools.Framework")
        override def millSourcePath = outer.millSourcePath
        override def intellijModulePath = outer.millSourcePath / 'src / 'test
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
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.ivyDeps)

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
            val Right((_, _)) = eval.apply(HelloWorld.core.test.compile)
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.htmlReport)
            assert(evalCount > 0)
          }
          "xmlReport" - workspaceTest(HelloWorld) { eval =>
            val Right((_, _)) = eval.apply(HelloWorld.core.test.compile)
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.xmlReport)
            assert(evalCount > 0)
          }
        }
        "test" - {
          "upstreamAssemblyClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.upstreamAssemblyClasspath)

            assert(
              result.map(_.toString).exists(_.contains("scalac-scoverage-runtime")),
              evalCount > 0
            )
          }
          "compileClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.compileClasspath)

            assert(
              result.map(_.toString).exists(_.contains("scalac-scoverage-runtime")),
              evalCount > 0
            )
          }
          "runClasspath" - TestUtil.disableInJava9OrAbove(workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.runClasspath)

            assert(
              result.map(_.toString).exists(_.contains("scalac-scoverage-runtime")),
              evalCount > 0
            )
          })
        }
      }
    }
    "HelloWorldSbt" - {
      "scoverage" - {
        "htmlReport" - workspaceTest(HelloWorldSbt, sbtResourcePath) { eval =>
          val Right((_, _)) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right((result, evalCount)) = eval.apply(HelloWorldSbt.core.scoverage.htmlReport)
          assert(evalCount > 0)
        }
        "xmlReport" - workspaceTest(HelloWorldSbt, sbtResourcePath) { eval =>
          val Right((_, _)) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right((result, evalCount)) = eval.apply(HelloWorldSbt.core.scoverage.xmlReport)
          assert(evalCount > 0)
        }
      }
    }
  }
}
