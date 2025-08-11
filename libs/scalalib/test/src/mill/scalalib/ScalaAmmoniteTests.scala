package mill.scalalib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object ScalaAmmoniteTests extends TestSuite {

  object AmmoniteReplMainClass extends TestRootModule {
    object oldAmmonite extends ScalaModule {
      override def scalaVersion = Task { "2.13.5" }
      override def ammoniteVersion = Task { "2.4.1" }
    }
    object newAmmonite extends ScalaModule {
      override def scalaVersion = Task { "2.13.5" }
      override def ammoniteVersion = Task { "2.5.0" }
    }
    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("replAmmoniteMainClass") - UnitTester(AmmoniteReplMainClass, null).scoped { eval =>
      val Right(result) =
        eval.apply(AmmoniteReplMainClass.oldAmmonite.ammoniteMainClass): @unchecked
      assert(result.value == "ammonite.Main")
      val Right(result2) =
        eval.apply(AmmoniteReplMainClass.newAmmonite.ammoniteMainClass): @unchecked
      assert(result2.value == "ammonite.AmmoniteMain")
    }

  }
}
