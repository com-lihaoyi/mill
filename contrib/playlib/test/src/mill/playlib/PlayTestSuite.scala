package mill.playlib

import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath

trait PlayTestSuite {

  val testScala212 = sys.props.getOrElse("MILL_SCALA_2_12_VERSION", ???)
  val testScala213 = sys.props.getOrElse("MILL_SCALA_2_13_VERSION", ???)
  val testScala3 = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)

  val testPlay26 = sys.props.getOrElse("TEST_PLAY_VERSION_2_6", ???)
  val testPlay27 = sys.props.getOrElse("TEST_PLAY_VERSION_2_7", ???)
  val testPlay28 = sys.props.getOrElse("TEST_PLAY_VERSION_2_8", ???)
  val testPlay29 = sys.props.getOrElse("TEST_PLAY_VERSION_2_9", ???)

  val matrix = Seq(
    (testScala212, testPlay26),
    (testScala212, testPlay27),
    (testScala213, testPlay27),
    (testScala213, testPlay28),
    (testScala213, testPlay29),
    (testScala3, testPlay29)
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
