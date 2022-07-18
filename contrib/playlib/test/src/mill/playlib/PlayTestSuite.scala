package mill.playlib

import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath

trait PlayTestSuite {
  val matrix = Seq(
    ("2.12.16", "2.6.25"),
    ("2.12.16", "2.7.9"),
    ("2.13.8", "2.8.16")
  )

  def resourcePath: os.Path

  def workspaceTest[T, M <: TestUtil.BaseModule](
      m: M,
      resourcePath: os.Path = resourcePath
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(resourcePath, m.millSourcePath)
    t(eval)
  }
}
