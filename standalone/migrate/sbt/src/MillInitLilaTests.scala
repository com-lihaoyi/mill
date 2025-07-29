package mill.standalone

import utest.*

object MillInitLilaTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:lichess-org/lila.git"
  def gitRepoBranch = "master"

  def tests = Tests {
    test("poc") - standaloneTest { tester =>
      import tester.*

      eval(("init", "--poc")).isSuccess ==> true
      eval(("resolve", "_")).isSuccess ==> false

      """module dependency core.test does not exist
        |requires sbt-play
        |""".stripMargin
    }
  }
}
