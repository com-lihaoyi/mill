package mill.scalalib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

import java.io.{ByteArrayOutputStream, PrintStream}

object ScalaNightlyModuleTests extends TestSuite {

  object LatestNightly extends TestRootModule {
    object foo extends ScalaNightlyModule {
      override def mvnDeps = Seq(
        mvn"org.scala-lang.modules::scala-xml:2.4.0"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  object PrefixedNightly extends TestRootModule {
    object foo extends ScalaNightlyModule {
      override def scalaNightlyVersionPrefix = "3.8"
    }

    lazy val millDiscover = Discover[this.type]
  }

  object NoMatch extends TestRootModule {
    object foo extends ScalaNightlyModule {
      override def scalaNightlyVersionPrefix = "2.13"
    }

    lazy val millDiscover = Discover[this.type]
  }

  object PinnedNightly extends TestRootModule {
    object foo extends ScalaNightlyModule {
      override def scalaVersion = "3.8.0-RC1-bin-20250825-ee2f641-NIGHTLY"
      override def mvnDeps = Seq(
        mvn"org.scala-lang.modules::scala-xml:2.4.0"
      )
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "dotty213"

  def isNightly(version: String): Boolean =
    ScalaNightlyModule.NightlyVersion.matches(version)

  def tests: Tests = Tests {

    test("latestNightlyVersion") {
      val versions = Seq(
        "3.7.4",
        "3.8.0-RC1",
        "3.8.0-RC1-bin-20250825-ee2f641-NIGHTLY",
        "3.8.3-RC1-bin-20260128-3882481-NIGHTLY",
        "3.8.3-RC1-bin-20260129-40075f7-NIGHTLY",
        "3.9.0-RC1-bin-20260101-aaaaaaa-NIGHTLY",
        "3.10.0-RC1-bin-20251231-bbbbbbb-NIGHTLY",
        "3.10.1",
        "3.11.0-RC1-bin-20260101-ccccccc-SNAPSHOT"
      )

      test("noPrefix") {
        val res = ScalaNightlyModule.latestNightlyVersion(versions, "")
        assert(res == Some("3.10.0-RC1-bin-20251231-bbbbbbb-NIGHTLY"))
      }
      test("minorPrefix") {
        val res = ScalaNightlyModule.latestNightlyVersion(versions, "3.8")
        assert(res == Some("3.8.3-RC1-bin-20260129-40075f7-NIGHTLY"))
      }
      test("prefixDoesNotMatchPartialSegment") {
        val res = ScalaNightlyModule.latestNightlyVersion(versions, "3.1")
        assert(res == None)
      }
      test("patchPrefix") {
        val res = ScalaNightlyModule.latestNightlyVersion(versions, "3.8.0")
        assert(res == Some("3.8.0-RC1-bin-20250825-ee2f641-NIGHTLY"))
      }
      test("fullVersionPrefix") {
        val res = ScalaNightlyModule.latestNightlyVersion(versions, "3.8.3-RC1-bin-20260128")
        assert(res == Some("3.8.3-RC1-bin-20260128-3882481-NIGHTLY"))
      }
      test("noNightlies") {
        val res = ScalaNightlyModule.latestNightlyVersion(Seq("3.7.4", "3.8.0-RC1"), "")
        assert(res == None)
      }
    }

    test("repositories") - UnitTester(LatestNightly, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(LatestNightly.foo.repositories).runtimeChecked
      assert(result.value.contains(CoursierModule.KnownRepositories.ScalaLangNightlies))
    }

    test("latestNightly") {
      val err = new ByteArrayOutputStream()
      UnitTester(LatestNightly, resourcePath, errStream = new PrintStream(err, true)).scoped {
        eval =>
          val Right(result) = eval.apply(LatestNightly.foo.scalaVersion).runtimeChecked
          val version = result.value
          assert(isNightly(version))

          val log = err.toString
          assert(log.contains(version))
          assert(log.contains("nightly"))

          val Right(runResult) = eval.apply(LatestNightly.foo.run()).runtimeChecked
          assert(runResult.evalCount > 0)
      }
    }

    test("prefixedNightly") - UnitTester(PrefixedNightly, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(PrefixedNightly.foo.scalaVersion).runtimeChecked
      val version = result.value
      assert(isNightly(version))
      assert(version.startsWith("3.8."))
    }

    test("offline") {
      val onlineVersion = UnitTester(PrefixedNightly, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(PrefixedNightly.foo.scalaVersion).runtimeChecked
        result.value
      }
      UnitTester(PrefixedNightly, resourcePath, offline = true).scoped { eval =>
        val Right(result) = eval.apply(PrefixedNightly.foo.scalaVersion).runtimeChecked
        assert(result.value == onlineVersion)
      }
    }

    test("noMatchingNightly") - UnitTester(NoMatch, resourcePath).scoped { eval =>
      val Left(failure) = eval.apply(NoMatch.foo.scalaVersion).runtimeChecked
      assert(failure.toString.contains("2.13"))
    }

    test("pinnedNightly") - UnitTester(PinnedNightly, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(PinnedNightly.foo.run()).runtimeChecked
      assert(result.evalCount > 0)
    }
  }
}
