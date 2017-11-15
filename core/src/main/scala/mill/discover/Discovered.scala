package mill.discover

import mill.define.{Target, Task}
import mill.discover.Router.{EntryPoint, Result}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
sealed trait Info[T, V]
class Discovered[T](val targets: Seq[LabelInfo[T, _]],
                    val mains: Seq[CommandInfo[T, _]],
                    val hierarchy: Hierarchy[T]){
  def apply(t: T) = targets.map{case LabelInfo(a, f, b) => (a, f, b(t)) }
}

case class Hierarchy[T](path: Seq[String], node: T => Any, children: List[Hierarchy[T]])

case class Labelled[T](target: Task[T],
                       format: upickle.default.ReadWriter[T],
                       segments: Seq[String])

case class LabelInfo[T, V](path: Seq[String],
                           format: upickle.default.ReadWriter[V],
                           run: T => Task[V]) extends Info[T, V]

case class CommandInfo[T, V](path: Seq[String],
                             resolve: T => V,
                             entryPoint: EntryPoint[V]) extends Info[T, V]{
  def invoke(target: T, groupedArgs: Seq[(String, Option[String])]): Result[Task[Any]] = {
    entryPoint.invoke(resolve(target),groupedArgs)
  }
}
object CommandInfo{
  def make[T, V](path: Seq[String], resolve: T => V)
                (entryPoint: EntryPoint[V]) = CommandInfo(path, resolve, entryPoint)
}
object Discovered {
  def consistencyCheck[T](base: T, d: Discovered[T]) = {
    val inconsistent = for{
      LabelInfo(path, formatter, targetGen) <- d.targets
      if targetGen(base) ne targetGen(base)
    } yield path
    inconsistent
  }
  def makeTuple[T, V](path: Seq[String], func: T => Task[V])(implicit f: upickle.default.ReadWriter[V]) = {
    LabelInfo(path, f, func)
  }


  def mapping[T: Discovered](t: T): Map[Task[_], Labelled[_]] = {
    implicitly[Discovered[T]].apply(t)
      .map(x => x._3 -> Labelled(x._3.asInstanceOf[Task[Any]], x._2.asInstanceOf[upickle.default.ReadWriter[Any]], x._1))
      .toMap
  }

  implicit def apply[T]: Discovered[T] = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: Context): c.Expr[Discovered[T]] = {
    import c.universe._
    val tpe = c.weakTypeTag[T].tpe
    def rec(segments: List[String],
            t: c.Type): (Seq[(Seq[String], Tree)], Seq[Seq[String]], Tree) = {

      val r = new Router(c)
      val selfMains =
        for(tree <- r.getAllRoutesForClass(t.asInstanceOf[r.c.Type]).asInstanceOf[Seq[c.Tree]])
        yield (segments, tree)

      val items = for {
        m <- t.members.toSeq
        if
          (m.isTerm && (m.asTerm.isGetter || m.asTerm.isLazy)) ||
          m.isModule ||
          (m.isMethod && m.typeSignature.paramLists.isEmpty && m.typeSignature.resultType <:< c.weakTypeOf[Target[_]])
        if !m.name.toString.contains('$')
      } yield {
        val extendedSegments = m.name.toString :: segments
        val self =
          if (m.typeSignature.resultType <:< c.weakTypeOf[Target[_]]) Seq(extendedSegments)
          else Nil

        val (mains, children, hierarchy) = rec(extendedSegments, m.typeSignature)

        val nonEmpty = mains.nonEmpty || children.nonEmpty
        (mains, self ++ children, if (nonEmpty) Some(hierarchy) else None)
      }

      val (mains, targets, childHierarchyOpts) = items.unzip3
      val childHierarchies = childHierarchyOpts.flatMap(_.toSeq).toList
      val hierarchySelector = {
        val base = q"${TermName(c.freshName())}"
        val ident = segments.reverse.foldLeft[Tree](base) { (prefix, name) =>
          q"$prefix.${TermName(name)}"
        }
        q"($base: $tpe) => $ident"
      }


      Tuple3(
        selfMains ++ mains.flatten,
        targets.flatten,
        q"mill.discover.Hierarchy[$tpe](${segments.reverse}, $hierarchySelector, $childHierarchies)"
      )
    }

    val (entryPoints, reversedPaths, hierarchy) = rec(Nil, tpe)

    val result = for(reversedPath <- reversedPaths.toList) yield {
      val base = q"${TermName(c.freshName())}"
      val segments = reversedPath.reverse.toList
      val ident = segments.foldLeft[Tree](base) { (prefix, name) =>
        q"$prefix.${TermName(name)}"
      }

      q"mill.discover.Discovered.makeTuple($segments, ($base: $tpe) => $ident)"
    }

    val nested = for{
      (reversedSegments, entry) <- entryPoints
    } yield {
      val segments = reversedSegments.reverse
      val arg = TermName(c.freshName())
      val select = segments.foldLeft[Tree](Ident(arg)) { (prefix, name) =>
        q"$prefix.${TermName(name)}"
      }
      q"mill.discover.CommandInfo.make(Seq(..$segments), ($arg: $tpe) => $select)($entry)"
    }

    c.Expr[Discovered[T]](q"""
      new _root_.mill.discover.Discovered(
        $result,
        ${nested.toList},
        $hierarchy
      )
    """)
  }
}
