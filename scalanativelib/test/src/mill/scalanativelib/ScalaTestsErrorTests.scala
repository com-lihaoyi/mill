package mill.scalanativelib

import mill._
import mill.define.Discover
import mill.scalalib.TestModule
import mill.testkit.TestBaseModule
import utest._

object ScalaTestsErrorTests extends TestSuite {
  object ScalaTestsError extends TestBaseModule {
    object scalaTestsError extends ScalaNativeModule {
      def scalaVersion = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)
      def scalaNativeVersion = sys.props.getOrElse("TEST_SCALANATIVE_0_4_VERSION", ???)
      object test extends ScalaTests with TestModule.Utest
      object testDisabledError extends ScalaTests with TestModule.Utest {
        override def hierarchyChecks(): Unit = {}
      }
    }

    override lazy val millDiscover = {
      import mill.util.TokenReaders.given
      Discover[this.type]
    }
  }

  def tests: Tests = Tests {
    test("extends-ScalaTests") {
      val error = intercept[ExceptionInInitializerError] {
        ScalaTestsError.scalaTestsError.test
      }
      val message = error.getCause.getMessage
      assert(
        message == s"scalaTestsError is a `mill.scalanativelib.ScalaNativeModule`. scalaTestsError.test needs to extend `ScalaNativeTests`."
      )
    }
    test("extends-ScalaTests-disabled-hierarchy-check") {
      // expect no throws exception
      ScalaTestsError.scalaTestsError.testDisabledError
    }
  }
}
