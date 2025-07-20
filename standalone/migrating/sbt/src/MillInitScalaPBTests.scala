package mill.standalone

import utest.*

object MillInitScalaPBTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:scalapb/ScalaPB.git"
  def gitRepoBranch = "v0.11.19"

  def tests = Tests {
    test - standaloneTest { tester =>
      import tester.*

      eval("init").isSuccess ==> true
      eval("__.compile").isSuccess ==> true
      eval("__.test").isSuccess ==> true
      /*
[6380] Cannot resolve __.test. Try `mill resolve _` to see what's available.
       */
    }
  }
}
