package mill.playlib

import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath

trait PlayTestSuite {

  val testScala212 = sys.props.getOrElse("MILL_SCALA_2_12_VERSION", ???)
  val testScala213 = sys.props.getOrElse("MILL_SCALA_2_13_VERSION", ???)

  val matrix = Seq(
    (testScala212, "2.6.15"),
    (testScala212, "2.7.9"),
    (testScala213, "2.8.8")
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
