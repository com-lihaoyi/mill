package mill.contrib.junit
import mill.contrib.junit.{TestSuite => TS}
import mill.define.Segment.{Cross, Label}
import mill.define.Segments
import mill.testrunner.TestResult
import utest._

import java.time.Instant

object TestDataTests extends TestSuite {
  override def tests: Tests = Tests {
    test("TestData") {
      test("asTestSuites empty") {
        val sut = TestData(os.Path("/test/data"), Seq.empty, Segments(Seq.empty))
        assert(TestData.asTestSuites(sut, Instant.now).isEmpty)
      }
      test("asTestSuites") {
        val sut = TestData(
          os.Path("/test/data"),
          Seq(
            TestResult("mill.contrib.junit.TestDataTests", "asTestSuites success", 1L, "Success"),
            TestResult(
              "mill.contrib.junit.TestDataTests",
              "asTestSuites failure",
              10L,
              "Failure",
              exceptionName = Some("utest.AssertionError"),
              exceptionMsg = Some("assert(Seq(expected) == ...)"),
              exceptionTrace = None
            ),
            TestResult("mill.contrib.junit.ModelTests", "TestCase asXML success", 101L, "Success"),
            TestResult("mill.contrib.junit.TestDataTests", "asTestSuites error", 100L, "Error"),
            TestResult("mill.contrib.junit.TestDataTests", "asTestSuites skipped", 1000L, "Ignored")
          ),
          Segments(Seq.empty)
        )
        val expected = Seq(
          TS(
            "localhost",
            "mill.contrib.junit.ModelTests",
            1,
            0,
            0,
            0,
            "0.101",
            "1970-01-01T01:00:00",
            Seq(
              TestCase("mill.contrib.junit.ModelTests", "TestCase asXML success", "0.101", None)
            )
          ),
          TS(
            "localhost",
            "mill.contrib.junit.TestDataTests",
            4,
            1,
            1,
            1,
            "1.111",
            "1970-01-01T01:00:00",
            Seq(
              TestCase("mill.contrib.junit.TestDataTests", "asTestSuites success", "0.001", None),
              TestCase(
                "mill.contrib.junit.TestDataTests",
                "asTestSuites failure",
                "0.01",
                Some(FailureDetail)
              ),
              TestCase(
                "mill.contrib.junit.TestDataTests",
                "asTestSuites error",
                "0.1",
                Some(ErrorDetail)
              ),
              TestCase(
                "mill.contrib.junit.TestDataTests",
                "asTestSuites skipped",
                "1.0",
                Some(SkippedDetail)
              )
            )
          )
        )
        val actual = TestData.asTestSuites(sut, Instant.ofEpochMilli(0)).toSeq.sortBy(_.name)
        assert(expected == actual)
      }
      test("asTestSuites cross build") {
        val sut = TestData(
          os.Path("/test/data"),
          Seq(TestResult(
            "mill.contrib.junit.TestDataTests",
            "asTestSuites success",
            1L,
            "Success"
          )),
          Segments(Seq(
            Label("mill"),
            Label("contrib"),
            Label("junit"),
            Cross(Seq("2.13.13")),
            Label("test"),
            Label("testCached")
          ))
        )
        val expected = Seq(TS(
          "localhost",
          "mill.contrib.junit.TestDataTests-2.13.13",
          1,
          0,
          0,
          0,
          "0.001",
          "1970-01-01T01:00:00",
          Seq(TestCase("mill.contrib.junit.TestDataTests", "asTestSuites success", "0.001", None))
        ))
        val actual = TestData.asTestSuites(sut, Instant.ofEpochMilli(0)).toSeq.sortBy(_.name)
        assert(expected == actual)
      }
    }
  }
}
