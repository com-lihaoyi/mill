package mill.scalanativelib

import mill._
import mill.define.Discover
import mill.scalalib.TestModule
import mill.util.TestUtil
import utest._

object ScalaTestsErrorTests extends TestSuite {
  object ScalaTestsError extends TestUtil.BaseModule {
    object scalaTestsError extends ScalaNativeModule {
      def scalaVersion = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)
      def scalaNativeVersion = sys.props.getOrElse("TEST_SCALANATIVE_VERSION", ???)
      object test extends ScalaTests with TestModule.Utest
    }

    override lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {
    test("extends-ScalaTests") {
      val error = intercept[ExceptionInInitializerError] {
        ScalaTestsError.scalaTestsError.test
      }
      val message = error.getCause.getMessage
      assert(
        message == s"scalaTestsError is a `ScalaNativeModule`. scalaTestsError.test needs to extend `ScalaNativeTests`."
      )
    }
  }
}
