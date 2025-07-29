package mill.standalone

import utest.*

object MillInitNscalaTimeTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:nscala-time/nscala-time.git"
  def gitRepoBranch = "releases/3.0.0"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> true
      eval("[2.11.12].compile").isSuccess ==> true
      eval("[2.11.12].test").isSuccess ==> true
      eval("[2.11.12].publishLocal").isSuccess ==> true

      "requires manual adjustments for cross Scala versions"
    }
  }
}
