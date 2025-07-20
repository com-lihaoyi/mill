package mill.standalone

import utest.*

object MillInitCatsTests extends GitRepoStandaloneTestSuite {

  def gitRepoUrl = "git@github.com:typelevel/cats.git"
  def gitRepoBranch = "v2.13.0"

  def tests = Tests {
    test - standaloneTest { tester =>
      import tester.*

      eval("init").isSuccess ==> true
      eval("__.compile").isSuccess ==> true
      eval("__.test").isSuccess ==> true
      /*
[6380] 6 tasks failed
[6380] kernel-laws.native.resolvedMvnDeps java.lang.RuntimeException:
[6380] Resolution failed for 7 modules:
[6380] --------------------------------------------
[6380]   org.scala-native:auxlib_2.13:0.5.6
       */
    }
  }
}
