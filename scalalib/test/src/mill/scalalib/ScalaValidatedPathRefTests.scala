package mill.scalalib

import mill._
import mill.define.NamedTask
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest._
object ScalaValidatedPathRefTests extends TestSuite {

  object ValidatedTarget extends TestBaseModule {
    private def mkDirWithFile = Task.Anon {
      os.write(Task.dest / "dummy", "dummy", createFolders = true)
      PathRef(Task.dest)
    }
    def uncheckedPathRef: T[PathRef] = Task { mkDirWithFile() }
    def uncheckedSeqPathRef: T[Seq[PathRef]] = Task { Seq(mkDirWithFile()) }
    def uncheckedAggPathRef: T[Agg[PathRef]] = Task { Agg(mkDirWithFile()) }
    def uncheckedTuplePathRef: T[Tuple1[PathRef]] = Task { Tuple1(mkDirWithFile()) }

    def checkedPathRef: T[PathRef] = Task { mkDirWithFile().withRevalidateOnce }
    def checkedSeqPathRef: T[Seq[PathRef]] = Task { Seq(mkDirWithFile()).map(_.withRevalidateOnce) }
    def checkedAggPathRef: T[Agg[PathRef]] = Task { Agg(mkDirWithFile()).map(_.withRevalidateOnce) }
    def checkedTuplePathRef: T[Tuple1[PathRef]] =
      Task { Tuple1(mkDirWithFile().withRevalidateOnce) }
  }

  def tests: Tests = Tests {

    test("validated") {
      test("PathRef") {
        def check(t: Target[PathRef], flip: Boolean) = UnitTester(ValidatedTarget, null).scoped {
          eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t)
            assert(
              result.value.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
              os.exists(result.value.path)
            )
            os.remove.all(result.value.path)
            val Right(result2) = eval.apply(t)
            assert(
              result2.value.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
              // as the result was cached but not checked, this path is missing
              os.exists(result2.value.path) == flip
            )
        }
        test("unchecked") - check(ValidatedTarget.uncheckedPathRef, false)
        test("checked") - check(ValidatedTarget.checkedPathRef, true)
      }
      test("SeqPathRef") {
        def check(t: Target[Seq[PathRef]], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped { eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t)
            assert(
              result.value.map(_.path.last) == Seq(t.asInstanceOf[NamedTask[_]].label + ".dest"),
              result.value.forall(p => os.exists(p.path))
            )
            result.value.foreach(p => os.remove.all(p.path))
            val Right(result2) = eval.apply(t)
            assert(
              result2.value.map(_.path.last) == Seq(t.asInstanceOf[NamedTask[_]].label + ".dest"),
              // as the result was cached but not checked, this path is missing
              result2.value.forall(p => os.exists(p.path) == flip)
            )
          }
        test("unchecked") - check(ValidatedTarget.uncheckedSeqPathRef, false)
        test("checked") - check(ValidatedTarget.checkedSeqPathRef, true)
      }
      test("AggPathRef") {
        def check(t: Target[Agg[PathRef]], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped { eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t)
            assert(
              result.value.map(_.path.last) == Agg(t.asInstanceOf[NamedTask[_]].label + ".dest"),
              result.value.forall(p => os.exists(p.path))
            )
            result.value.foreach(p => os.remove.all(p.path))
            val Right(result2) = eval.apply(t)
            assert(
              result2.value.map(_.path.last) == Agg(t.asInstanceOf[NamedTask[_]].label + ".dest"),
              // as the result was cached but not checked, this path is missing
              result2.value.forall(p => os.exists(p.path) == flip)
            )
          }
        test("unchecked") - check(ValidatedTarget.uncheckedAggPathRef, false)
        test("checked") - check(ValidatedTarget.checkedAggPathRef, true)
      }
      test("other") {
        def check(t: Target[Tuple1[PathRef]], flip: Boolean) =
          UnitTester(ValidatedTarget, null).scoped { eval =>
            // we reconstruct faulty behavior
            val Right(result) = eval.apply(t)
            assert(
              result.value._1.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
              os.exists(result.value._1.path)
            )
            os.remove.all(result.value._1.path)
            val Right(result2) = eval.apply(t)
            assert(
              result2.value._1.path.last == (t.asInstanceOf[NamedTask[_]].label + ".dest"),
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
