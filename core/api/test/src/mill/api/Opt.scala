package mill.api

import mill.api.internal.Applicative

import scala.quoted.*

case class Opt[+T](self: Option[T]) extends Applicative.Applyable[Opt, T]
object Opt {
  def none: Opt[Nothing] = new Opt(None)
  def some[T](t: T): Opt[T] = new Opt(Some(t))
  val injectedCtx = "helloooo"

  def ctx()(implicit c: String): String = c
  inline def apply[T](inline t: T): Opt[T] = ${ applyImpl[T]('t) }

  def traverseCtx[I, R](xs: Seq[Opt[I]])(f: (Seq[I], String) => Applicative.Id[R])
      : Opt[R] = {
    new Opt(
      if (xs.exists(_.self.isEmpty)) None
      else Some(f(xs.map(_.self.get).toVector, Opt.injectedCtx))
    )
  }
  def applyImpl[T: Type](t: Expr[T])(using
      Quotes
  ): Expr[Opt[T]] =
    Applicative.impl[Opt, Opt, Applicative.Id, T, String](
      (args, fn) => '{ traverseCtx($args)($fn) },
      t
    )
}
