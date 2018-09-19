package mill.scalacoverage

import ammonite.ops._
import mill.define.{Discover, Target}
import mill.eval.Result
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.scalalib.{CrossScalaModule, Dep, DepSyntax, PublishModule, TestRunner}
import mill.util.{Loose, TestEvaluator, TestUtil}
import mill.{Agg, Cross, T, define}
import utest.{TestSuite, Tests, _}

object ScoverageReportModuleTest extends TestSuite {

  val workspacePath = TestUtil.getOutPathStatic() / "basic-coverage"

  trait CoverageReportModule extends CrossScalaModule with ScoverageReportModule {
    override def millSourcePath = workspacePath
  }

  object CoverageReport extends TestUtil.BaseModule {
    val matrix = for {
      scala <- Seq("2.11.12")
      scoverageVersion <- Seq("1.4.0-M3")
    } yield (scala, scoverageVersion)

    abstract class BuildModule(val crossScalaVersion: String, override val scoverageVersion: String) extends CoverageReportModule {
      override def artifactName = "basic-coverage"
    }

    object buildUTest extends Cross[BuildModuleUtest](matrix:_*)
    class BuildModuleUtest(crossScalaVersion: String, scoverageVersion: String)
      extends BuildModule(crossScalaVersion, scoverageVersion) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'basic / 'utest }
        def testFrameworks = Seq("utest.runner.Framework")
        override def ivyDeps = Agg(
          ivy"com.lihaoyi::utest::0.6.4"
        )
      }
      def testIvyDeps = T{ test.ivyDeps()}
    }

    object buildScalaTest extends Cross[BuildModuleScalaTest](matrix:_*)
    class BuildModuleScalaTest(crossScalaVersion: String, scoverageVersion: String)
      extends BuildModule(crossScalaVersion, scoverageVersion) {
      object test extends super.Tests {
        override def sources = T.sources{ millSourcePath / 'src / 'basic / 'scalatest }
        def testFrameworks = Seq("org.scalatest.tools.Framework")
        override def ivyDeps = Agg(
          ivy"org.scalatest::scalatest::3.2.0-SNAP10"
        )
      }
      def testIvyDeps = T{ test.ivyDeps()}
    }
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = pwd / 'scalacoverage / 'test / 'resources / "basic-coverage"

  val basicCoverageEvaluator = TestEvaluator.static(CoverageReport)

  def tests: Tests = Tests {
    prepareWorkspace()
    // TODO: unit tests for the aggregate report
    'scalacoverage - {
      "coverageReport" - {
        def runTests(testTask: define.Command[(String, Seq[TestRunner.Result])]): Map[String, Map[String, TestRunner.Result]] = {
          // FIXME: simplify this
          val testResults: Seq[TestRunner.Result] = basicCoverageEvaluator(testTask) match {
            case Right(((_, res), _)) => res
            case Left(Result.Failure(_, Some((_, res)))) => res
            case Left(Result.Exception(thr, stack)) =>
              stack.value.foreach { elem => System.err.println(elem) }
              System.err.flush()
              throw thr
          }

          testResults
            .groupBy(_.fullyQualifiedName)
            .mapValues(_.map(e => e.selector -> e).toMap)
        }
        def runCoverage(testTask: define.Target[Path]): Unit = {
          val Right((reportDir: Path, numTasks: Int)) = basicCoverageEvaluator.apply(testTask)
          val scoverageXml = reportDir.toNIO.resolve("scoverage-report").resolve("scoverage.xml")
          assert(scoverageXml.toFile.exists())
        }
        def checkUtest(scalaVersion: String, scoverageVersion: String) = {
          val resultMap = runTests(CoverageReport.buildUTest(scalaVersion, scoverageVersion).test.test())
          val mainTests = resultMap("basic.MainTests")
          assert(
            mainTests.size == 2,
            mainTests("basic.MainTests.runMethod.zero").status == "Success",
            mainTests("basic.MainTests.runMethod.one").status == "Success"
          )
          runCoverage(CoverageReport.buildUTest(scalaVersion, scoverageVersion).coverageReport)
        }

        def checkScalaTest(scalaVersion: String, scoverageVersion: String) = {
          val resultMap = runTests(CoverageReport.buildScalaTest(scalaVersion, scoverageVersion).test.test())
          val mainSpec = resultMap("basic.MainSpec")
          assert(
            mainSpec.size == 2,
            mainSpec("runMethod should return 0 when 0 is given").status == "Success",
            mainSpec("runMethod should return 1 when 1 is given").status == "Success"
          )
          runCoverage(CoverageReport.buildScalaTest(scalaVersion, scoverageVersion).coverageReport)
        }
        'utest_scala_2_11_12_scoverage_1_4 - (checkUtest("2.11.12", "1.4.0-M3"))
        'scalaTest_scala_2_11_12_scoverage_1_4 - (checkScalaTest("2.11.12", "1.4.0-M3"))
      }
    }
  }

  def prepareWorkspace(): Unit = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    cp(millSourcePath, workspacePath)
  }
}
