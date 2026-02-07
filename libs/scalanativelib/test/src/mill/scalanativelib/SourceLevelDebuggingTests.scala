package mill.scalanativelib

import mill.given
import mill.api.Discover
import mill.scalanativelib.api.SourceLevelDebuggingConfig
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

object SourceLevelDebuggingTests extends TestSuite {

  object SourceLevelDebuggingDisabled extends TestRootModule with ScalaNativeModule {
    def scalaNativeVersion = "0.5.10"
    def scalaVersion = "3.3.7"
    def nativeSourceLevelDebuggingConfig = SourceLevelDebuggingConfig.Disabled
    override lazy val millDiscover = Discover[this.type]
  }

  object SourceLevelDebuggingEnabled extends TestRootModule with ScalaNativeModule {
    def scalaNativeVersion = "0.5.10"
    def scalaVersion = "3.3.7"
    def nativeSourceLevelDebuggingConfig = SourceLevelDebuggingConfig.Enabled()
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "features"

  val tests: Tests = Tests {
    test("nativeLink works with source-level debugging disabled") -
      UnitTester(SourceLevelDebuggingDisabled, millSourcePath).scoped { eval =>
        val Right(_) = eval(SourceLevelDebuggingDisabled.nativeLink).runtimeChecked
      }

    test("nativeLink works with source-level debugging enabled") -
      UnitTester(SourceLevelDebuggingEnabled, millSourcePath).scoped { eval =>
        val Right(_) = eval(SourceLevelDebuggingEnabled.nativeLink).runtimeChecked
      }
  }
}
