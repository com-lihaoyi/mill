package mill.eval

import mill.util.TestEvaluator
import ammonite.ops.pwd
import utest._
import utest.framework.TestPath
import mill.util.TestEvaluator.implicitDisover
object FailureTests extends TestSuite{

  def workspace(implicit tp: TestPath) = {
    ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
  }


  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._

    'evaluateSingle - {
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))
      val check = new TestEvaluator(singleton, workspace, pwd)
      check.fail(
        target = singleton.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      singleton.single.failure = Some("lols")

      check.fail(
        target = singleton.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Failure("lols"))
      )

      singleton.single.failure = None

      check.fail(
        target = singleton.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )


      val ex = new IndexOutOfBoundsException()
      singleton.single.exception = Some(ex)


      check.fail(
        target = singleton.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Exception(ex, Nil))
      )
    }
    'evaluatePair - {
      val check = new TestEvaluator(pair, workspace, pwd)
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))
      check.fail(
        pair.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      pair.up.failure = Some("lols")

      check.fail(
        pair.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Skipped)
      )

      pair.up.failure = None

      check.fail(
        pair.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      pair.up.exception = Some(new IndexOutOfBoundsException())

      check.fail(
        pair.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Skipped)
      )
    }
  }
}

