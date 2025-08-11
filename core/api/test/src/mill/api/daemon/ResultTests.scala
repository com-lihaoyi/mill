package mill.api.daemon

import utest.*

object ResultTests extends TestSuite {
  val tests = Tests {
    test("sequence") {
      test("empty") {
        val actual = Result.sequence(Seq.empty)
        assert(actual == Result.Success(Seq.empty))
      }

      test("success") {
        val actual = Result.sequence(Seq(Result.Success(1), Result.Success(2)))
        assert(actual == Result.Success(Seq(1, 2)))
      }

      test("failure") {
        val actual =
          Result.sequence(Seq(Result.Success(1), Result.Failure("fail"), Result.Success(2)))
        assert(actual == Result.Failure("fail"))
      }
    }

    test("traverse") {
      test("empty") {
        test("success") {
          val actual = Result.traverse(Seq.empty)(Result.Success(_))
          assert(actual == Result.Success(Seq.empty))
        }

        test("failure") {
          val actual = Result.traverse(Seq.empty)(_ => Result.Failure("fail"))
          assert(actual == Result.Success(Seq.empty))
        }
      }

      test("success") {
        val actual = Result.traverse(Seq(1, 2))(Result.Success(_))
        assert(actual == Result.Success(Seq(1, 2)))
      }

      test("failure") {
        val actual = Result.traverse(Seq(1, 2, 3)) { x =>
          if (x % 2 == 0) Result.Failure(s"fail $x") else Result.Success(x)
        }
        assert(actual == Result.Failure("fail 2"))
      }
    }
  }
}
