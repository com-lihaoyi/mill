package mill.scalalib

import mill.*
import mill.api.{Discover, Task}
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

// TODO: Move into Java module or even further up
object ScalaValidatedPathRefTests extends TestSuite {

  object ValidatedTarget extends TestRootModule {
    private def mkDirWithFile = Task.Anon {
      os.write(Task.dest / "dummy", "dummy", createFolders = true)
      PathRef(Task.dest)
    }
    def uncheckedPathRef: T[PathRef] = Task { mkDirWithFile() }
    def uncheckedSeqPathRef: T[Seq[PathRef]] = Task { Seq(mkDirWithFile()) }
    def uncheckedAggPathRef: T[Seq[PathRef]] = Task { Seq(mkDirWithFile()) }
    def uncheckedTuplePathRef: T[Tuple1[PathRef]] = Task { Tuple1(mkDirWithFile()) }

    def checkedPathRef: T[PathRef] = Task { mkDirWithFile().withRevalidateOnce }
    def checkedSeqPathRef: T[Seq[PathRef]] = Task { Seq(mkDirWithFile()).map(_.withRevalidateOnce) }
    def checkedAggPathRef: T[Seq[PathRef]] = Task { Seq(mkDirWithFile()).map(_.withRevalidateOnce) }
    def checkedTuplePathRef: T[Tuple1[PathRef]] =
      Task { Tuple1(mkDirWithFile().withRevalidateOnce) }

    lazy val millDiscover = Discover[this.type]
  }

  def tests: Tests = Tests {

    test("validated") {
      test("PathRef") {
        def check(t: Task.Simple[PathRef], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped {
            eval =>
              // we reconstruct faulty behavior
              val Right(result) = eval.apply(t): @unchecked
              assert(
                result.value.path.last == (t.asInstanceOf[Task.Named[?]].label + ".dest"),
                os.exists(result.value.path)
              )
              os.remove.all(result.value.path)
              val Right(result2) = eval.apply(t): @unchecked
              assert(
                result2.value.path.last == (t.asInstanceOf[Task.Named[?]].label + ".dest"),
                // as the result was cached but not checked, this path is missing
                os.exists(result2.value.path) == flip
              )
          }
        test("unchecked") - check(ValidatedTarget.uncheckedPathRef, false)
        test("checked") - check(ValidatedTarget.checkedPathRef, true)
      }
      test("SeqPathRef") {
        def check(t: Task.Simple[Seq[PathRef]], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped { eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t): @unchecked
            assert(
              result.value.map(_.path.last) == Seq(t.asInstanceOf[Task.Named[?]].label + ".dest"),
              result.value.forall(p => os.exists(p.path))
            )
            result.value.foreach(p => os.remove.all(p.path))
            val Right(result2) = eval.apply(t): @unchecked
            assert(
              result2.value.map(_.path.last) == Seq(t.asInstanceOf[Task.Named[?]].label + ".dest"),
              // as the result was cached but not checked, this path is missing
              result2.value.forall(p => os.exists(p.path) == flip)
            )
          }
        test("unchecked") - check(ValidatedTarget.uncheckedSeqPathRef, false)
        test("checked") - check(ValidatedTarget.checkedSeqPathRef, true)
      }
      test("AggPathRef") {
        def check(t: Task.Simple[Seq[PathRef]], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped { eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t): @unchecked
            assert(
              result.value.map(_.path.last) == Seq(t.asInstanceOf[Task.Named[?]].label + ".dest"),
              result.value.forall(p => os.exists(p.path))
            )
            result.value.foreach(p => os.remove.all(p.path))
            val Right(result2) = eval.apply(t): @unchecked
            assert(
              result2.value.map(_.path.last) == Seq(t.asInstanceOf[Task.Named[?]].label + ".dest"),
              // as the result was cached but not checked, this path is missing
              result2.value.forall(p => os.exists(p.path) == flip)
            )
          }
        test("unchecked") - check(ValidatedTarget.uncheckedAggPathRef, false)
        test("checked") - check(ValidatedTarget.checkedAggPathRef, true)
      }
      test("other") {
        def check(t: Task.Simple[Tuple1[PathRef]], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped { eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t): @unchecked
            assert(
              result.value._1.path.last == (t.asInstanceOf[Task.Named[?]].label + ".dest"),
              os.exists(result.value._1.path)
            )
            os.remove.all(result.value._1.path)
            val Right(result2) = eval.apply(t): @unchecked
            assert(
              result2.value._1.path.last == (t.asInstanceOf[Task.Named[?]].label + ".dest"),
              // as the result was cached but not checked, this path is missing
              os.exists(result2.value._1.path) == flip
            )
          }
        test("unchecked") - check(ValidatedTarget.uncheckedTuplePathRef, false)
        test("checked") - check(ValidatedTarget.checkedTuplePathRef, true)
      }

    }

  }
}
