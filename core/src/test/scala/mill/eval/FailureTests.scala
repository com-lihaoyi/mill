package mill.eval

import mill.define.Target
import mill.discover.Discovered
import mill.util.{DummyLogger, OSet}
import utest._
import utest.framework.TestPath

object FailureTests extends TestSuite{

  def workspace(implicit tp: TestPath) = {
    ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
  }
  class Checker[T: Discovered](base: T)(implicit tp: TestPath){

    val evaluator = new Evaluator(workspace, Discovered.mapping(base), DummyLogger)

    def apply(target: T => Target[_], expectedFailCount: Int, expectedRawValues: Seq[Result[_]]) = {

      val res = evaluator.evaluate(OSet(target(base)))
      assert(
        res.rawValues == expectedRawValues,
        res.failing.keyCount == expectedFailCount
      )

    }
  }
  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._

    'evaluateSingle - {
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))
      val check = new Checker(singleton)
      check(
        target = _.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      singleton.single.failure = Some("lols")

      check(
        target = _.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Failure("lols"))
      )

      singleton.single.failure = None

      check(
        target = _.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )


      val ex = new IndexOutOfBoundsException()
      singleton.single.exception = Some(ex)


      check(
        target = _.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Exception(ex))
      )
    }
    'evaluatePair - {
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))
      val check = new Checker(pair)
      check(
        _.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      pair.up.failure = Some("lols")

      check(
        _.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Skipped)
      )

      pair.up.failure = None

      check(
        _.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(Result.Success(0))
      )

      pair.up.exception = Some(new IndexOutOfBoundsException())

      check(
        _.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(Result.Skipped)
      )
    }
  }
}

