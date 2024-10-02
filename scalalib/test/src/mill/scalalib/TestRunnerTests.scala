package mill.scalalib

import mill.api.Result
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.{Agg, T, Task}
import os.Path
import sbt.testing.Status
import utest._

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.xml.{Elem, NodeSeq, XML}

object TestRunnerTestUtils {
  object testrunner extends TestRunnerTestModule {
    def computeTestForkGrouping(x: Seq[String]) = Seq(x)
  }

  object testrunnerGrouping extends TestRunnerTestModule {
    def computeTestForkGrouping(x: Seq[String]) = x.sorted.grouped(2).toSeq
  }

  trait TestRunnerTestModule extends TestBaseModule with ScalaModule {
    def computeTestForkGrouping(x: Seq[String]): Seq[Seq[String]]
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)

    object utest extends ScalaTests with TestModule.Utest {
      override def testForkGrouping = computeTestForkGrouping(discoveredTestClasses())
      override def ivyDeps = Task {
        super.ivyDeps() ++ Agg(
          ivy"com.lihaoyi::utest:${sys.props.getOrElse("TEST_UTEST_VERSION", ???)}"
        )
      }
    }

    object scalatest extends ScalaTests with TestModule.ScalaTest {
      override def testForkGrouping = computeTestForkGrouping(discoveredTestClasses())
      override def ivyDeps = Task {
        super.ivyDeps() ++ Agg(
          ivy"org.scalatest::scalatest:${sys.props.getOrElse("TEST_SCALATEST_VERSION", ???)}"
        )
      }
    }

    trait DoneMessage extends ScalaTests {
      override def ivyDeps = Task {
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
      override def testForkGrouping = computeTestForkGrouping(discoveredTestClasses())
      override def ivyDeps = Task {
        super.ivyDeps() ++ Agg(
          ivy"dev.zio::zio-test:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}",
          ivy"dev.zio::zio-test-sbt:${sys.props.getOrElse("TEST_ZIOTEST_VERSION", ???)}"
        )
      }
    }
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "testrunner"

  class TestOnlyTester(m: TestRunnerTestModule => TestModule) {
    def testOnly0(f: (UnitTester, TestRunnerTestModule) => Unit) = {
      for (mod <- Seq(testrunner, testrunnerGrouping)) {
        UnitTester(mod, resourcePath).scoped { eval => f(eval, mod) }
      }
    }
    def testOnly(
        args: Seq[String],
        size: Int,
        expectedFileListing: Map[TestModule, Set[String]] = Map()
    ) = {
      testOnly0 { (eval, mod) =>
        val Right(result) = eval.apply(m(mod).testOnly(args: _*))
        val testOnly = result.value
        if (expectedFileListing.nonEmpty) {
          val dest = eval.outPath / m(mod).toString / "testOnly.dest"
          val sortedListed = os.list(dest).map(_.last).sorted
          val sortedExpected = expectedFileListing(m(mod)).toSeq.sorted
          assert(sortedListed == sortedExpected)
        }
        // Regardless of whether tests are grouped or not, the same
        // number of test results appear at the end
        assert(testOnly._2.size == size)
      }
    }
  }
  trait JUnitReportMatch {
    def shouldHave(statuses: (Int, Status)*): Unit
  }
  def junitReportIn(
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

object UtestTestRunnerTests {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("utest") - {
      test("test case lookup") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(testrunner.utest.test())
        val test = result.value.asInstanceOf[(String, Seq[mill.testrunner.TestResult])]
        assert(
          test._2.size == 3
        )
        junitReportIn(eval.outPath, "utest").shouldHave(3 -> Status.Success)
      }
      test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
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
        val tester = new TestOnlyTester(_.utest)
        test("suffix") - tester.testOnly(Seq("*arTests"), 2)
        test("prefix") - tester.testOnly(Seq("mill.scalalib.FooT*"), 1)
        test("exactly") - tester.testOnly(
          Seq("mill.scalalib.FooTests"),
          1,
          Map(
            testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
            // When there is only one test group with test classes, we do not put it in a subfolder
            testrunnerGrouping.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs")
          )
        )
        test("multi") - tester.testOnly(
          Seq("*Bar*", "*bar*"),
          2,
          Map(
            testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
            // When there are multiple test groups with one test class each, we
            // put each test group in a subfolder with the number of the class
            testrunnerGrouping.utest -> Set(
              "mill.scalalib.BarTests",
              "mill.scalalib.FoobarTests",
              "test-report.xml"
            )
          )
        )
        test("all") - tester.testOnly(
          Seq("*"),
          3,
          Map(
            testrunner.utest -> Set("out.json", "sandbox", "test-report.xml", "testargs"),
            // When there are multiple test groups some with multiple test classes, we put each
            // test group in a subfolder with the index of the group, and for any test groups
            // with only one test class we append the name of the class
            testrunnerGrouping.utest -> Set(
              "group-0",
              "group-1-mill.scalalib.FoobarTests",
              "test-report.xml"
            )
          )
        )
        test("noMatch") - tester.testOnly0 { (eval, mod) =>
          val Left(Result.Failure(msg, _)) =
            eval.apply(mod.utest.testOnly("noMatch", "noMatch*2"))
          assert(
            msg == "Test selector does not match any test: noMatch noMatch*2\nRun discoveredTestClasses to see available tests"
          )
        }
      }
    }

    test("doneMessage") {
      test("failure") {
        val outStream = new ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = new PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Left(Result.Failure(msg, _)) = eval(testrunner.doneMessageFailure.test())
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test failure done message"))
          junitReportIn(eval.outPath, "doneMessageFailure").shouldHave(1 -> Status.Failure)
        }
      }
      test("success") {
        val outStream = new ByteArrayOutputStream()
        UnitTester(
          testrunner,
          outStream = new PrintStream(outStream, true),
          sourceRoot = resourcePath
        ).scoped { eval =>
          val Right(_) = eval(testrunner.doneMessageSuccess.test())
          val stdout = new String(outStream.toByteArray)
          assert(stdout.contains("test success done message"))
        }
      }

      test("null") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(_) = eval(testrunner.doneMessageNull.test())
      }
    }
  }

}

object ScalatestTestRunnerTests {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("ScalaTest") {
      test("test") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval(testrunner.scalatest.test())
        assert(result.value._2.size == 3)
        junitReportIn(eval.outPath, "scalatest").shouldHave(3 -> Status.Success)
      }
      test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(testrunner.scalatest.discoveredTestClasses)
        val expected = Seq("mill.scalalib.ScalaTestSpec")
        assert(result.value == expected)
        expected
      }

      test("testOnly") - {
        val tester = new TestOnlyTester(_.scalatest)

        test("all") - tester.testOnly(Seq("mill.scalalib.ScalaTestSpec"), 3)
        test("include") - tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec", "--", "-n", "tagged"),
          1
        )
        test("exclude") - tester.testOnly(
          Seq("mill.scalalib.ScalaTestSpec", "--", "-l", "tagged"),
          2
        )
        test("includeAndExclude") - tester.testOnly0 { (eval, mod) =>
          val Left(Result.Failure(msg, _)) =
            eval.apply(mod.scalatest.testOnly(
              "mill.scalalib.ScalaTestSpec",
              "--",
              "-n",
              "tagged",
              "-l",
              "tagged"
            ))
          assert(msg.contains("Test selector does not match any test"))
        }
      }
    }

  }

}

object ZiotestTestRunnerTests {
  import TestRunnerTestUtils._
  override def tests: Tests = Tests {
    test("ZioTest") {
      test("test") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval(testrunner.ziotest.test())
        assert(result.value._2.size == 1)
        junitReportIn(eval.outPath, "ziotest").shouldHave(1 -> Status.Success)
      }
      test("discoveredTestClasses") - UnitTester(testrunner, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(testrunner.ziotest.discoveredTestClasses)
        val expected = Seq("mill.scalalib.ZioTestSpec")
        assert(result.value == expected)
        expected
      }
    }
  }

}
