package forge


import scala.annotation.compileTimeOnly
import scala.language.experimental.macros
import scala.reflect.macros.blackbox._


final case class DefCtx(baseLabel: String, anonId: Option[Int]){
  def label = baseLabel + anonId.getOrElse("")
}
object DefCtx{
  @compileTimeOnly("A DefCtx can only be provided directly within a T{} macro")
  implicit def dummy: DefCtx with Int = ???
}

object T{
  def apply[T](expr: T): T = macro applyImpl[T]

  def applyImpl[T: c.WeakTypeTag](c: Context)(expr: c.Expr[T]): c.Expr[T] = {
    import c.universe._
    var count = 0
    object transformer extends c.universe.Transformer {
      override def transform(tree: c.Tree): c.Tree = {
        if (tree.toString.startsWith("forge.") && tree.toString.endsWith(".DefCtx.dummy")) {
          count += 1
          c.typecheck(q"forge.DefCtx(sourcecode.Enclosing(), Some($count))")
        }else tree match{
          case Apply(fun, args) =>
            val extendedParams = fun.tpe.paramLists.head.padTo(
              args.length,
              fun.tpe.paramLists.head.lastOption.getOrElse(null)
            )
            val newArgs =
              for((sym, tree) <- extendedParams.zip(args))
              yield {
                if (sym.asTerm.isByNameParam) tree
                else transform(tree)
              }
            treeCopy.Apply(tree, transform(fun), newArgs)

          case t: DefDef => t
          case t: ClassDef => t
          case t: Function => t
          case t: LabelDef => t
          case t => super.transform(t)
        }

      }
    }


    def transformTerminal(tree: c.Tree): c.Tree = tree match{
      case Block(stats, returnExpr) =>
        treeCopy.Block(
          tree,
          stats.map(transformer.transform(_)),
          transformTerminal(returnExpr)
        )

      case Apply(fun, args) =>
        var isTransformed = false
        val newArgs = for(x <- args) yield {
          if (x.toString.startsWith("forge.") && x.toString.endsWith(".DefCtx.dummy")) {
            isTransformed = true
            c.typecheck(q"forge.DefCtx(sourcecode.Enclosing(), None)")
          }else transformer.transform(x)
        }

        assert(isTransformed)
        treeCopy.Apply(tree, transformer.transform(fun), newArgs)

      case _ => ???
    }

    val transformed = transformTerminal(expr.tree)
    c.Expr[T](transformed)
  }
}