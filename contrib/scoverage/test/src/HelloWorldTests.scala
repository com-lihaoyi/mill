package mill.contrib.scoverage

import mill._
import mill.contrib.buildinfo.BuildInfo
import mill.scalalib.{DepSyntax, ScalaModule, TestModule}
import mill.scalalib.api.ZincWorkerUtil
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

trait HelloWorldTests extends utest.TestSuite {

  def threadCount: Option[Int]
  def testScalaVersion: String
  def testScoverageVersion: String
  def testScalatestVersion: String

  val resourcePath = os.pwd / "contrib" / "scoverage" / "test" / "resources" / "hello-world"
  val sbtResourcePath = resourcePath / os.up / "hello-world-sbt"
  val unmanagedFile = resourcePath / "unmanaged.xml"
  trait HelloBase extends TestUtil.BaseModule {
    override def millSourcePath = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  object HelloWorld extends HelloBase {
    object other extends ScalaModule {
      def scalaVersion = testScalaVersion
    }

    object core extends ScoverageModule with BuildInfo {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion
      override def unmanagedClasspath = Agg(PathRef(unmanagedFile))
      override def moduleDeps = Seq(other)
      override def buildInfoMembers = T {
        Map("scoverageVersion" -> scoverageVersion())
      }

      object test extends ScoverageTests with TestModule.ScalaTest {
        override def ivyDeps = Agg(ivy"org.scalatest::scalatest:${testScalatestVersion}")
      }
    }
  }

  object HelloWorldSbt extends HelloBase { outer =>
    object core extends ScoverageModule {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion
      override def sources = T.sources(
        millSourcePath / "src" / "main" / "scala",
        millSourcePath / "src" / "main" / "java"
      )
      override def resources = T.sources { millSourcePath / "src" / "main" / "resources" }

      object test extends ScoverageTests with TestModule.ScalaTest {
        override def ivyDeps = Agg(ivy"org.scalatest::scalatest:${testScalatestVersion}")
        override def millSourcePath = outer.millSourcePath
        override def intellijModulePath = outer.millSourcePath / "src" / "test"
      }
    }
  }

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, threads = threadCount, debugEnabled = true)
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
            result == testScoverageVersion,
            evalCount > 0
          )
        }
        "scoverage" - {
          "unmanagedClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.unmanagedClasspath)

            assert(
              result.map(_.toString).iterator.exists(_.contains("unmanaged.xml")),
              evalCount > 0
            )
          }
          "ivyDeps" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.ivyDeps)

            val expected = if (ZincWorkerUtil.isScala3(testScalaVersion)) Agg.empty
            else Agg(
              ivy"org.scoverage::scalac-scoverage-runtime:${testScoverageVersion}"
            )

            assert(
              result == expected,
              evalCount > 0
            )
          }
          "scalacPluginIvyDeps" - {
            "scoverage1x" - workspaceTest(HelloWorld) { eval =>
              val Right((result, evalCount)) =
                eval.apply(HelloWorld.core.scoverage.scalacPluginIvyDeps)
              if (testScoverageVersion.startsWith("1.")) {
                assert(
                  result == Agg(
                    ivy"org.scoverage:::scalac-scoverage-plugin:${testScoverageVersion}"
                  ),
                  evalCount > 0
                )
              } else "skipped"
            }
            "scoverage2x" - workspaceTest(HelloWorld) { eval =>
              val Right((result, evalCount)) =
                eval.apply(HelloWorld.core.scoverage.scalacPluginIvyDeps)

              val expected = if (ZincWorkerUtil.isScala3(testScalaVersion)) Agg.empty
              else
                Agg(
                  ivy"org.scoverage:::scalac-scoverage-plugin:${testScoverageVersion}",
                  ivy"org.scoverage::scalac-scoverage-domain:${testScoverageVersion}",
                  ivy"org.scoverage::scalac-scoverage-serializer:${testScoverageVersion}",
                  ivy"org.scoverage::scalac-scoverage-reporter:${testScoverageVersion}"
                )

              if (testScoverageVersion.startsWith("2.")) {
                assert(
                  result == expected,
                  evalCount > 0
                )
              } else "skipped"
            }
          }
          "data" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.data)

            assert(
              result.path.toIO.getPath.replace("""\""", "/").endsWith(
                "mill/target/workspace/mill/contrib/scoverage/HelloWorldTests/eval/HelloWorld/core/scoverage/data/core/scoverage/data.dest"
              ),
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
          "console" - workspaceTest(HelloWorld) { eval =>
            val Right((_, _)) = eval.apply(HelloWorld.core.test.compile)
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.consoleReport)
            assert(evalCount > 0)
          }
        }
        "test" - {
          "upstreamAssemblyClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.upstreamAssemblyClasspath)

            val runtimeExistsOnClasspath =
              result.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))
            if (ZincWorkerUtil.isScala3(testScalaVersion)) {
              assert(
                !runtimeExistsOnClasspath,
                evalCount > 0
              )
            } else {
              assert(
                runtimeExistsOnClasspath,
                evalCount > 0
              )
            }
          }
          "compileClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.compileClasspath)

            val runtimeExistsOnClasspath =
              result.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))
            if (ZincWorkerUtil.isScala3(testScalaVersion)) {
              assert(
                !runtimeExistsOnClasspath,
                evalCount > 0
              )
            } else {
              assert(
                runtimeExistsOnClasspath,
                evalCount > 0
              )
            }
          }
          // TODO: document why we disable for Java9+
          "runClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.runClasspath)

            val runtimeExistsOnClasspath =
              result.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))

            if (ZincWorkerUtil.isScala3(testScalaVersion)) {
              assert(
                !runtimeExistsOnClasspath,
                evalCount > 0
              )
            } else {
              assert(
                runtimeExistsOnClasspath,
                evalCount > 0
              )
            }
          }
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
        "console" - workspaceTest(HelloWorldSbt, sbtResourcePath) { eval =>
          val Right((_, _)) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right((result, evalCount)) = eval.apply(HelloWorldSbt.core.scoverage.consoleReport)
          assert(evalCount > 0)
        }
      }
    }
  }
}

object HelloWorldTests_2_12 extends HelloWorldTests {
  override def threadCount = Some(1)
  override def testScalaVersion: String = sys.props.getOrElse("MILL_SCALA_2_12_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
  override def testScalatestVersion = "3.2.13"
}

object HelloWorldTests_2_13 extends HelloWorldTests {
  override def threadCount = Some(1)
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
  override def testScalatestVersion = "3.2.13"
}

object Scoverage2Tests_2_13 extends HelloWorldTests {
  override def threadCount = Some(1)
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
  override def testScalatestVersion = "3.2.13"
}

object Scoverage2Tests_3 extends HelloWorldTests {
  override def threadCount = Some(1)
  override def testScalaVersion: String = "3.2.0"
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
  override def testScalatestVersion = "3.2.13"
}
