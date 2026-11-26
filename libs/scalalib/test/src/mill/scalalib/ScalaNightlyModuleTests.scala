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

  object FixedNightlies extends TestRootModule {
    var versions = Seq(
      "3.8.3-RC1-bin-20260128-3882481-NIGHTLY",
      "3.9.0-RC1-bin-20260101-aaaaaaa-NIGHTLY"
    )
    var prefix = ""

    object foo extends ScalaNightlyModule {
      override def scalaNightlyVersions = Task.Input { versions }
      override def scalaNightlyVersionPrefix = Task.Input { prefix }
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "dotty213"

  def nightlyWarnings(log: String): Int =
    log.linesIterator.count(_.contains("Using Scala nightly version"))

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
      test("laterReleaseCandidate") {
        val res = ScalaNightlyModule.latestNightlyVersion(
          Seq(
            "3.8.3-RC2-bin-20260201-1234567-NIGHTLY",
            "3.8.3-RC1-bin-20260129-40075f7-NIGHTLY",
            "3.8.2-RC9-bin-20260301-89abcde-NIGHTLY"
          ),
          ""
        )
        assert(res == Some("3.8.3-RC2-bin-20260201-1234567-NIGHTLY"))
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

    test("cachedSelection") {
      val err = new ByteArrayOutputStream()
      UnitTester(PrefixedNightly, resourcePath, errStream = new PrintStream(err, true)).scoped {
        eval =>
          val Right(first) = eval.apply(PrefixedNightly.foo.scalaVersion).runtimeChecked
          assert(nightlyWarnings(err.toString) == 1)

          val Right(second) = eval.apply(PrefixedNightly.foo.scalaVersion).runtimeChecked
          assert(second.value == first.value)
          assert(second.evalCount == 0)
          assert(nightlyWarnings(err.toString) == 1)
      }
    }

    test("prefixChange") {
      FixedNightlies.prefix = ""
      UnitTester(FixedNightlies, resourcePath).scoped { eval =>
        val Right(all) = eval.apply(FixedNightlies.foo.scalaVersion).runtimeChecked
        assert(all.value == "3.9.0-RC1-bin-20260101-aaaaaaa-NIGHTLY")

        FixedNightlies.prefix = "3.8"
        val Right(prefixed) = eval.apply(FixedNightlies.foo.scalaVersion).runtimeChecked
        assert(prefixed.value == "3.8.3-RC1-bin-20260128-3882481-NIGHTLY")

        FixedNightlies.prefix = "3.7"
        val Left(failure) = eval.apply(FixedNightlies.foo.scalaVersion).runtimeChecked
        assert(failure.toString.contains("'3.7'"))
      }
    }

    test("versionChangeMessage") {
      val v1 = "3.8.3-RC1-bin-20260128-3882481-NIGHTLY"
      val v2 = "3.8.3-RC1-bin-20260129-40075f7-NIGHTLY"
      val first = ScalaNightlyModule.versionChangeMessage(None, v1)
      assert(first.exists(_.startsWith(s"Using Scala nightly version $v1.")))
      val changed = ScalaNightlyModule.versionChangeMessage(Some(v1), v2)
      assert(changed.exists(_.startsWith(s"Scala nightly version changed from $v1 to $v2.")))
      assert(ScalaNightlyModule.versionChangeMessage(Some(v1), v1) == None)
    }

    test("reportVersionChange") {
      val v1 = "3.8.3-RC1-bin-20260128-3882481-NIGHTLY"
      val v2 = "3.8.3-RC1-bin-20260129-40075f7-NIGHTLY"
      FixedNightlies.versions = Seq(v1)
      FixedNightlies.prefix = "3.8"
      val err = new ByteArrayOutputStream()
      UnitTester(FixedNightlies, resourcePath, errStream = new PrintStream(err, true)).scoped {
        eval =>
          def nightlyLogs() = {
            val log = err.toString
            err.reset()
            log.linesIterator.filter(_.contains("Scala nightly version")).toSeq
          }

          val Right(first) = eval.apply(FixedNightlies.foo.scalaVersion).runtimeChecked
          assert(first.value == v1)
          val firstLogs = nightlyLogs()
          assert(firstLogs.size == 1 && firstLogs.head.contains(s"Using Scala nightly version $v1"))

          FixedNightlies.versions = Seq(v1, "3.9.0-RC1-bin-20260101-aaaaaaa-NIGHTLY")
          val Right(unrelated) = eval.apply(FixedNightlies.foo.scalaVersion).runtimeChecked
          assert(unrelated.value == v1)
          assert(nightlyLogs().isEmpty)

          FixedNightlies.versions = Seq(v1, v2)
          val Right(changed) = eval.apply(FixedNightlies.foo.scalaVersion).runtimeChecked
          assert(changed.value == v2)
          val changedLogs = nightlyLogs()
          assert(
            changedLogs.size == 1 &&
              changedLogs.head.contains(s"Scala nightly version changed from $v1 to $v2")
          )
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
