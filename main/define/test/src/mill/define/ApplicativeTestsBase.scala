package mill.define

import scala.quoted.*

trait ApplicativeTestsBase {
  class Opt[+T](val self: Option[T]) extends Applicative.Applyable[Option, T]
  object Opt extends Applicative.Applyer[Opt, Option, Applicative.Id, String] {

    val injectedCtx = "helloooo"
    inline def apply[T](inline t: T): Option[T] =
      ${ ApplicativeTestsBase.applyImpl[Opt, T]('t)('this) }

    def traverseCtx[I, R](xs: Seq[Opt[I]])(f: (IndexedSeq[I], String) => Applicative.Id[R])
        : Option[R] = {
      if (xs.exists(_.self.isEmpty)) None
      else Some(f(xs.map(_.self.get).toVector, injectedCtx))
    }
  }
}

object ApplicativeTestsBase {
  def applyImpl[Opt[+_]: Type, T: Type](t: Expr[T])(caller: Expr[Applicative.Applyer[
    Opt,
    Option,
    Applicative.Id,
    String
  ]])(using Quotes): Expr[Option[T]] =
    Applicative.impl[Option, Opt, Applicative.Id, T, String](
      (args, fn) => '{ $caller.traverseCtx($args)($fn) },
      t
    )
}
