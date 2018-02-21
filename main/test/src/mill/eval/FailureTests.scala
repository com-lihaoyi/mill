package mill.eval
import mill.T
import mill.util.{TestEvaluator, TestUtil}
import ammonite.ops.{Path, pwd, rm}
import mill.eval.Result.OuterStack
import utest._
import utest.framework.TestPath


object FailureTests extends TestSuite{

  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._

    'evaluateSingle - {
      val check = new TestEvaluator(singleton)
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
        expectedRawValues = Seq(Result.Exception(ex, new OuterStack(Nil)))
      )
    }
    'evaluatePair - {
      val check = new TestEvaluator(pair)
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
    'multipleUsesOfDest - {
      object build extends TestUtil.BaseModule {
        // Using `T.ctx(  ).dest` twice in a single task is ok
        def left = T{ + T.ctx().dest.toString.length + T.ctx().dest.toString.length }

        // Using `T.ctx(  ).dest` once in two different tasks is not ok
        val task = T.task{ T.ctx().dest.toString.length  }
        def right = T{ task() + left() + T.ctx().dest.toString().length }
      }

      val check = new TestEvaluator(build)
      val Right(_) = check(build.left)
      val Left(Result.Exception(e, _)) = check(build.right)
      assert(e.getMessage.contains("`dest` can only be used in one place"))
    }
  }
}

