package forge


import ammonite.ops.{ls, mkdir}
import forge.util.{Args, PathRef}
import play.api.libs.json.{Format, JsValue, Json}

import scala.annotation.compileTimeOnly
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
  class Target0[T](t: T) extends Target[T]{
    lazy val t0 = t
    val inputs = Nil
    def evaluate(args: Args)  = t0
  }
  class Target1[T](t: => Target[T]) extends Target[T]{
    lazy val t0 = t
    lazy val inputs = t0.inputs
    def evaluate(args: Args)  = t0.evaluate(args)
  }
  implicit def toTarget[T](t: T): Target[T] = new Target0(t)
  implicit def apply[T](t: => Target[T]): Target[T] = new Target1(t)
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
