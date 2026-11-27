package mill.scalanativelib

import mill.given
import mill.api.{Discover, ExecResult}
import mill.scalanativelib.api.GarbageCollectorConfig
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object GarbageCollectorConfigTests extends TestSuite {
  trait Base extends TestRootModule with ScalaNativeModule {
    def scalaVersion = "3.3.7"
  }

  object LegacyDefault extends Base {
    def scalaNativeVersion = "0.5.12"
    override lazy val millDiscover = Discover[this.type]
  }

  object LegacyConfigured extends Base {
    def scalaNativeVersion = "0.5.12"
    override def nativeGarbageCollectorConfig = GarbageCollectorConfig.empty.withThreads(1)
    override lazy val millDiscover = Discover[this.type]
  }

  object Configured extends Base {
    def scalaNativeVersion = "0.5.13"
    override def nativeGC = "immix"
    override def nativeGarbageCollectorConfig = GarbageCollectorConfig.empty
      .withInitialHeapSizeBytes(16L * 1024 * 1024)
      .withMaxHeapSizeBytes(128L * 1024 * 1024)
      .withStatsEnabled(false)
    override lazy val millDiscover = Discover[this.type]
  }

  object Invalid extends Base {
    def scalaNativeVersion = "0.5.13"
    override def nativeGarbageCollectorConfig =
      GarbageCollectorConfig.empty.withInitialHeapSizeBytes(-1)
    override lazy val millDiscover = Discover[this.type]
  }

  val millSourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "features"

  val tests: Tests = Tests {
    test("options are immutable and round-trip") {
      val empty = GarbageCollectorConfig.empty
      val options = empty.withMaxHeapSizeBytes(128L * 1024 * 1024).withStatsEnabled(false)
      val restored = upickle.default.read[GarbageCollectorConfig](upickle.default.write(options))
      val defaults = upickle.default.read[GarbageCollectorConfig]("{}")
      val withFutureKey = upickle.default.read[GarbageCollectorConfig]("""{"futureOption": true}""")
      assert(
        empty.maxHeapSizeBytes.isEmpty,
        empty.statsEnabled.isEmpty,
        options.maxHeapSizeBytes.contains(128L * 1024 * 1024),
        options.statsEnabled.contains(false),
        restored == options,
        restored.hashCode() == options.hashCode(),
        defaults == empty,
        withFutureKey == empty,
        classOf[GarbageCollectorConfig].getConstructors.isEmpty,
        !classOf[GarbageCollectorConfig].getMethods.exists(_.getName.contains("data"))
      )
    }

    test("an older toolchain still links without tuning") -
      UnitTester(LegacyDefault, millSourcePath).scoped { eval =>
        val Right(_) = eval(LegacyDefault.nativeLink).runtimeChecked
      }

    test("configured tuning rejects an older toolchain") -
      UnitTester(LegacyConfigured, millSourcePath).scoped { eval =>
        val Left(ExecResult.Failure(msg = msg)) =
          eval(LegacyConfigured.validatedGarbageCollectorConfig).runtimeChecked
        assert(msg.contains("requires Scala Native 0.5.13 or newer (got 0.5.12)"))
      }

    test("Scala Native 0.5.13 links with configured tuning") -
      UnitTester(Configured, millSourcePath).scoped { eval =>
        val Right(_) = eval(Configured.nativeLink).runtimeChecked
      }

    test("invalid tuning names the task and the rejected setting") -
      UnitTester(Invalid, millSourcePath).scoped { eval =>
        val Left(ExecResult.Failure(msg = msg)) = eval(Invalid.nativeLink).runtimeChecked
        assert(msg.contains("Invalid nativeGarbageCollectorConfig"), msg.contains("negative"))
      }
  }
}
