package mill.contrib.scoverage

import mill._
import mill.api.Result
import mill.contrib.buildinfo.BuildInfo
import mill.scalalib.{DepSyntax, SbtModule, ScalaModule, TestModule}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
import utest.framework.TestPath

trait HelloWorldTests extends utest.TestSuite {
  def threadCount: Option[Int] = Some(1)

  def testScalaVersion: String

  def testScoverageVersion: String

  def testScalatestVersion: String = "3.2.13"

  def isScala3: Boolean = testScalaVersion.startsWith("3.")
  def isScov3: Boolean = testScoverageVersion.startsWith("2.")

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world"
  val sbtResourcePath = resourcePath / os.up / "hello-world-sbt"
  val unmanagedFile = resourcePath / "unmanaged.xml"

  object HelloWorld extends TestBaseModule {
    object other extends ScalaModule {
      def scalaVersion = testScalaVersion
    }

    object core extends ScoverageModule with BuildInfo {
      def scalaVersion = testScalaVersion

      def scoverageVersion = testScoverageVersion

      override def unmanagedClasspath = Agg(PathRef(unmanagedFile))

      override def moduleDeps = Seq(other)

      def buildInfoPackageName = "bar"
      override def buildInfoMembers = Seq(
        BuildInfo.Value("scoverageVersion", scoverageVersion())
      )

      object test extends ScoverageTests with TestModule.ScalaTest {
        override def ivyDeps = Agg(mvn"org.scalatest::scalatest:${testScalatestVersion}")
      }
    }
  }

  object HelloWorldSbt extends TestBaseModule {
    object core extends SbtModule with ScoverageModule {
      def scalaVersion = testScalaVersion
      def scoverageVersion = testScoverageVersion

      object test extends SbtTests with ScoverageTests with TestModule.ScalaTest {
        override def ivyDeps = Agg(mvn"org.scalatest::scalatest:${testScalatestVersion}")
      }
    }
  }

  def tests: utest.Tests = utest.Tests {
    test("HelloWorld") {
      test("core") {
        test("scoverageVersion") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
          val Right(result) = eval.apply(HelloWorld.core.scoverageVersion)

          assert(
            result.value == testScoverageVersion,
            result.evalCount > 0
          )
        }
        test("scoverage") {
          test("unmanagedClasspath") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) =
              eval.apply(HelloWorld.core.scoverage.unmanagedClasspath)

            assert(
              result.value.map(_.toString).iterator.exists(_.contains("unmanaged.xml")),
              result.evalCount > 0
            )
          }
          test("ivyDeps") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) =
              eval.apply(HelloWorld.core.scoverage.ivyDeps)

            val expected = if (isScala3) Agg.empty
            else Agg(
              mvn"org.scoverage::scalac-scoverage-runtime:${testScoverageVersion}"
            )

            assert(
              result.value == expected,
              result.evalCount > 0
            )
          }
          test("scalacPluginIvyDeps") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) =
              eval.apply(HelloWorld.core.scoverage.scalacPluginIvyDeps)

            val expected = (isScov3, isScala3) match {
              case (true, true) => Agg.empty
              case (true, false) =>
                Agg(
                  mvn"org.scoverage:::scalac-scoverage-plugin:${testScoverageVersion}",
                  mvn"org.scoverage::scalac-scoverage-domain:${testScoverageVersion}",
                  mvn"org.scoverage::scalac-scoverage-serializer:${testScoverageVersion}",
                  mvn"org.scoverage::scalac-scoverage-reporter:${testScoverageVersion}"
                )
              case (false, _) =>
                Agg(
                  mvn"org.scoverage:::scalac-scoverage-plugin:${testScoverageVersion}"
                )
            }
            assert(
              result.value == expected,
              result.evalCount > 0
            )
          }
          test("data") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) = eval.apply(HelloWorld.core.scoverage.data)

            val resultPath = result.value.path.toIO.getPath.replace("""\""", "/")
            val expectedEnd = "/out/core/scoverage/data.dest"

            assert(
              resultPath.endsWith(expectedEnd),
              result.evalCount > 0
            )
          }
          test("htmlReport") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(_) = eval.apply(HelloWorld.core.test.compile)
            val res = eval.apply(HelloWorld.core.scoverage.htmlReport())
            if (
              res.isLeft && testScalaVersion.startsWith("3.2") && testScoverageVersion.startsWith(
                "2."
              )
            ) {
              s"""Disabled for Scoverage ${testScoverageVersion} on Scala ${testScalaVersion}, as it fails with "No source root found" message"""
            } else {
              assert(res.isRight)
              val Right(result) = res
              assert(result.evalCount > 0)
              ""
            }
          }
          test("xmlReport") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(_) = eval.apply(HelloWorld.core.test.compile)
            val res = eval.apply(HelloWorld.core.scoverage.xmlReport())
            if (
              res.isLeft && testScalaVersion.startsWith("3.2") && testScoverageVersion.startsWith(
                "2."
              )
            ) {
              s"""Disabled for Scoverage ${testScoverageVersion} on Scala ${testScalaVersion}, as it fails with "No source root found" message"""
            } else {
              assert(res.isRight)
              val Right(result) = res
              assert(result.evalCount > 0)
              ""
            }
          }
          test("xmlCoberturaReport") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(_) = eval.apply(HelloWorld.core.test.compile)
            val res = eval.apply(HelloWorld.core.scoverage.xmlCoberturaReport())
            if (
              res.isLeft && testScalaVersion.startsWith("3.2") && testScoverageVersion.startsWith(
                "2."
              )
            ) {
              s"""Disabled for Scoverage ${testScoverageVersion} on Scala ${testScalaVersion}, as it fails with "No source root found" message"""
            } else {
              assert(res.isRight)
              val Right(result) = res
              assert(result.evalCount > 0)
              ""
            }
          }
          test("console") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(_) = eval.apply(HelloWorld.core.test.compile)
            val Right(result) = eval.apply(HelloWorld.core.scoverage.consoleReport())
            assert(result.evalCount > 0)
          }
        }
        test("test") - {
          test("upstreamAssemblyClasspath") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) =
              eval.apply(HelloWorld.core.scoverage.upstreamAssemblyClasspath)

            val runtimeExistsOnClasspath =
              result.value.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))
            if (isScala3) {
              assert(
                !runtimeExistsOnClasspath,
                result.evalCount > 0
              )
            } else {
              assert(
                runtimeExistsOnClasspath,
                result.evalCount > 0
              )
            }
          }
          test("compileClasspath") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) =
              eval.apply(HelloWorld.core.scoverage.compileClasspath)

            val runtimeExistsOnClasspath =
              result.value.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))
            if (isScala3) {
              assert(
                !runtimeExistsOnClasspath,
                result.evalCount > 0
              )
            } else {
              assert(
                runtimeExistsOnClasspath,
                result.evalCount > 0
              )
            }
          }
          test("runClasspath") - UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val Right(result) = eval.apply(HelloWorld.core.scoverage.runClasspath)

            val runtimeExistsOnClasspath =
              result.value.map(_.toString).iterator.exists(_.contains("scalac-scoverage-runtime"))

            if (isScala3) {
              assert(
                !runtimeExistsOnClasspath,
                result.evalCount > 0
              )
            } else {
              assert(
                runtimeExistsOnClasspath,
                result.evalCount > 0
              )
            }
          }
        }
      }
    }
    test("HelloWorldSbt") {
      test("scoverage") {
        test("htmlReport") - UnitTester(HelloWorld, sbtResourcePath).scoped { eval =>
          val Right(_) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right(result) = eval.apply(HelloWorldSbt.core.scoverage.htmlReport())
          assert(result.evalCount > 0)
        }
        test("xmlReport") - UnitTester(HelloWorld, sbtResourcePath).scoped { eval =>
          val Right(_) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right(result) = eval.apply(HelloWorldSbt.core.scoverage.xmlReport())
          assert(result.evalCount > 0)
        }
        test("console") - UnitTester(HelloWorld, sbtResourcePath).scoped { eval =>
          val Right(_) = eval.apply(HelloWorldSbt.core.test.compile)
          val Right(result) =
            eval.apply(HelloWorldSbt.core.scoverage.consoleReport())
          assert(result.evalCount > 0)
        }
      }
    }
  }
}

trait FailedWorldTests extends HelloWorldTests {
  def errorMsg: String
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)

  override def tests: Tests = utest.Tests {
    test("HelloWorld") {
      val mod = HelloWorld
      test("shouldFail") {
        test("scoverageToolsCp") - UnitTester(mod, resourcePath).scoped { eval =>
          val Left(Result.Failure(msg, _)) = eval.apply(mod.core.scoverageToolsClasspath)
          assert(msg == errorMsg)
        }
        test("other") - UnitTester(mod, resourcePath).scoped { eval =>
          val Left(Result.Failure(msg, _)) = eval.apply(mod.core.scoverage.xmlReport())
          assert(msg == errorMsg)
        }
      }
    }
    test("HelloWorldSbt") {
      val mod = HelloWorldSbt
      test("shouldFail") {
        test("scoverageToolsCp") - UnitTester(mod, resourcePath).scoped { eval =>
          val res = eval.apply(mod.core.scoverageToolsClasspath)
          assert(res.isLeft)
          println(s"res: ${res}")
          val Left(Result.Failure(msg, _)) = res
          assert(msg == errorMsg)
        }
        test("other") - UnitTester(mod, resourcePath).scoped { eval =>
          val Left(Result.Failure(msg, _)) = eval.apply(mod.core.scoverage.xmlReport())
          assert(msg == errorMsg)
        }
      }
    }
  }
}

object Scoverage2Tests_2_13 extends HelloWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
}

object Scoverage2Tests_3_2 extends HelloWorldTests {
  override def testScalaVersion: String = sys.props.getOrElse("TEST_SCALA_3_2_VERSION", ???)
  override def testScoverageVersion = sys.props.getOrElse("MILL_SCOVERAGE2_VERSION", ???)
}
