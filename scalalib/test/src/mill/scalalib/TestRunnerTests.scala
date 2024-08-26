package mill.scalalib

import mill.api.Result
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.{Agg, T}
import os.Path
import sbt.testing.Status
import utest._
import utest.framework.TestPath

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.xml.{Elem, NodeSeq, XML}

object TestRunnerTests extends TestSuite {
  object testrunner extends TestBaseModule with ScalaModule {
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object utest extends ScalaTests with TestModule.Utest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }

    object scalatest extends ScalaTests with TestModule.ScalaTest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"org.scalatest::scalatest:${sys.props.getOrElse("TEST_SCALATEST_VERSION", ???)}"
        )
      }
    }

    trait DoneMessage extends ScalaTests {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"org.scala-sbt:test-interface:${sys.props.getOrElse("TEST_TEST_INTERFACE_VERSION", ???)}"
        )
      }
    }
    object doneMessageSuccess extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageSuccessFramework"
    }
    object doneMessageFailure extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageFailureFramework"
    }
    object doneMessageNull extends DoneMessage {
      def testFramework = "mill.scalalib.DoneMessageNullFramework"
    }

    object ziotest extends ScalaTests with TestModule.ZioTest {
      override def ivyDeps = T {
        super.ivyDeps() ++ Agg(
          ivy"dev.zio::zio-test:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}",
          ivy"dev.zio::zio-test-sbt:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "testrunner"

  override def tests: Tests = Tests {
    test("TestRunner") - {
      test("utest") - {
        test("test case lookup") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(result) = eval.apply(testrunner.utest.test())
          val test = result.value.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
          assert(
            test._2.size == 3
          )
          junitReportIn(eval.outPath, "utest").shouldHave(3 -> Status.Success)
        }
        test("discoveredTestClasses") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(result) = eval.apply(testrunner.utest.discoveredTestClasses)
          val expected = Seq(
            "mill.scalalib.BarTests",
            "mill.scalalib.FooTests",
            "mill.scalalib.FoobarTests"
          )
          assert(result.value == expected)
          expected
        }
        test("testOnly") - {
          def testOnly(eval: UnitTester, args: Seq[String], size: Int) = {
            val Right(result) = eval.apply(testrunner.utest.testOnly(args: _*))
            val testOnly = result.value.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
            assert(
              testOnly._2.size == size
            )
          }

          test("suffix") {
            val eval = UnitTester(testrunner, resourcePath)
            testOnly(eval, Seq("*arTests"), 2)
          }
          test("prefix") {
            val eval = UnitTester(testrunner, resourcePath)
            testOnly(eval, Seq("mill.scalalib.FooT*"), 1)
          }
          test("exactly") {
            val eval = UnitTester(testrunner, resourcePath)
            testOnly(eval, Seq("mill.scalalib.FooTests"), 1)
          }
          test("multi") {
            val eval = UnitTester(testrunner, resourcePath)
            testOnly(eval, Seq("*Bar*", "*bar*"), 2)
          }
          test("noMatch") {
            val eval = UnitTester(testrunner, resourcePath)
            val Left(Result.Failure(msg, _)) =
              eval.apply(testrunner.utest.testOnly("noMatch", "noMatch*2"))
            assert(
              msg == "Test selector does not match any test: noMatch noMatch*2\nRun discoveredTestClasses to see available tests"
            )
          }
        }
      }

      test("doneMessage") {
        test("failure") {
          val outStream = new ByteArrayOutputStream()
          val eval = UnitTester(
            testrunner,
            outStream = new PrintStream(outStream, true),
            sourceRoot = resourcePath
          )

          val Left(Result.Failure(msg, _)) = eval(testrunner.doneMessageFailure.test())
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test failure done message"))
          junitReportIn(eval.outPath, "doneMessageFailure").shouldHave(1 -> Status.Failure)
        }
        test("success") {
          val outStream = new ByteArrayOutputStream()
          val eval = UnitTester(
            testrunner,
            outStream = new PrintStream(outStream, true),
            sourceRoot = resourcePath
          )

          val Right(_) = eval(testrunner.doneMessageSuccess.test())
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test success done message"))
        }

        test("null") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(_) = eval(testrunner.doneMessageNull.test())
        }
      }
      test("ScalaTest") {
        test("test") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(result) = eval(testrunner.scalatest.test())
          assert(result.value._2.size == 2)
          junitReportIn(eval.outPath, "scalatest").shouldHave(2 -> Status.Success)
        }
        test("discoveredTestClasses") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(result) = eval.apply(testrunner.scalatest.discoveredTestClasses)
          val expected = Seq("mill.scalalib.ScalaTestSpec")
          assert(result.value == expected)
          expected
        }
      }

      test("ZioTest") {
        test("test") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(result) = eval(testrunner.ziotest.test())
          assert(result.value._2.size == 1)
          junitReportIn(eval.outPath, "ziotest").shouldHave(1 -> Status.Success)
        }
        test("discoveredTestClasses") {
          val eval = UnitTester(testrunner, resourcePath)
          val Right(result) = eval.apply(testrunner.ziotest.discoveredTestClasses)
          val expected = Seq("mill.scalalib.ZioTestSpec")
          assert(result.value == expected)
          expected
        }
      }
    }
  }

  trait JUnitReportMatch {
    def shouldHave(statuses: (Int, Status)*): Unit
  }
  private def junitReportIn(
      outPath: Path,
      moduleName: String,
      action: String = "test"
  ): JUnitReportMatch = {
    val reportPath: Path = outPath / moduleName / s"$action.dest" / "test-report.xml"
    val reportXML = XML.loadFile(reportPath.toIO)
    new JUnitReportMatch {
      override def shouldHave(statuses: (Int, Status)*): Unit = {
        def getValue(attribute: String): Int =
          reportXML.attribute(attribute).map(_.toString).getOrElse("0").toInt
        statuses.foreach { case (expectedQuantity: Int, status: Status) =>
          status match {
            case Status.Success =>
              val testCases: NodeSeq = reportXML \\ "testcase"
              val actualSucceededTestCases: Int =
                testCases.count(tc => !tc.child.exists(n => n.isInstanceOf[Elem]))
              assert(expectedQuantity == actualSucceededTestCases)
            case _ =>
              val statusXML = reportXML \\ status.name().toLowerCase
              val nbSpecificStatusElement = statusXML.size
              assert(expectedQuantity == nbSpecificStatusElement)
              val specificStatusAttributeValue = getValue(s"${status.name().toLowerCase}s")
              assert(expectedQuantity == specificStatusAttributeValue)
          }
        }
        val expectedNbTests = statuses.map(_._1).sum
        val actualNbTests = getValue("tests")
        assert(expectedNbTests == actualNbTests)
      }
    }
  }
}
