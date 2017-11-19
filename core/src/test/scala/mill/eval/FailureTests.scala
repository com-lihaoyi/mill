package mill.eval

import mill.define.Target
import mill.discover.Discovered
import mill.util.OSet
import utest._
import utest.framework.TestPath

object FailureTests extends TestSuite{

  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    def check[T: Discovered](base: T)
                            (target: T => Target[_], expectedFailCount: Int, expectedRawValues: Seq[Result[_]])
                            (implicit tp: TestPath) = {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
      val evaluator = new Evaluator(workspace, Discovered.mapping(base), _ => ())
      val res = evaluator.evaluate(OSet(target(base)))
      assert(
        res.rawValues == expectedRawValues,
        res.failing.keyCount == expectedFailCount
      )

    }
    'evaluateSingle - {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))

      check(singleton)(
        target = _.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      singleton.single.failure = Some("lols")

      check(singleton)(
        target = _.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Failure("lols"))
      )

      singleton.single.failure = None

      check(singleton)(
        target = _.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )


      val ex = new IndexOutOfBoundsException()
      singleton.single.exception = Some(ex)


      check(singleton)(
        target = _.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Exception(ex))
      )
    }
    'evaluatePair - {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))

      check(pair)(
        _.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      pair.up.failure = Some("lols")

      check(pair)(
        _.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Skipped)
      )

      pair.up.failure = None

      check(pair)(
        _.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      pair.up.exception = Some(new IndexOutOfBoundsException())

      check(pair)(
        _.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Skipped)
      )
    }
  }
}

