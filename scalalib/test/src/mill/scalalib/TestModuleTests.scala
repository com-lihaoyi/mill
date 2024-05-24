package mill.scalalib

import mill.testrunner.TestResult
import utest._

import java.time.Instant
import scala.xml._

object TestModuleTests extends TestSuite {

  override def tests: Tests = Tests {
    val timestamp = "2024-05-23T12:20:04"
    val instant = Instant.parse(timestamp + "Z")

    val succeededTestResult: TestResult = TestResult(
      "mill.scalalib.TestModuleTests",
      "mill.scalalib.TestModuleTests.success",
      1,
      "Success",
      None,
      None,
      None
    )

    val succeededTestCaseElement =
      <testcase classname="mill.scalalib.TestModuleTests" name="success" time="0.001">
      </testcase>
    test("success") - {
      val expectedReport = <testsuites tests="1" failures="0" errors="0" skipped="0" time="0.001">
        <testsuite name="mill.scalalib.TestModuleTests" tests="1" failures="0" errors="0" skipped="0" time="0.001" timestamp={
        timestamp
      }>
        <properties>
        </properties>
        {succeededTestCaseElement}
      </testsuite>
        </testsuites>
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(Seq(succeededTestResult), instant, Map.empty)
      )
    }
    val failedTestResult: TestResult = TestResult(
      "mill.scalalib.TestModuleTests",
      "mill.scalalib.TestModuleTests.fail",
      12,
      "Failure",
      Some("utest.AssertionError"),
      Some("assert(1 == 2)"),
      Some(Seq(
        new StackTraceElement(
          "utest.asserts.Util$",
          "$anonfun$makeAssertError$1",
          "Util.scala",
          26
        ),
        new StackTraceElement(
          "utest.framework.StackMarker$",
          "dropInside",
          "StackMarker.scala",
          11
        )
      ))
    )
    val failedTestCaseElement =
      <testcase classname="mill.scalalib.TestModuleTests" name="fail" time="0.012">
        <failure message="assert(1 == 2)" type="utest.AssertionError"> utest.AssertionError: assert(1 == 2) at utest.asserts.Util$.$anonfun$makeAssertError$1(Util.scala:26) at utest.framework.StackMarker$.dropInside(StackMarker.scala:11) </failure>
      </testcase>
    test("fail") - {
      val expectedReport =
        <testsuites tests="1" failures="1" errors="0" skipped="0" time="0.012">
          <testsuite name="mill.scalalib.TestModuleTests" tests="1" failures="1" errors="0" skipped="0" time="0.012" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            {failedTestCaseElement}
          </testsuite>
        </testsuites>
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(Seq(failedTestResult), instant, Map.empty)
      )
    }
    test("fail - empty message") - {
      val expectedReport =
        <testsuites tests="1" failures="1" errors="0" skipped="0" time="0.012">
          <testsuite name="mill.scalalib.TestModuleTests" tests="1" failures="1" errors="0" skipped="0" time="0.012" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            <testcase classname="mill.scalalib.TestModuleTests" name="fail" time="0.012">
              <failure message="No Exception or message provided"/>
            </testcase>
          </testsuite>
        </testsuites>
      val testResult: TestResult = TestResult(
        "mill.scalalib.TestModuleTests",
        "mill.scalalib.TestModuleTests.fail",
        12,
        "Failure",
        None,
        None,
        None
      )
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(Seq(testResult), instant, Map.empty)
      )
    }
    val errorTestResult: TestResult = TestResult(
      "mill.scalalib.TestModuleTests",
      "mill.scalalib.TestModuleTests.error",
      2400,
      "Error",
      Some("java.lang.RuntimeException"),
      Some("throw new RuntimeException()"),
      Some(Seq(
        new StackTraceElement(
          "utest.asserts.Util$",
          "$anonfun$makeAssertError$1",
          "Util.scala",
          26
        ),
        new StackTraceElement(
          "utest.framework.StackMarker$",
          "dropInside",
          "StackMarker.scala",
          11
        )
      ))
    )
    val errorTestCaseElement =
      <testcase classname="mill.scalalib.TestModuleTests" name="error" time="2.4">
        <error message="throw new RuntimeException()" type="java.lang.RuntimeException"> java.lang.RuntimeException: throw new RuntimeException() at utest.asserts.Util$.$anonfun$makeAssertError$1(Util.scala:26) at utest.framework.StackMarker$.dropInside(StackMarker.scala:11) </error>
      </testcase>
    test("error") - {
      val expectedReport =
        <testsuites tests="1" failures="0" errors="1" skipped="0" time="2.4">
          <testsuite name="mill.scalalib.TestModuleTests" tests="1" failures="0" errors="1" skipped="0" time="2.4" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            {errorTestCaseElement}
          </testsuite>
        </testsuites>
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(Seq(errorTestResult), instant, Map.empty)
      )
    }
    test("error - empty message") - {
      val expectedReport =
        <testsuites tests="1" failures="0" errors="1" skipped="0" time="2.4">
          <testsuite name="mill.scalalib.TestModuleTests" tests="1" failures="0" errors="1" skipped="0" time="2.4" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            <testcase classname="mill.scalalib.TestModuleTests" name="error" time="2.4">
              <error message="No Exception or message provided"/>
            </testcase>
          </testsuite>
        </testsuites>
      val testResult: TestResult = TestResult(
        "mill.scalalib.TestModuleTests",
        "mill.scalalib.TestModuleTests.error",
        2400,
        "Error",
        None,
        None,
        None
      )
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(Seq(testResult), instant, Map.empty)
      )
    }
    test("skipped") - {
      val expectedReport =
        <testsuites tests="3" failures="0" errors="0" skipped="3" time="0.3">
          <testsuite name="mill.scalalib.TestModuleTests" tests="3" failures="0" errors="0" skipped="3" time="0.3" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            <testcase classname="mill.scalalib.TestModuleTests" name="skipped" time="0.1">
              <skipped/>
            </testcase>
            <testcase classname="mill.scalalib.TestModuleTests" name="ignored" time="0.1">
              <skipped/>
            </testcase>
            <testcase classname="mill.scalalib.TestModuleTests" name="pending" time="0.1">
              <skipped/>
            </testcase>
          </testsuite>
        </testsuites>
      val skippedTestResult: TestResult = TestResult(
        "mill.scalalib.TestModuleTests",
        "mill.scalalib.TestModuleTests.skipped",
        100,
        "Skipped",
        None,
        None,
        None
      )
      val ignoredTestResult: TestResult = TestResult(
        "mill.scalalib.TestModuleTests",
        "mill.scalalib.TestModuleTests.ignored",
        100,
        "Ignored",
        None,
        None,
        None
      )
      val pendingTestResult: TestResult = TestResult(
        "mill.scalalib.TestModuleTests",
        "mill.scalalib.TestModuleTests.pending",
        100,
        "Pending",
        None,
        None,
        None
      )

      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(
          Seq(skippedTestResult, ignoredTestResult, pendingTestResult),
          instant,
          Map.empty
        )
      )
    }
    test("multi test cases") - {
      val expectedReport = <testsuites tests="2" failures="1" errors="0" skipped="0" time="0.013">
        <testsuite name="mill.scalalib.TestModuleTests" tests="2" failures="1" errors="0" skipped="0" time="0.013" timestamp={
        timestamp
      }>
          <properties>
          </properties>
          {succeededTestCaseElement}
          {failedTestCaseElement}
          </testsuite>
        </testsuites>
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(Seq(succeededTestResult, failedTestResult), instant, Map.empty)
      )
    }
    test("multi test suites") - {
      val expectedReport =
        <testsuites tests="3" failures="1" errors="0" skipped="0" time="0.014">
          <testsuite name="mill.scalalib.TestModuleTests" tests="2" failures="1" errors="0" skipped="0" time="0.013" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            {succeededTestCaseElement}
            {failedTestCaseElement}
            </testsuite>
          <testsuite name="mill.scalalib.TestModuleTests2" tests="1" failures="0" errors="0" skipped="0" time="0.001" timestamp={
          timestamp
        }>
            <properties>
            </properties>
            <testcase classname="mill.scalalib.TestModuleTests2" name="success" time="0.001">
            </testcase>
          </testsuite>
        </testsuites>
      val testSuite2TestResult: TestResult = TestResult(
        "mill.scalalib.TestModuleTests2",
        "mill.scalalib.TestModuleTests2.success",
        1,
        "Success",
        None,
        None,
        None
      )
      assertEquals(
        expectedReport,
        TestModule.genTestXmlReport(
          Seq(succeededTestResult, testSuite2TestResult, failedTestResult),
          instant,
          Map.empty
        )
      )
    }
  }

  private def assertEquals(expected: Elem, actual: Option[Elem]): Unit = {
    val actElem = actual.get
    val act = asString(actElem)
    val exp = asString(expected)
    // extracted variables to get a usable display
    assert(exp == act)
  }

  private def asString(elem: Elem) = new PrettyPrinter(Int.MaxValue, 0, true).format(elem)
}
