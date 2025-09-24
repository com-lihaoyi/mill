package mill.scalanativelib

import mill.given
import mill.Agg
import mill.scalalib._
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object ExclusionsTests extends TestSuite {
  object Exclusions extends TestBaseModule {
    object scala213 extends ScalaNativeModule {
      def scalaNativeVersion = "0.4.3"
      def scalaVersion = "2.13.10"
      override def ivyDeps = super.ivyDeps() ++ Agg(
        mvn"com.github.scopt:scopt_native0.4_3:4.0.1"
      )
    }
    object scala3 extends ScalaNativeModule {
      def scalaNativeVersion = "0.4.3"
      def scalaVersion = "3.1.1"
      override def ivyDeps = super.ivyDeps() ++ Agg(
        mvn"com.github.scopt:scopt_native0.4_2.13:4.0.1"
      )
    }
    override lazy val millDiscover: Discover = Discover[this.type]
  }

  val exclusionsEvaluator = UnitTester(Exclusions, null)

  val tests: Tests = Tests {
    test("scala3 scala native libraries are excluded in Scala 2.13") {
      val Right(result) = exclusionsEvaluator(Exclusions.scala213.resolvedIvyDeps)
      val jars = result.value.iterator.map(_.path.last).toSet
      assert(jars.contains("nativelib_native0.4_2.13-0.4.3.jar"))
      assert(!jars.contains("nativelib_native0.4_3-0.4.3.jar"))
      assert(jars.contains("clib_native0.4_2.13-0.4.3.jar"))
      assert(!jars.contains("clib_native0.4_3-0.4.3.jar"))
    }
    test("scala2.13 scala native libraries are excluded in Scala 3") {
      val Right(result) = exclusionsEvaluator(Exclusions.scala3.resolvedIvyDeps)
      val jars = result.value.iterator.map(_.path.last).toSet
      assert(jars.contains("nativelib_native0.4_3-0.4.3.jar"))
      assert(!jars.contains("nativelib_native0.4_2.13-0.4.3.jar"))
      assert(jars.contains("clib_native0.4_3-0.4.3.jar"))
      assert(!jars.contains("clib_native0.4_2.13-0.4.3.jar"))
    }
  }
}
