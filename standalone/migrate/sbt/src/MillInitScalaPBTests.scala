package mill.standalone

import utest.*

object MillInitScalaPBTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:scalapb/ScalaPB.git"
  def gitRepoBranch = "v0.11.19"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> false

      "requires sbt-projectmatrix"
    }
  }
}
