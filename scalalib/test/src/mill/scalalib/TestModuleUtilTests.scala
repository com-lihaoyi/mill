package mill.scalalib

import mill.testrunner.TestResult
import utest._

import java.time.Instant
import scala.xml._

object TestModuleUtilTests extends TestSuite {

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
        TestModuleUtil.genTestXmlReport(Seq(succeededTestResult), instant, Map.empty)
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
        TestModuleUtil.genTestXmlReport(Seq(failedTestResult), instant, Map.empty)
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
        TestModuleUtil.genTestXmlReport(Seq(testResult), instant, Map.empty)
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
        TestModuleUtil.genTestXmlReport(Seq(errorTestResult), instant, Map.empty)
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
        TestModuleUtil.genTestXmlReport(Seq(testResult), instant, Map.empty)
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
        TestModuleUtil.genTestXmlReport(
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
        TestModuleUtil.genTestXmlReport(
          Seq(succeededTestResult, failedTestResult),
          instant,
          Map.empty
        )
      )
    }
    // format: off
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
        TestModuleUtil.genTestXmlReport(
          Seq(succeededTestResult, testSuite2TestResult, failedTestResult),
          instant,
          Map.empty
        )
      )
    }

    // format: on
    test("collapseTestClassNames") {
      val res = TestModuleUtil.collapseTestClassNames(
        Seq(
          "mill.javalib.palantirformat.PalantirFormatModuleTest",
          "mill.scalalib.AssemblyExeTests",
          "mill.scalalib.AssemblyNoExeTests",
          "mill.scalalib.CoursierMirrorTests",
          "mill.scalalib.CrossVersionTests",
          "mill.scalalib.CycleTests",
          "mill.scalalib.DottyDocTests",
          "mill.scalalib.HelloJavaTests",
          "mill.scalalib.HelloWorldTests",
          "mill.scalalib.PublishModuleTests",
          "mill.scalalib.ResolveDepsTests",
          "mill.scalalib.ScalaAmmoniteTests",
          "mill.scalalib.ScalaAssemblyAppendTests",
          "mill.scalalib.ScalaAssemblyExcludeTests",
          "mill.scalalib.ScalaAssemblyTests",
          "mill.scalalib.ScalaColorOutputTests",
          "mill.scalalib.ScalaCrossVersionTests",
          "mill.scalalib.ScalaDoc3Tests",
          "mill.scalalib.ScalaDotty213Tests",
          "mill.scalalib.ScalaFlagsTests",
          "mill.scalalib.ScalaIvyDepsTests",
          "mill.scalalib.ScalaMacrosTests",
          "mill.scalalib.ScalaMultiModuleClasspathsTests",
          "mill.scalalib.ScalaRunTests",
          "mill.scalalib.ScalaScalacheckTests",
          "mill.scalalib.ScalaScaladocTests",
          "mill.scalalib.ScalaSemanticDbTests",
          "mill.scalalib.ScalaTypeLevelTests",
          "mill.scalalib.ScalaValidatedPathRefTests",
          "mill.scalalib.ScalaVersionsRangesTests",
          "mill.scalalib.ScalatestTestRunnerTests",
          "mill.scalalib.TestClassLoaderTests",
          "mill.scalalib.TestModuleUtilTests",
          "mill.scalalib.UtestTestRunnerTests",
          "mill.scalalib.VersionContolTests",
          "mill.scalalib.ZiotestTestRunnerTests",
          "mill.scalalib.api.JvmWorkerUtilTests",
          "mill.scalalib.bsp.BspModuleTests",
          "mill.scalalib.dependency.metadata.MetadataLoaderFactoryTests",
          "mill.scalalib.dependency.updates.UpdatesFinderTests",
          "mill.scalalib.dependency.versions.ScalaVersionTests",
          "mill.scalalib.dependency.versions.VersionTests",
          "mill.scalalib.giter8.Giter8Tests",
          "mill.scalalib.publish.IvyTests",
          "mill.scalalib.publish.LocalM2PublisherTests",
          "mill.scalalib.publish.PomTests",
          "mill.scalalib.scalafmt.ScalafmtTests"
        )
      )
      val expected = Seq(
        "mill.javalib.palantirformat.PalantirFormatModuleTest",
        "m.scalalib.AssemblyExeTests",
        "m.s.AssemblyNoExeTests",
        "m.s.CoursierMirrorTests",
        "m.s.CrossVersionTests",
        "m.s.CycleTests",
        "m.s.DottyDocTests",
        "m.s.HelloJavaTests",
        "m.s.HelloWorldTests",
        "m.s.PublishModuleTests",
        "m.s.ResolveDepsTests",
        "m.s.ScalaAmmoniteTests",
        "m.s.ScalaAssemblyAppendTests",
        "m.s.ScalaAssemblyExcludeTests",
        "m.s.ScalaAssemblyTests",
        "m.s.ScalaColorOutputTests",
        "m.s.ScalaCrossVersionTests",
        "m.s.ScalaDoc3Tests",
        "m.s.ScalaDotty213Tests",
        "m.s.ScalaFlagsTests",
        "m.s.ScalaIvyDepsTests",
        "m.s.ScalaMacrosTests",
        "m.s.ScalaMultiModuleClasspathsTests",
        "m.s.ScalaRunTests",
        "m.s.ScalaScalacheckTests",
        "m.s.ScalaScaladocTests",
        "m.s.ScalaSemanticDbTests",
        "m.s.ScalaTypeLevelTests",
        "m.s.ScalaValidatedPathRefTests",
        "m.s.ScalaVersionsRangesTests",
        "m.s.ScalatestTestRunnerTests",
        "m.s.TestClassLoaderTests",
        "m.s.TestModuleUtilTests",
        "m.s.UtestTestRunnerTests",
        "m.s.VersionContolTests",
        "m.s.ZiotestTestRunnerTests",
        "m.s.api.JvmWorkerUtilTests",
        "m.s.bsp.BspModuleTests",
        "m.s.dependency.metadata.MetadataLoaderFactoryTests",
        "m.s.d.updates.UpdatesFinderTests",
        "m.s.d.versions.ScalaVersionTests",
        "m.s.d.v.VersionTests",
        "m.s.giter8.Giter8Tests",
        "m.s.publish.IvyTests",
        "m.s.p.LocalM2PublisherTests",
        "m.s.p.PomTests",
        "m.s.scalafmt.ScalafmtTests"
      )
      assert(expected == res)
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
