package mill.scalalib

import mill.*
import mill.define.Discover
import mill.testkit.{TestBaseModule, UnitTester}
import utest.*

object ScalaDotty213Tests extends TestSuite {
  object Dotty213 extends TestBaseModule {
    object foo extends ScalaModule {
      def scalaVersion = "0.18.1-RC1"
      override def ivyDeps =
        Agg(ivy"org.scala-lang.modules::scala-xml:1.2.0".withDottyCompat(scalaVersion()))
    }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("dotty213") - UnitTester(
      Dotty213,
      sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "dotty213"
    ).scoped { eval =>
      val Right(result) = eval.apply(Dotty213.foo.run())
      assert(result.evalCount > 0)
    }

  }
}
