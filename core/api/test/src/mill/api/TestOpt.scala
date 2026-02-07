package mill.api

import mill.api.internal.Applicative

import scala.quoted.*

case class TestOpt[+T](self: Option[T]) extends Applicative.Applyable[TestOpt, T]
object TestOpt {
  def none: TestOpt[Nothing] = new TestOpt(None)
  def some[T](t: T): TestOpt[T] = new TestOpt(Some(t))
  val injectedCtx = "helloooo"

  def ctx()(using c: String): String = c
  inline def apply[T](inline t: T): TestOpt[T] = ${ applyImpl[T]('t) }

  def traverseCtx[I, R](xs: Seq[TestOpt[I]])(f: (Seq[I], String) => Applicative.Id[R])
      : TestOpt[R] = {
    new TestOpt(
      if (xs.exists(_.self.isEmpty)) None
      else Some(f(xs.map(_.self.get).toVector, TestOpt.injectedCtx))
    )
  }
  def applyImpl[T: Type](t: Expr[T])(using
      Quotes
  ): Expr[TestOpt[T]] =
    Applicative.impl[TestOpt, TestOpt, Applicative.Id, T, String](
      (args, fn) => '{ traverseCtx($args)($fn) },
      t
    )
}
