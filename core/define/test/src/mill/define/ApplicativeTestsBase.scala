package mill.define

import mill.define.internal.Applicative

import scala.quoted.*

class Opt[+T](val self: Option[T]) extends Applicative.Applyable[Option, T]
object Opt extends Applicative.Applyer[Opt, Option, Applicative.Id, String] {

  val injectedCtx = "helloooo"
  inline def apply[T](inline t: T): Option[T] =
    ${ applyImpl[T]('t)('this) }

  def traverseCtx[I, R](xs: Seq[Opt[I]])(f: (Seq[I], String) => Applicative.Id[R])
  : Option[R] = {
    if (xs.exists(_.self.isEmpty)) None
    else Some(f(xs.map(_.self.get).toVector, Opt.injectedCtx))
  }
  def applyImpl[T: Type](t: Expr[T])(caller: Expr[Applicative.Applyer[
    Opt,
    Option,
    Applicative.Id,
    String
  ]])(using Quotes): Expr[Option[T]] =
    Applicative.impl[Option, Opt, Applicative.Id, T, String]((args, fn) => '{ traverseCtx($args)($fn) }, t)
}
