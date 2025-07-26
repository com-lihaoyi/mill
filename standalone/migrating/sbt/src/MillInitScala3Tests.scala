package mill.standalone

import utest.*

object MillInitScala3Tests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:scala/scala3.git"
  def gitRepoBranch = "3.7.1"

  def tests = Tests {
    test - standaloneTest { tester =>
      import tester.*

      eval("init").isSuccess ==> true
      eval("__.compile").isSuccess ==> true
      /*
[6380] Exception in thread "main" java.lang.IllegalArgumentException: Project at duplicate locations
       */
    }
  }
}
