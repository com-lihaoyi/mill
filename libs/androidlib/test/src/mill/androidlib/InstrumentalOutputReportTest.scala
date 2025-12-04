package mill.androidlib

import mill.api.Logger.DummyLogger
import mill.androidlib.InstrumentationOutput.*
import utest.*

import java.io.{BufferedReader, StringReader}

object InstrumentalOutputReportTest extends TestSuite {

  def tests: Tests = Tests {
    test("parseLine should parse class names correctly") {
      val line = "INSTRUMENTATION_STATUS: class=com.example.MyClass"
      val result = parseLine(line)
      assert(result == TestClassName("com.example.MyClass"))
    }

    test("parseLine should parse method names correctly") {
      val line = "INSTRUMENTATION_STATUS: test=myTestMethod"
      val result = parseLine(line)
      assert(result == TestMethodName("myTestMethod"))
    }

    test("parseLine should parse status codes correctly") {
      assert(parseLine("INSTRUMENTATION_STATUS_CODE: 1") == StatusStarted)
      assert(parseLine("INSTRUMENTATION_STATUS_CODE: 0") == StatusOk)
      assert(parseLine("INSTRUMENTATION_STATUS_CODE: -1") == StatusError)
      assert(parseLine("INSTRUMENTATION_STATUS_CODE: -2") == StatusFailure)
    }

    test("parseLine should handle unrecognized lines gracefully") {
      val line = "unrelated log line"
      val result = parseLine(line)
      assert(result == Ignored("unrelated log line"))
    }

    test("parse test stream into test result") {
      val testOutput =
        s"""
           INSTRUMENTATION_STATUS: class=com.helloworld.app.ExampleInstrumentedTest
           |INSTRUMENTATION_STATUS: current=1
           |INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
           |INSTRUMENTATION_STATUS: numtests=1
           |INSTRUMENTATION_STATUS: stream=
           |com.helloworld.app.ExampleInstrumentedTest:
           |INSTRUMENTATION_STATUS: test=useAppContext
           |INSTRUMENTATION_STATUS_CODE: 1
           |INSTRUMENTATION_STATUS: class=com.helloworld.app.ExampleInstrumentedTest
           |INSTRUMENTATION_STATUS: current=1
           |INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
           |INSTRUMENTATION_STATUS: numtests=1
           |INSTRUMENTATION_STATUS: stream=.
           |INSTRUMENTATION_STATUS: test=useAppContext
           |INSTRUMENTATION_STATUS_CODE: 0
           |INSTRUMENTATION_RESULT: stream=
           |
           |Time: 0.005
           |
           |OK (1 test)
           |
           |
           |INSTRUMENTATION_CODE: -1
        """.stripMargin

      val reader = BufferedReader(StringReader(testOutput))

      val (_, testResults) = parseTestOutputStream(reader)(DummyLogger)

      assert(testResults.size == 1)
      assert(
        testResults.head.fullyQualifiedName == "com.helloworld.app.ExampleInstrumentedTest.useAppContext"
      )
      assert(testResults.head.duration == 0L)
      assert(testResults.head.status == "Success")
      assert(
        testResults.head.selector == "com.helloworld.app.ExampleInstrumentedTest.useAppContext"
      )

    }
  }
}
