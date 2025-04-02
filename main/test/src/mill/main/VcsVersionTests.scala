package mill.main

import utest.*
object VcsVersionTests extends TestSuite {

  def state(
      lastTag: String,
      commitsSinceLastTag: Int,
      dirtyHash: String = null,
      currentRevision: String = "abcdefghijklmnopqrstuvwxyz",
      vcs: String = "git"
  ): VcsVersion.State = VcsVersion.State(
    currentRevision,
    Option(lastTag),
    commitsSinceLastTag,
    Option(dirtyHash),
    vcs = Option(VcsVersion.Vcs(vcs))
  )

  def tests = Tests {
    "VcsState.format" - {
      "With default format options" - {
        "without any tag and commit" - {
          assert(state(null, 0, null).format() == "0.0.0-0-abcdef")
        }
        "without any tag" - {
          assert(state(null, 2, null).format() == "0.0.0-2-abcdef")
        }
        "locally changed without any tag" - {
          assert(state(null, 1, "d23456789").format() == "0.0.0-1-abcdef-DIRTYd2345678")
        }
        "locally changed without any tag and commit" - {
          assert(state(null, 0, "d23456789").format() == "0.0.0-0-abcdef-DIRTYd2345678")
        }
        "other" - {
          assert(
            state("0.0.1", 2, "d23456789")
              .format() == "0.0.1-2-abcdef-DIRTYd2345678"
          )
        }
      }

      "should strip the `v` prefix from the tag by default" - {
        assert(
          state("v0.7.3", 0).format() == "0.7.3"
        )

      }

      "should be able to use a tag modifier to change the tag" - {
        assert(
          state("v0.7.3", 0, null)
            .format(tagModifier = {
              case t if t.startsWith("v") => t.substring(1) + "v"
              case t => t
            }) == "0.7.3v"
        )
      }

      "should not render the commit count when commitCountPad is negative" - {
        assert(
          state("0.7.3", 4, "a6ea44d3726", "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
            .format(
              dirtyHashDigits = 8,
              commitCountPad = -1,
              countSep = ""
            ) == "0.7.3-61568e-DIRTYa6ea44d3"
        )
        assert(
          state("0.7.3", 4, null, "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
            .format(dirtyHashDigits = 8, commitCountPad = -1, countSep = "") == "0.7.3-61568e"
        )
      }

      "should append a -SNAPSHOT suffix" - {
        assert(
          state("0.7.3", 0, null, "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
            .format(untaggedSuffix = "-SNAPSHOT") == "0.7.3"
        )
        assert(
          state("0.7.3", 4, "a6ea44d3726", "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
            .format(untaggedSuffix = "-SNAPSHOT") == "0.7.3-4-61568e-DIRTYa6ea44d3-SNAPSHOT"
        )
        assert(
          state("0.7.3", 4, null, "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
            .format(untaggedSuffix = "-SNAPSHOT") == "0.7.3-4-61568e-SNAPSHOT"
        )
      }

      "should format with fallback tag when no vcs" - {
        assert(
          state(null, 0, null, "no-vcs", null).format() == "0.0.0-0-no-vcs"
        )
      }

      "Example format configs" - {
        "mill" - {
          assert(
            state("0.7.3", 4, "a6ea44d3726", "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
              .format(
                dirtyHashDigits = 8,
                commitCountPad = 0,
                countSep = "-"
              ) == "0.7.3-4-61568e-DIRTYa6ea44d3"
          )
          assert(
            state("0.7.3", 4, null, "61568ec80f2465f3f01ea2c7e92273f4fbf94b01")
              .format(dirtyHashDigits = 8, commitCountPad = 0, countSep = "-") == "0.7.3-4-61568e"
          )
        }
        "Comfis" - {
          assert(
            state("5.3.7", 30, "d23456789", "618c86095ce483feea2e331cc4e28e6466d634f7")
              .format(
                dirtyHashDigits = 0,
                commitCountPad = 4,
                countSep = "."
              ) == "5.3.7.0030-618c86-DIRTY"
          )
          assert(
            state("5.3.7", 30, null, "618c86095ce483feea2e331cc4e28e6466d634f7")
              .format(
                dirtyHashDigits = 0,
                commitCountPad = 4,
                countSep = "."
              ) == "5.3.7.0030-618c86"
          )
        }
      }

    }
  }
}
