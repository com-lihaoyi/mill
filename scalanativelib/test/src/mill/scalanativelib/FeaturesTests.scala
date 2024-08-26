package mill.scalanativelib

import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._

object FeaturesTests extends TestSuite {
  object Features extends TestBaseModule with ScalaNativeModule {
    def scalaNativeVersion = "0.5.0"
    def scalaVersion = "2.13.10"
    def nativeIncrementalCompilation = true
    override lazy val millDiscover: Discover[Features.this.type] = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER")) / "features"

  val tests: Tests = Tests {
    test("incremental compilation works") {
      val eval = UnitTester(Features, millSourcePath)
      val Right(_) = eval(Features.nativeLink)
      val Right(result) = eval(Features.nativeWorkdir)
      assert(os.walk(result.value).exists(_.ext == "ll"))
    }
  }
}
