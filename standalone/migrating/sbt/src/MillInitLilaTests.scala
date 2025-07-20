package mill.standalone

import utest.*

object MillInitLilaTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:lichess-org/lila.git"
  def gitRepoBranch = "master"

  def tests = Tests {
    test - standaloneTest { tester =>
      import tester.*

      eval("init").isSuccess ==> true
      eval("__.compile").isSuccess ==> true
      /*
[6380] [error] -- [E8] .../mill/out/standalone/migrating/sbt/packaged/nodaemon/testOnly.dest/sandbox/clone/lila/modules/analyse/package.mill:73:69
[6380] [error] 73 │    def moduleDeps = super.moduleDeps ++ Seq(build.modules.coreI18n.test)
[6380] [error]    │                                                                    ^^^^
[6380] [error]    │value test is not a member of object build_.modules.coreI18n.package_
       */
    }
  }
}
