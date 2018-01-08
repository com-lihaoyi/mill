package mill.define
import language.experimental.macros
import scala.reflect.ClassTag
import scala.reflect.macros.{Context, blackbox}

case class Cross[+T](items: List[(List[Any], T)])(implicit val e: sourcecode.Enclosing, val l: sourcecode.Line){
  def flatMap[V](f: T => Cross[V]): Cross[V] = new Cross(
    items.flatMap{
      case (l, v) => f(v).items.map{case (l2, v2) => (l2 ::: l, v2)}
    }
  )
  def map[V](f: T => V): Cross[V] = new Cross(items.map{case (l, v) => (l, f(v))})
  def withFilter(f: T => Boolean): Cross[T] = new Cross(items.filter(t => f(t._2)))

  def applyOpt(input: Any*): Option[T] = {
    val inputList = input.toList
    items.find(_._1 == inputList).map(_._2)
  }
  def apply(input: Any*): T = {
    applyOpt(input:_*).getOrElse(
      throw new Exception(
        "Unknown set of cross values: " + input +
        " not in known values\n" + items.map(_._1).mkString("\n")
      )
    )
  }
}
object Cross{
  def apply[T](t: T*) = new Cross(t.map(i => List(i) -> i).toList)
}

object CrossModule{
  def autoCast[A](x: Any): A = x.asInstanceOf[A]
  abstract class Implicit[T]{
    def make(v: Any, ctx: Module.Ctx): T
    def crossValues(v: Any): List[Any]
  }
  object Implicit{
    implicit def make[T]: Implicit[T] = macro makeImpl[T]
    def makeImpl[T: c.WeakTypeTag](c: blackbox.Context): c.Expr[Implicit[T]] = {
      import c.universe._
      val tpe = weakTypeOf[T]

      val primaryConstructorArgs =
        tpe.typeSymbol.asClass.primaryConstructor.typeSignature.paramLists.head

      val tree = primaryConstructorArgs match{
        case List(arg) =>
          q"""
            new mill.define.CrossModule.Implicit[$tpe]{
              def make(v: Any, ctx0: mill.define.Module.Ctx) = new $tpe(v.asInstanceOf[${arg.info}]){
                override def ctx = ctx0
              }
              def crossValues(v: Any) = List(v)
            }
          """
        case args =>
          val argTupleValues = for((a, n) <- args.zipWithIndex) yield{
            q"v.asInstanceOf[scala.Product].productElement($n).asInstanceOf[${a.info}]"
          }
          q"""
            new mill.define.CrossModule.Implicit[$tpe]{
              def make(v: Any, ctx0: mill.define.Module.Ctx) = new $tpe(..$argTupleValues){
                override def ctx = ctx0
              }
              def crossValues(v: Any) = List(..$argTupleValues)
            }
          """

      }
      c.Expr[Implicit[T]](tree)
    }
  }
}
class CrossModule[T](cases: Any*)
                    (implicit ci: CrossModule.Implicit[T],
                     ctx: Module.Ctx)
extends Cross[T]({
  for(c <- cases.toList) yield{
    val crossValues = ci.crossValues(c)
    val sub = ci.make(
      c,
      ctx.copy(
        segments0 = Segments(ctx.segments0.value :+ ctx.segment),
        segment = Segment.Cross(crossValues.reverse)
      )
    )
    (crossValues.reverse, sub)
  }
})