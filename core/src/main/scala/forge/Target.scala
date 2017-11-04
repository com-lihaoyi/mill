package forge


import ammonite.ops.{ls, mkdir}
import forge.util.{Args, PathRef}
import play.api.libs.json.{Format, JsValue, Json}

import scala.annotation.compileTimeOnly
import language.experimental.macros
import reflect.macros.blackbox.Context
import scala.collection.mutable

abstract class Target[T] extends Target.Ops[T]{
  /**
    * What other Targets does this Target depend on?
    */
  val inputs: Seq[Target[_]]

  /**
    * Evaluate this target
    */
  def evaluate(args: Args): T

  /**
    * Even if this target's inputs did not change, does it need to re-evaluate
    * anyway?
    */
  def sideHash: Int = 0

  @compileTimeOnly("Target#apply() can only be used with a T{...} block")
  def apply(): T = ???
}

object Target{
  trait Cacher{
    private[this] val cacherLazyMap = mutable.Map.empty[sourcecode.Enclosing, Target[_]]
    protected[this] def T[T](t: T): Target[T] = macro impl[T]
    protected[this] def T[T](t: => Target[T])(implicit c: sourcecode.Enclosing): Target[T] = {
      cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[Target[T]]
    }
  }
  class Target0[T](t: T) extends Target[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: Args)  = t0
  }
  def apply[T](t: Target[T]): Target[T] = t
  def apply[T](t: T): Target[T] = macro impl[T]
  def impl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Target[T]] = {
    import c.universe._
    val bound = collection.mutable.Buffer.empty[(c.Tree, Symbol)]
    val OptionGet = c.universe.typeOf[Target[_]].member(TermName("apply"))
    object transformer extends c.universe.Transformer {
      // Derived from @olafurpg's
      // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608
      override def transform(tree: c.Tree): c.Tree = tree match {
        case t @ q"$fun.apply()" if t.symbol == OptionGet =>
          val tempName = c.freshName(TermName("tmp"))
          val tempSym = c.internal.newTermSymbol(c.internal.enclosingOwner, tempName)
          c.internal.setInfo(tempSym, t.tpe)
          val tempIdent = Ident(tempSym)
          c.internal.setType(tempIdent, t.tpe)
          bound.append((fun, tempSym))
          tempIdent
        case _ => super.transform(tree)
      }
    }
    val transformed = transformer.transform(t.tree)
    val (exprs, symbols) = bound.unzip

    val bindings = symbols.map(c.internal.valDef(_))

    val newTargetTree = q"forge.zipMap(..$exprs){ (..$bindings) => $transformed }"

    val embedded =
      if (!(c.prefix.tree.tpe <:< typeOf[Cacher])) newTargetTree
      else q"${c.prefix}.T($newTargetTree)"


    c.Expr[Target[T]](embedded)
  }

  abstract class Ops[T]{ this: Target[T] =>
    def map[V](f: T => V) = new Target.Mapped(this, f)

    def filter(f: T => Boolean) = this
    def withFilter(f: T => Boolean) = this
    def zip[V](other: Target[V]) = new Target.Zipped(this, other)

  }

  def traverse[T](source: Seq[Target[T]]) = {
    new Traverse[T](source)
  }
  class Traverse[T](val inputs: Seq[Target[T]]) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[T, V](source: Target[T], f: T => V) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  class Zipped[T, V](source1: Target[T],
                                     source2: Target[V]) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(1))
    val inputs = List(source1, source2)
  }

  def path(path: ammonite.ops.Path) = new Path(path)
  class Path(path: ammonite.ops.Path) extends Target[PathRef]{
    def handle = PathRef(path)
    def evaluate(args: Args) = handle
    override def sideHash = handle.hashCode()
    val inputs = Nil
  }

  class Subprocess(val inputs: Seq[Target[_]],
                   command: Args => Seq[String]) extends Target[Subprocess.Result] {

    def evaluate(args: Args) = {
      mkdir(args.dest)
      import ammonite.ops._
      implicit val path = ammonite.ops.Path(args.dest, pwd)
      val toTarget = () // Shadow the implicit conversion :/
      val output = %%(command(args))
      assert(output.exitCode == 0)
      Subprocess.Result(output, PathRef(args.dest))
    }
  }
  object Subprocess{
    case class Result(result: ammonite.ops.CommandResult, dest: PathRef)
    object Result{
      implicit val tsFormat: Format[Target.Subprocess.Result] = Json.format
    }
  }
}
