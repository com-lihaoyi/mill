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
    protected[this] def cachedTarget[T](t: => Target[T])
                                      (implicit c: sourcecode.Enclosing): Target[T] = synchronized{
      cacherLazyMap.getOrElseUpdate(c, t).asInstanceOf[Target[T]]
    }
  }
  class Target0[T](t: T) extends Target[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: Args)  = t0
  }
  def apply[T](t: Target[T]): Target[T] = macro impl0[T]
  def apply[T](t: T): Target[T] = macro impl[T]
  def impl0[T: c.WeakTypeTag](c: Context)(t: c.Expr[Target[T]]): c.Expr[Target[T]] = {
    wrapCached(c)(t.tree)
  }
  def impl[T: c.WeakTypeTag](c: Context)(t: c.Expr[T]): c.Expr[Target[T]] = {
    import c.universe._
    def rec(t: Tree): Iterator[c.Tree] = Iterator(t) ++ t.children.flatMap(rec(_))
    val bound = collection.mutable.Buffer.empty[(c.Tree, Symbol)]
    val targetApplySym = c.universe.typeOf[Target[_]].member(TermName("apply"))
    // Derived from @olafurpg's
    // https://gist.github.com/olafurpg/596d62f87bf3360a29488b725fbc7608

    val (startPos, endPos) = rec(t.tree)
      .map(t => (t.pos.start, t.pos.end))
      .reduce[(Int, Int)]{ case ((s1, e1), (s2, e2)) => (math.min(s1, s2), math.max(e1, e2))}

    val macroSource = t.tree.pos.source
    val transformed = c.internal.typingTransform(t.tree) {
      case (t @ q"$fun.apply()", api) if t.symbol == targetApplySym =>

        val used = rec(t)
        val banned = used.filter(x =>
          x.symbol.pos.source == macroSource &&
          x.symbol.pos.start >= startPos &&
          x.symbol.pos.end <= endPos
        )
        if (banned.hasNext){
          val banned0 = banned.next()
          c.abort(
            banned0.pos,
            "Target#apply() call cannot use `" + banned0.symbol + "` defined within the T{...} block"
          )
        }
        val tempName = c.freshName(TermName("tmp"))
        val tempSym = c.internal.newTermSymbol(api.currentOwner, tempName)
        c.internal.setInfo(tempSym, t.tpe)
        val tempIdent = Ident(tempSym)
        c.internal.setType(tempIdent, t.tpe)
        bound.append((fun, tempSym))
        tempIdent
      case (t, api) => api.default(t)
    }

    val (exprs, symbols) = bound.unzip

    val bindings = symbols.map(c.internal.valDef(_))

    wrapCached(c)(q"forge.zipMap(..$exprs){ (..$bindings) => $transformed }")
  }
  def wrapCached[T](c: Context)(t: c.Tree) = {
    import c.universe._
    val owner = c.internal.enclosingOwner
    val ownerIsCacherClass = owner.owner.isClass && owner.owner.asClass.baseClasses.exists(_.fullName == "forge.Target.Cacher")

    if (ownerIsCacherClass && !owner.isMethod){
      c.abort(
        c.enclosingPosition,
        "T{} members defined in a Cacher class/trait/object body must be defs"
      )
    }else{
      val embedded =
        if (!ownerIsCacherClass) t
        else q"this.cachedTarget($t)"

      c.Expr[Target[T]](embedded)
    }
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
