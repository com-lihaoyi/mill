package mill.scalanativelib

import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object FeaturesTests extends TestSuite {
  object Features extends TestBaseModule {
    object module extends ScalaNativeModule {
      def scalaNativeVersion = "0.4.9"
      def scalaVersion = "2.13.10"
      def nativeIncrementalCompilation = true
    }
    override lazy val millDiscover: Discover[Features.this.type] = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalanativelib" / "test" / "resources" / "features"

  val featuresEvaluator = UnitTester(Features, millSourcePath)

  val tests: Tests = Tests {
    test("incremental compilation works") {
      val Right(_) = featuresEvaluator(Features.module.nativeLink)
      val Right(result) = featuresEvaluator(Features.module.nativeWorkdir)
      assert(os.exists(result.value / "scala.ll"))
    }
  }
}
