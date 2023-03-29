package mill.contrib.scoverage

import mill._
import mill.api.Result
import mill.contrib.buildinfo.BuildInfo
import mill.scalalib.{DepSyntax, SbtModule, ScalaModule, TestModule}
import mill.scalalib.api.ZincWorkerUtil
import mill.util.{TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

trait HelloWorldTests extends utest.TestSuite {
  def threadCount: Option[Int] = Some(1)

  def testScalaVersion: String

  def testScoverageVersion: String

  def testScalatestVersion: String = "3.2.13"

  def isScala3: Boolean = testScalaVersion.startsWith("3.")
  def isScov3: Boolean = testScoverageVersion.startsWith("2.")

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

  object HelloWorldSbt extends HelloBase {
    outer =>
    object core extends SbtModule with ScoverageModule {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion

      object test extends SbtModuleTests with ScoverageTests with TestModule.ScalaTest {
        override def ivyDeps = Agg(ivy"org.scalatest::scalatest:${testScalatestVersion}")
      }
    }
  }

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      resourcePath: os.Path = resourcePath,
      debugEnabled: Boolean = false
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, threads = threadCount, debugEnabled = debugEnabled)
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

            val expected = if (isScala3) Agg.empty
            else Agg(
              ivy"org.scoverage::scalac-scoverage-runtime:${testScoverageVersion}"
            )

            assert(
              result == expected,
              evalCount > 0
            )
          }
          "scalacPluginIvyDeps" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.scalacPluginIvyDeps)

            val expected = (isScov3, isScala3) match {
              case (true, true) => Agg.empty
              case (true, false) =>
                Agg(
                  ivy"org.scoverage:::scalac-scoverage-plugin:${testScoverageVersion}",
                  ivy"org.scoverage::scalac-scoverage-domain:${testScoverageVersion}",
                  ivy"org.scoverage::scalac-scoverage-serializer:${testScoverageVersion}",
                  ivy"org.scoverage::scalac-scoverage-reporter:${testScoverageVersion}"
                )
              case (false, _) =>
                Agg(
                  ivy"org.scoverage:::scalac-scoverage-plugin:${testScoverageVersion}"
                )
            }
            assert(
              result == expected,
              evalCount > 0
            )
          }
          "data" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) = eval.apply(HelloWorld.core.scoverage.data)

            val resultPath = result.path.toIO.getPath.replace("""\""", "/")
            val expectedEnd =
              "/target/workspace/mill/contrib/scoverage/HelloWorldTests/eval/HelloWorld/core/scoverage/data/core/scoverage/data.dest"

            assert(
              resultPath.endsWith(expectedEnd),
              evalCount > 0
            )
          }
          "htmlReport" - workspaceTest(HelloWorld) { eval =>
            val Right((_, _)) = eval.apply(HelloWorld.core.test.compile)
            val res = eval.apply(HelloWorld.core.scoverage.htmlReport())
            if (
              res.isLeft && testScalaVersion.startsWith("3.2") && testScoverageVersion.startsWith(
                "2."
              )
            ) {
              s"""Disabled for Scoverage ${testScoverageVersion} on Scala ${testScalaVersion}, as it fails with "No source root found" message"""
            } else {
              assert(res.isRight)
              val Right((_, evalCount)) = res
              assert(evalCount > 0)
              ""
            }
          }
          "xmlReport" - workspaceTest(HelloWorld) { eval =>
            val Right((_, _)) = eval.apply(HelloWorld.core.test.compile)
            val res = eval.apply(HelloWorld.core.scoverage.xmlReport())
            if (
              res.isLeft && testScalaVersion.startsWith("3.2") && testScoverageVersion.startsWith(
                "2."
              )
            ) {
              s"""Disabled for Scoverage ${testScoverageVersion} on Scala ${testScalaVersion}, as it fails with "No source root found" message"""
            } else {
              assert(res.isRight)
              val Right((_, evalCount)) = res
              assert(evalCount > 0)
              ""
            }
          }
          "console" - workspaceTest(HelloWorld) { eval =>
            val Right((_, _)) = eval.apply(HelloWorld.core.test.compile)
            val Right((_, evalCount)) = eval.apply(HelloWorld.core.scoverage.consoleReport())
            assert(evalCount > 0)
          }
        }
        test("test") - {
          "upstreamAssemblyClasspath" - workspaceTest(HelloWorld) { eval =>
            val Right((result, evalCount)) =
              eval.apply(HelloWorld.core.scoverage.upstreamAssemblyClasspath)

            val runtimeExistsOnClasspath =
              result.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))
            if (isScala3) {
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
            if (isScala3) {
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

            if (isScala3) {
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
          val Right((result, evalCount)) = eval.apply(HelloWorldSbt.core.scoverage.htmlReport())
          assert(evalCount > 0)
        }
        "xmlReport" - workspaceTest(HelloWorldSbt, sbtResourcePath) { eval =>
          val Right((_, _)) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right((result, evalCount)) = eval.apply(HelloWorldSbt.core.scoverage.xmlReport())
          assert(evalCount > 0)
        }
        "console" - workspaceTest(HelloWorldSbt, sbtResourcePath) { eval =>
          val Right((_, _)) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right((result, evalCount)) = eval.apply(HelloWorldSbt.core.scoverage.consoleReport())
          assert(evalCount > 0)
        }
      }
    }
  }
}

trait FailedWorldTests extends HelloWorldTests {
  def errorMsg: String
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)

  override def tests: Tests = utest.Tests {
    "HelloWorld" - {
      val mod = HelloWorld
      "shouldFail" - {
        "scoverageToolsCp" - workspaceTest(mod) { eval =>
          val Left(Result.Failure(msg, _)) = eval.apply(mod.core.scoverageToolsClasspath)
          assert(msg == errorMsg)
        }
        "other" - workspaceTest(mod) { eval =>
          val Left(Result.Failure(msg, _)) = eval.apply(mod.core.scoverage.xmlReport())
          assert(msg == errorMsg)
        }
      }
    }
    "HelloWorldSbt" - {
      val mod = HelloWorldSbt
      "shouldFail" - {
        "scoverageToolsCp" - workspaceTest(mod) { eval =>
          val res = eval.apply(mod.core.scoverageToolsClasspath)
          assert(res.isLeft)
          println(s"res: ${res}")
          val Left(Result.Failure(msg, _)) = res
          assert(msg == errorMsg)
        }
        "other" - workspaceTest(mod) { eval =>
          val Left(Result.Failure(msg, _)) = eval.apply(mod.core.scoverage.xmlReport())
          assert(msg == errorMsg)
        }
      }
    }
  }
}

object Scoverage1Tests_2_12 extends HelloWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
}

object Scoverage1Tests_2_13 extends HelloWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
}

object Scoverage2Tests_2_13 extends HelloWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
}

object Scoverage2Tests_3_2 extends HelloWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_3_2_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
}

object Scoverage1Tests_3_0 extends FailedWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_3_0_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
  override val errorMsg =
    "Scala 3.0 and 3.1 is not supported by Scoverage. You have to update to at least Scala 3.2 and Scoverage 2.0"
}

object Scoverage1Tests_3_2 extends FailedWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_3_2_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE_VERSION", ???)
  override val errorMsg =
    "Scoverage 1.x does not support Scala 3. You have to update to at least Scala 3.2 and Scoverage 2.0"
}

object Scoverage2Tests_2_11 extends FailedWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_11_VERSION", ???)
  override val errorMsg =
    "Scoverage 2.x is not compatible with Scala 2.11. Consider using Scoverage 1.x or switch to a newer Scala version."
}

object Scoverage2Tests_3_1 extends FailedWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_3_1_VERSION", ???)
  override val errorMsg =
    "Scala 3.0 and 3.1 is not supported by Scoverage. You have to update to at least Scala 3.2 and Scoverage 2.0"
}
