package mill.eval

import mill.discover.Discovered
import mill.util.OSet
import utest._
import utest.framework.TestPath

object FailureTests extends TestSuite{

  val tests = Tests{
    val graphs = new mill.util.TestGraphs()
    import graphs._
    'evaluateSingle - {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))

      val evaluator = new Evaluator(workspace, Discovered.mapping(singleton), _ => ())

      val res1 = evaluator.evaluate(OSet(singleton.single))
      assert(
        res1.failing.keyCount == 0,
        res1.rawValues == Seq(Result.Success(0))
      )
      singleton.single.failure = Some("lols")

      val res2 = evaluator.evaluate(OSet(singleton.single))
      assert(
        res2.failing.keyCount == 1,
        res2.rawValues == Seq(Result.Failure("lols"))
      )

      singleton.single.failure = None

      val res3 = evaluator.evaluate(OSet(singleton.single))
      assert(
        res3.failing.keyCount == 0,
        res3.rawValues == Seq(Result.Success(0))
      )

      val ex = new IndexOutOfBoundsException()
      singleton.single.exception = Some(ex)

      val res4 = evaluator.evaluate(OSet(singleton.single))
      assert(
        res4.failing.keyCount == 1,
        res4.rawValues == Seq(Result.Exception(ex))
      )
    }
    'evaluatePair - {
      val workspace = ammonite.ops.pwd / 'target / 'workspace / 'failure / implicitly[TestPath].value
      ammonite.ops.rm(ammonite.ops.Path(workspace, ammonite.ops.pwd))

      val evaluator = new Evaluator(workspace, Discovered.mapping(pair), _ => ())

      val res1 = evaluator.evaluate(OSet(pair.down))
      assert(
        res1.failing.keyCount == 0,
        res1.rawValues == Seq(Result.Success(0))
      )

      pair.up.failure = Some("lols")

      val res2 = evaluator.evaluate(OSet(pair.down))
      assert(
        res2.failing.keyCount == 1,
        res2.rawValues == Seq(Result.Skipped)
      )

      pair.up.failure = None

      val res3 = evaluator.evaluate(OSet(pair.down))
      assert(
        res3.failing.keyCount == 0,
        res1.rawValues == Seq(Result.Success(0))
      )

      pair.up.exception = Some(new IndexOutOfBoundsException())

      val res4 = evaluator.evaluate(OSet(pair.down))
      assert(
        res4.failing.keyCount == 1,
        res4.rawValues == Seq(Result.Skipped)
      )
    }
  }
}

