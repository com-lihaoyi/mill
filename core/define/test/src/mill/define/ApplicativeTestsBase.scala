package mill.define

import mill.define.internal.Applicative

import scala.quoted.*

class Opt[+T](val self: Option[T]) extends Applicative.Applyable[Opt, T]
object Opt extends Applicative.Applyer[Opt, Applicative.Id, String] {
  def none: Opt[Nothing ] = new Opt(None)
  def some[T](t: T): Opt[T] = new Opt(Some(t))
  val injectedCtx = "helloooo"
  inline def apply[T](inline t: T): Opt[T] =
    ${ applyImpl[T]('t)('this) }

  def traverseCtx[I, R](xs: Seq[Opt[I]])(f: (Seq[I], String) => Applicative.Id[R])
      : Opt[R] = {
    new Opt(
      if (xs.exists(_.self.isEmpty)) None
      else Some(f(xs.map(_.self.get).toVector, Opt.injectedCtx))
    )
  }
  def applyImpl[T: Type](t: Expr[T])(caller: Expr[Applicative.Applyer[
    Opt,
    Applicative.Id,
    String
  ]])(using Quotes): Expr[Opt[T]] =
    Applicative.impl[Opt, Opt, Applicative.Id, T, String](
      (args, fn) => '{ traverseCtx($args)($fn) },
      t
    )
}
