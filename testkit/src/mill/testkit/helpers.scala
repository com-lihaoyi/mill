package mill.testkit

import utest.TestValue

import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.reflect.NameTransformer
import scala.quoted.*

extension [A](a: A) {
  inline def asTestValue: TestValue =
    ${ TestValueConversion.impl('{ a }, customName = '{ None }) }

  inline def asTestValue(name: String): TestValue =
    ${ TestValueConversion.impl('{ a }, customName = '{ Some(name) }) }
}

object TestValueConversion {
  def impl[A](a: Expr[A], customName: Expr[Option[String]])(using
      t: Type[A],
      ctx: Quotes
  ): Expr[TestValue] = {
    import ctx.reflect.*

    // Taken from https://github.com/Somainer/scala3-nameof/blob/ffe2e3c258171e2a70ccd5aa436063911fc3cc91/src/main/scala/com/somainer/nameof/NameOfMacros.scala#L8
    @tailrec
    def extract(term: Term): String = term match
      case Inlined(Some(term: Term), _, _) => extract(term)
      case Inlined(None, _, inlined) => extract(inlined)
      case Ident(n) => NameTransformer.decode(n)
      case Select(_, n) => NameTransformer.decode(n)
      case Block(DefDef(_, _, _, Some(term)) :: Nil, _) => extract(term)
      case Block(_, expr) => extract(expr)
      case Apply(func, _) => extract(func)
      case Typed(expr, _) => extract(expr)
      case TypeApply(func, _) => extract(func)
      case _ => report.errorAndAbort(s"Can not know how to get name of ${a.show}: ${term}")

    val name = customName.valueOrAbort match {
      case None => Expr(extract(a.asTerm))
      case Some(name) => Expr(name)
    }
    val typeName = Expr(Type.show[A])
    '{ TestValue(${ name }, ${ typeName }, $a) }
  }
}

/** Adds the provided clues to the thrown [[utest.AssertionError]]. */
def withTestClues[A](clues: TestValue*)(f: => A): A = {
  try f
  catch {
    case e: utest.AssertionError =>
      val newException = e.copy(captured = clues ++ e.captured)
      newException.setStackTrace(e.getStackTrace)
      throw newException
  }
}
