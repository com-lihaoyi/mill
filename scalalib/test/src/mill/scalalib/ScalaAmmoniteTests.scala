package mill.scalalib

import mill.*
import mill.define.Discover
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

object ScalaAmmoniteTests extends TestSuite {

  object AmmoniteReplMainClass extends TestBaseModule {
    object oldAmmonite extends ScalaModule {
      override def scalaVersion = T("2.13.5")
      override def ammoniteVersion = T("2.4.1")
    }
    object newAmmonite extends ScalaModule {
      override def scalaVersion = T("2.13.5")
      override def ammoniteVersion = T("2.5.0")
    }
    lazy val millDiscover: Discover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("replAmmoniteMainClass") - UnitTester(AmmoniteReplMainClass, null).scoped { eval =>
      val Right(result) = eval.apply(AmmoniteReplMainClass.oldAmmonite.ammoniteMainClass)
      assert(result.value == "ammonite.Main")
      val Right(result2) = eval.apply(AmmoniteReplMainClass.newAmmonite.ammoniteMainClass)
      assert(result2.value == "ammonite.AmmoniteMain")
    }

  }
}
