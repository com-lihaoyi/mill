package mill.scalalib

import mill.scalalib.semver.{PreReleaseVersion, ReleaseVersion, Version}
import utest._

object GitVersioningTests extends TestSuite {
  def tests: Tests = Tests {
    'gitVersioning - {
      'version - {
        'parse - {
          'releaseVersion - {
            val parsed = Version.parse("1.2.3")
            assert(parsed.contains(ReleaseVersion(1, 2, 3)))
          }
          'preReleaseVersion - {
            val parsed = Version.parse("1.2.3-rc.1")
            assert(parsed.contains(PreReleaseVersion(1, 2, 3, "-rc.1")))
          }
          'wrongVersion - {
            val parsed = Version.parse("1.2.3.4")
            assert(parsed.isEmpty)
          }
        }
        'infer - {
          val commits = Seq(
            ("0001", "initial commit", None, "0.0.1-1+0001"),
            ("0002", "initial release", Some("0.5.0"), "0.5.0"),
            ("0003", "patch", None, "0.5.1-1+0003"),
            ("0004", "[minor] minor change", None, "0.6.0-2+0004"),
            ("0005", "[major] major change", None, "1.0.0-3+0005"),
            ("0006", "release candidate", Some("2.0.0-rc.1"), "2.0.0-rc.1"),
            ("0007", "patch 2", None, "2.0.0-5+0007"),
            ("0008", "release", Some("2.0.0"), "2.0.0")
          )

          def git(max: Int) = new Git {
            private val visibleCommits = commits.take(max)
            override def commitsBetween(refFrom: Option[String], refTo: Option[String]): Seq[(String, String)] = {
              val from = refFrom.fold(0)(from => visibleCommits.indexWhere(_._1 == from))
              val until = refTo.fold(visibleCommits.length)(to => visibleCommits.indexWhere(_._1 == to) + 1)
              assert(from >= 0, until >= 0)
              visibleCommits.slice(from, until).map {
                case (hash, msg, _, _) => hash -> msg
              }
            }
            override def tags: Seq[(String, String)] = {
              visibleCommits.collect {
                case (hash, _, Some(tag), _) => hash -> tag
              }
            }
          }

          val versions = (0 to commits.length).map(c => GitVersionModule.inferVersion(git(c)))
          val expected = "0.0.0" +: commits.map(_._4)
          assert(versions == expected)
        }
      }
    }
  }
}
