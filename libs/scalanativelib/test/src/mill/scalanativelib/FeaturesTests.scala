package mill.scalanativelib

import mill.given
import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

object FeaturesTests extends TestSuite {
  object Features extends TestRootModule with ScalaNativeModule {
    def scalaNativeVersion = "0.5.9"
    def scalaVersion = "2.13.18"
    def nativeIncrementalCompilation = true
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "features"

  val tests: Tests = Tests {
    test("incremental compilation works") - UnitTester(Features, millSourcePath).scoped { eval =>
      val Right(_) = eval(Features.nativeLink).runtimeChecked
      val Right(result) = eval(Features.nativeWorkdir).runtimeChecked
      assert(os.walk(result.value).exists(_.ext == "ll"))
    }
  }
}
