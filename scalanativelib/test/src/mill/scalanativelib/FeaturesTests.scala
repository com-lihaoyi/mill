package mill.scalanativelib

import mill.define.Discover
import mill.testkit.TestEvaluator
import mill.testkit.MillTestKit
import utest._

object FeaturesTests extends TestSuite {
  val workspacePath = MillTestKit.getOutPathStatic() / "features"
  object Features extends mill.testkit.BaseModule {
    object module extends ScalaNativeModule {
      def millSourcePath = workspacePath
      def scalaNativeVersion = "0.4.9"
      def scalaVersion = "2.13.10"
      def nativeIncrementalCompilation = true
    }
    override lazy val millDiscover: Discover[Features.this.type] = Discover[this.type]
  }

  val millSourcePath = os.pwd / "scalanativelib" / "test" / "resources" / "features"

  val featuresEvaluator = TestEvaluator.static(Features)

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(millSourcePath, workspacePath)
  }

  val tests: Tests = Tests {
    prepareWorkspace()
    test("incremental compilation works") {
      val Right(_) = featuresEvaluator(Features.module.nativeLink)
      val Right(result) = featuresEvaluator(Features.module.nativeWorkdir)
      assert(os.exists(result.value / "scala.ll"))
    }
  }
}
