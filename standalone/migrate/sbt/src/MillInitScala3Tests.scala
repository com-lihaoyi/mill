package mill.standalone

import utest.*

object MillInitScala3Tests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:scala/scala3.git"
  def gitRepoBranch = "3.7.1"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> false

      "multiple modules have the same base directory"
    }
  }
}
