package mill.standalone

import utest.*

object MillInitRefinedTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:fthomas/refined.git"
  def gitRepoBranch = "v0.11.3"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("modules.core.jvm[2.12.20].compile").isSuccess ==> false

      "custom version range 3.0- not supported by CrossScalaVersionRanges"
    }
  }
}
