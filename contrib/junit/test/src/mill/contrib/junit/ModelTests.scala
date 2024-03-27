package mill.contrib.junit

import mill.contrib.junit.{TestSuite => TS}
import utest._

import scala.xml.Elem

object ModelTests extends TestSuite {
  private def checkElementName(expected: String, adaptable: XMLAdaptable) =
    expected == adaptable.asXML.nameToString(new StringBuilder()).toString()
  private def checkAtt(expected: String, adaptable: XMLAdaptable, att: String): Boolean =
    adaptable.asXML.attributes.get(att).exists(v => expected == v.toString())
  override def tests: Tests = Tests {
    test("ErrorDetail") {
      test("asXML") {
        assert(checkElementName("error", ErrorDetail))
      }
    }
    test("FailureDetail") {
      test("asXML") {
        assert(checkElementName("failure", FailureDetail))
      }
    }
    test("SkippedDetail") {
      test("asXML") {
        assert(checkElementName("skipped", SkippedDetail))
      }
    }
    test("TestCase") {
      test("asXML without details") {
        val sut = TestCase("mill.test", "test-case", "123", None)
        assert(
          checkElementName("testcase", sut),
          checkAtt("mill.test", sut, "classname"),
          checkAtt("test-case", sut, "name"),
          checkAtt("123", sut, "time"),
          !sut.asXML.child.exists(_.isInstanceOf[Elem])
        )
      }
      test("asXML with details") {
        val sut = TestCase("mill.test.Skip", "test-case-skip", "42", Some(SkippedDetail))
        assert(
          checkElementName("testcase", sut),
          checkAtt("mill.test.Skip", sut, "classname"),
          checkAtt("test-case-skip", sut, "name"),
          checkAtt("42", sut, "time"),
          sut.asXML.child.count(_.isInstanceOf[Elem]) == 1
        )
      }
    }
    test("TestSuite") {
      test("asXML without testcases") {
        val sut = TS(
          "localhost",
          "test-suite",
          42,
          2,
          4,
          24,
          "0.42s",
          "15:00",
          Seq.empty
        )

        assert(
          checkElementName("testsuite", sut),
          checkAtt("localhost", sut, "hostname"),
          checkAtt("test-suite", sut, "name"),
          checkAtt("42", sut, "tests"),
          checkAtt("2", sut, "errors"),
          checkAtt("4", sut, "failures"),
          checkAtt("24", sut, "skipped"),
          checkAtt("0.42s", sut, "time"),
          checkAtt("15:00", sut, "timestamp"),
          sut.asXML.child.count(_.isInstanceOf[Elem]) == 3
        )
      }
      test("asXML with testcases") {
        val sut = TS(
          "localhost2",
          "test-suite-2",
          42,
          2,
          4,
          24,
          "0.42s",
          "15:00",
          Seq(
            TestCase("mill.test.Skip", "test-case-skip", "42", Some(SkippedDetail)),
            TestCase("mill.test.Error", "test-case-error", "24", Some(ErrorDetail))
          )
        )

        assert(
          checkElementName("testsuite", sut),
          checkAtt("localhost2", sut, "hostname"),
          checkAtt("test-suite-2", sut, "name"),
          checkAtt("42", sut, "tests"),
          checkAtt("2", sut, "errors"),
          checkAtt("4", sut, "failures"),
          checkAtt("24", sut, "skipped"),
          checkAtt("0.42s", sut, "time"),
          checkAtt("15:00", sut, "timestamp"),
          sut.asXML.child.count(_.isInstanceOf[Elem]) == 5
        )
      }
    }
  }
}
