package mill.eval

import mill.{Task}
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import mill.api.Result.OuterStack
import utest._

object FailureTests extends TestSuite {

  val tests = Tests {
    val graphs = new mill.util.TestGraphs()
    import graphs._

    test("evaluateSingle") {
      val check = UnitTester(singleton, null)
      check.fail(
        target = singleton.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      singleton.single.failure = Some("lols")

      check.fail(
        target = singleton.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Failure("lols"))
      )

      singleton.single.failure = None

      check.fail(
        target = singleton.single,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      val ex = new IndexOutOfBoundsException()
      singleton.single.exception = Some(ex)

      check.fail(
        target = singleton.single,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Exception(ex, new OuterStack(Nil)))
      )
    }
    test("evaluatePair") {
      val check = UnitTester(pair, null)
      check.fail(
        pair.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      // inject some fake error
      pair.up.failure = Some("lols")

      check.fail(
        pair.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Skipped)
      )

      pair.up.failure = None

      check.fail(
        pair.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      pair.up.exception = Some(new IndexOutOfBoundsException())

      check.fail(
        pair.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Skipped)
      )
    }

    test("evaluatePair (failFast=true)") {
      val check = UnitTester(pair, null, failFast = true)
      check.fail(
        pair.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      pair.up.failure = Some("lols")

      check.fail(
        pair.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Aborted)
      )

      pair.up.failure = None

      check.fail(
        pair.down,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      pair.up.exception = Some(new IndexOutOfBoundsException())

      check.fail(
        pair.down,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Aborted)
      )
    }

    test("evaluateBacktickIdentifiers") {
      val check = UnitTester(bactickIdentifiers, null)
      import bactickIdentifiers._
      check.fail(
        `a-down-target`,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      `up-target`.failure = Some("lols")

      check.fail(
        `a-down-target`,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Skipped)
      )

      `up-target`.failure = None

      check.fail(
        `a-down-target`,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      `up-target`.exception = Some(new IndexOutOfBoundsException())

      check.fail(
        `a-down-target`,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Skipped)
      )
    }

    test("evaluateBacktickIdentifiers (failFast=true)") {
      val check = UnitTester(bactickIdentifiers, null, failFast = true)
      import bactickIdentifiers._
      check.fail(
        `a-down-target`,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      `up-target`.failure = Some("lols")

      check.fail(
        `a-down-target`,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Aborted)
      )

      `up-target`.failure = None

      check.fail(
        `a-down-target`,
        expectedFailCount = 0,
        expectedRawValues = Seq(mill.api.Result.Success(0))
      )

      `up-target`.exception = Some(new IndexOutOfBoundsException())

      check.fail(
        `a-down-target`,
        expectedFailCount = 1,
        expectedRawValues = Seq(mill.api.Result.Aborted)
      )
    }

    test("multipleUsesOfDest") {
      object build extends TestBaseModule {
        // Using `Task.ctx(  ).dest` twice in a single task is ok
        def left = Task { +Task.dest.toString.length + Task.dest.toString.length }

        // Using `Task.ctx(  ).dest` once in two different tasks is ok
        val task = Task.Anon { Task.dest.toString.length }
        def right = Task { task() + left() + Task.dest.toString().length }
      }

      val check = UnitTester(build, null)
      assert(check(build.left).isRight)
      assert(check(build.right).isRight)
      // assert(e.getMessage.contains("`dest` can only be used in one place"))
    }
  }
}
