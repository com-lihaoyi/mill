package mill.contrib.versionfile

import utest.{TestSuite, Tests, assert, assertAll, assertMatch, assertThrows, test}

object VersionTests extends TestSuite {
  def tests = Tests {

    test("toString") {
      val snapshot = Version.Snapshot(1, 2, 3)
      val release = Version.Release(1, 2, 3)

      assertAll(
        snapshot.toString == "1.2.3-SNAPSHOT",
        release.toString == "1.2.3"
      )
    }

    test("parsing") {

      test("x.y.z") {
        assertMatch(Version.of("1.2.3")) {
          case Version.Release(1, 2, 3) =>
        }
      }

      test("x.y.z-SNAPSHOT") {
        assertMatch(Version.of("1.2.3-SNAPSHOT")) {
          case Version.Snapshot(1, 2, 3) =>
        }
      }

      test("x.y.z.r") {
        assertThrows[MatchError] {
          Version.of("1.2.3.4")
        }
      }

      test("scopt") {
        assertMatch(Version.read.read(Seq("1.2.3"))) {
          case Right(Version.Release(1, 2, 3)) =>
        }
      }

      test("upickle") {
        val in = Version.of("1.2.3")
        val out = Version.readWriter.visitString(
          Version.readWriter.write(upickle.default.StringReader, in),
          0
        )
        assert(in == out)
      }
    }

    test("modification") {

      test("to release") {
        val snapshot = Version.Snapshot(1, 2, 3)
        val release = Version.Release(1, 2, 3)

        assertAll(
          snapshot.asRelease == release,
          release.asRelease == release
        )
      }

      test("to snapshot") {
        val snapshot = Version.Snapshot(1, 2, 3)
        val release = Version.Release(1, 2, 3)

        assertAll(
          release.asSnapshot == snapshot,
          snapshot.asSnapshot == snapshot
        )
      }

      test("bumping") {

        import Bump._

        test("patch snapshot") {
          val snapshot = Version.Snapshot(1, 2, 3)

          assertMatch(snapshot.bump(patch)) {
            case Version.Snapshot(1, 2, 4) =>
          }
        }

        test("minor snapshot") {
          val snapshot = Version.Snapshot(1, 2, 3)

          assertMatch(snapshot.bump(minor)) {
            case Version.Snapshot(1, 3, 0) =>
          }
        }

        test("major snapshot") {
          val snapshot = Version.Snapshot(1, 2, 3)

          assertMatch(snapshot.bump(major)) {
            case Version.Snapshot(2, 0, 0) =>
          }
        }

        test("patch release") {
          val release = Version.Release(1, 2, 3)

          assertMatch(release.bump(patch)) {
            case Version.Release(1, 2, 4) =>
          }
        }

        test("minor release") {
          val release = Version.Release(1, 2, 3)

          assertMatch(release.bump(minor)) {
            case Version.Release(1, 3, 0) =>
          }
        }

        test("major release") {
          val release = Version.Release(1, 2, 3)

          assertMatch(release.bump(major)) {
            case Version.Release(2, 0, 0) =>
          }
        }
      }
    }
  }
}
