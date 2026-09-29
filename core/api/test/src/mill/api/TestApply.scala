package mill.api

import mill.api.internal.Applicative

import scala.quoted.*

case class TestApply[+T](self: Option[T]) extends Applicative.Applyable[TestApply, T]
object TestApply {
  def none: TestApply[Nothing] = TestApply(None)
  def some[T](t: T): TestApply[T] = TestApply(Some(t))
  val injectedCtx = "helloooo"

  def ctx()(using c: String): String = c
  inline def apply[T](inline t: T): TestApply[T] = ${ applyImpl[T]('t) }

  def traverseCtx[I, R](xs: Seq[TestApply[I]])(f: (Seq[I], String) => Applicative.Id[R])
      : TestApply[R] = {
    TestApply(
      if (xs.exists(_.self.isEmpty)) None
      else Some(f(xs.map(_.self.get).toVector, TestApply.injectedCtx))
    )
  }
  def applyImpl[T: Type](t: Expr[T])(using
      Quotes
  ): Expr[TestApply[T]] =
    Applicative.impl[TestApply, TestApply, Applicative.Id, T, String](
      (args, fn) => '{ traverseCtx($args)($fn) },
      t
    )
}
