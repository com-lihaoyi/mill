package mill.discover

import mill.define.Task.Module
import mill.define.{Cross, Target, Task}
import mill.discover.Mirror.LabelledTarget
import mill.discover.Router.{EntryPoint, Result}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context

/**
  * Allows you to implicitly summon up a build [[Mirror]] for arbitrary types
  */
class Discovered[T](val mirror: Mirror[T, T]){
  def targets(obj: T) = Mirror.traverse(obj, mirror) { (h, p) =>
    h.labelled(obj, p)
  }
  def mapping(t: T) = {
    Discovered.Mapping(
      targets(t).map(x => x.target -> x).toMap[Target[Any], LabelledTarget[_]],
      mirror,
      t
    )
  }
}

object Discovered {
  case class Mapping[T](value: Map[Target[Any], LabelledTarget[_]],
                        mirror: Mirror[T, T],
                        base: T)

  def consistencyCheck[T](mapping: Discovered.Mapping[T]) = {
    val d = new Discovered(mapping.mirror)
    val inconsistent = for{
      (t1, t2) <- d.targets(mapping.base).zip(d.targets(mapping.base))
      if t1.target ne t2.target
    } yield t1.segments
    inconsistent
  }


  def make[T]: Discovered[T] = macro applyImpl[T]
  def mapping[T](t: T): Discovered.Mapping[T] = macro mappingImpl[T]
  def tupleLeft[T, V](items: List[(T, V)]) = items.map(_._1)
  def mappingImpl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Discovered.Mapping[T]] = {
    import c.universe._
    c.Expr[Discovered.Mapping[T]](q"${applyImpl[T](c)}.mapping($t)")
  }
  def applyImpl[T: c.WeakTypeTag](c: Context): c.Expr[Discovered[T]] = {

    import c.universe._
    val baseType = c.weakTypeTag[T].tpe
    def rec(segments: List[Option[String]],
            t: c.Type): Tree = {

      val r = new Router(c)

      val targets = for {
        m <- t.members.toList
        if m.isMethod &&
           m.typeSignature.paramLists.isEmpty &&
           m.typeSignature.resultType <:< c.weakTypeOf[Target[_]] &&
           !m.name.toString.contains(' ') &&
           m.isPublic
      } yield {
        val x = Ident(TermName(c.freshName()))
        val t = q"""mill.discover.Mirror.makeTargetPoint(
          ${m.name.toString},
          ($x: ${m.typeSignature.resultType}) => $x.${m.name.toTermName}
        )"""

        c.internal.setPos(t, m.pos)
        t
      }

      val crossChildren =
        if (!(t <:< c.weakTypeOf[Cross[Module]])) q"None"
        else {
          val TypeRef(_, _, Seq(arg)) = t
          val innerMirror = rec(None :: segments, arg)
          q"Some(((c: mill.define.Cross[_]) => mill.discover.Discovered.tupleLeft(c.items), $innerMirror))"
        }
      val childHierarchies = for{
        m <- t.members.toList
        if m.typeSignature.paramLists.isEmpty && m.isPublic
        if (m.typeSignature.finalResultType <:< c.weakTypeOf[Module]) ||
          (m.typeSignature.finalResultType <:< c.weakTypeOf[Cross[Module]])

      } yield {
        val name = m.name.toString.trim
        q"($name, ${rec(Some(name) :: segments, m.typeSignature.finalResultType)})"
      }


      val crossName = q"${TermName(c.freshName())}"
      val hierarchySelector = {
        val base = q"${TermName(c.freshName())}"
        val ident = segments.reverse.zipWithIndex.foldLeft[Tree](base) {
          case (prefix, (Some(name), i)) => q"$prefix.${TermName(name)}"
          case (prefix, (None, i)) => q"$prefix.apply($crossName($i):_*)"
        }
        q"($base: $baseType, $crossName: List[List[Any]]) => $ident.asInstanceOf[$t]"
      }

      val commands =
        r.getAllRoutesForClass(t.asInstanceOf[r.c.Type])
          .asInstanceOf[Seq[c.Tree]]
          .toList

      q"""mill.discover.Mirror[$baseType, $t](
        $hierarchySelector,
        $commands,
        $targets,
        $childHierarchies,
        $crossChildren
      )"""
    }

    val res = q"new _root_.mill.discover.Discovered(${rec(Nil, baseType)})"
//    println(res)
    c.Expr[Discovered[T]](res)
  }
}
