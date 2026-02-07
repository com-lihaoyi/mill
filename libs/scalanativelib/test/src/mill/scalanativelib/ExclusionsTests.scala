package mill.scalanativelib

import mill.given
import mill.scalalib.*
import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

object ExclusionsTests extends TestSuite {
  object Exclusions extends TestRootModule {
    object scala213 extends ScalaNativeModule {
      def scalaNativeVersion = "0.5.9"
      def scalaVersion = "2.13.18"
      override def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"com.github.scopt:scopt_native0.5_3:4.1.0"
      )
    }
    object scala3 extends ScalaNativeModule {
      def scalaNativeVersion = "0.5.9"
      def scalaVersion = "3.3.7"
      override def mvnDeps = super.mvnDeps() ++ Seq(
        mvn"com.github.scopt:scopt_native0.5_2.13:4.1.0"
      )
    }
    override lazy val millDiscover = Discover[this.type]
  }

  val exclusionsEvaluator = UnitTester(Exclusions, null)

  val tests: Tests = Tests {
    test("scala3 scala native libraries are excluded in Scala 2.13") {
      val Right(result) = exclusionsEvaluator(Exclusions.scala213.resolvedMvnDeps).runtimeChecked
      val jars = result.value.iterator.map(_.path.last).toSet
      assert(jars.contains("nativelib_native0.5_2.13-0.5.9.jar"))
      assert(!jars.contains("nativelib_native0.5_3-0.5.9.jar"))
      assert(jars.contains("clib_native0.5_2.13-0.5.9.jar"))
      assert(!jars.contains("clib_native0.5_3-0.5.9.jar"))
    }
    test("scala2.13 scala native libraries are excluded in Scala 3") {
      val Right(result) = exclusionsEvaluator(Exclusions.scala3.resolvedMvnDeps).runtimeChecked
      val jars = result.value.iterator.map(_.path.last).toSet
      assert(jars.contains("nativelib_native0.5_3-0.5.9.jar"))
      assert(!jars.contains("nativelib_native0.5_2.13-0.5.9.jar"))
      assert(jars.contains("clib_native0.5_3-0.5.9.jar"))
      assert(!jars.contains("clib_native0.5_2.13-0.5.9.jar"))
    }
  }
}
