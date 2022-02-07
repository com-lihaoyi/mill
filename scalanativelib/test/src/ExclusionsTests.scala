package mill.scalanativelib

import mill.Agg
import mill.scalalib._
import mill.define.Discover
import mill.scalanativelib.api._
import mill.util.{TestEvaluator, TestUtil}
import utest._

object ExclusionsTests extends TestSuite {
  object Exclusions extends TestUtil.BaseModule {
    object scala213 extends ScalaNativeModule {
      def scalaNativeVersion = "0.4.3"
      def scalaVersion = "2.13.8"
      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"com.github.scopt:scopt_native0.4_3:4.0.1"
      )
    }
    object scala3 extends ScalaNativeModule {
      def scalaNativeVersion = "0.4.3"
      def scalaVersion = "3.1.1"
      override def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"com.github.scopt:scopt_native0.4_2.13:4.0.1"
      )
    }
    override lazy val millDiscover: Discover[Exclusions.this.type] = Discover[this.type]
  }

  val exclusionsEvaluator = TestEvaluator.static(Exclusions)

  val tests: Tests = Tests {
    test("scala3 scala native libraries are excluded in Scala 2.13") {
      val Right((result, evalCount)) = exclusionsEvaluator(Exclusions.scala213.resolvedIvyDeps)
      val jars = result.map(_.path.last).toSet
      assert(jars.contains("nativelib_native0.4_2.13-0.4.3.jar"))
      assert(!jars.contains("nativelib_native0.4_3-0.4.3.jar"))
      assert(jars.contains("clib_native0.4_2.13-0.4.3.jar"))
      assert(!jars.contains("clib_native0.4_3-0.4.3.jar"))
    }
    test("scala2.13 scala native libraries are excluded in Scala 3") {
      val Right((result, evalCount)) = exclusionsEvaluator(Exclusions.scala3.resolvedIvyDeps)
      val jars = result.map(_.path.last).toSet
      assert(jars.contains("nativelib_native0.4_3-0.4.3.jar"))
      assert(!jars.contains("nativelib_native0.4_2.13-0.4.3.jar"))
      assert(jars.contains("clib_native0.4_3-0.4.3.jar"))
      assert(!jars.contains("clib_native0.4_2.13-0.4.3.jar"))
    }
  }
}
