package mill.spotless

import utest.framework.TestPath

object TestUtil {

  def diff(original: os.Path, modified: os.Path): Int =
    os.proc("git", "diff", "--no-index", original, modified)
      .call(stdin = os.Inherit, stdout = os.Inherit, check = false)
      .exitCode

  def sandbox[A](root: os.Path)(thunk: => A)(using tp: TestPath): A =
    val testRoot = os.pwd / tp.value
    os.copy.over(
      root,
      testRoot,
      replaceExisting = true,
      copyAttributes = true,
      createFolders = true
    )
    os.dynamicPwd.withValue(testRoot)(thunk)
}
