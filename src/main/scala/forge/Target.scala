package forge


import ammonite.ops.{ls, mkdir}
import forge.util.{Args, PathRef}
import play.api.libs.json.{Format, JsValue, Json}
abstract class Target[T](implicit formatter: Format[T]) extends Target.Ops[T]{
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

}

object Target{
  abstract class Ops[T](implicit val formatter: Format[T]){ this: Target[T] =>
    def evaluateAndWrite(args: Args): (T, JsValue) = {
      val res = evaluate(args)
      val str = formatter.writes(res)
      (res, str)
    }
    def map[V: Format](f: T => V) = {
      new Target.Mapped(this, f)
    }
    def zip[V: Format](other: Target[V]) = {
      new Target.Zipped(this, other)
    }
  }
  def test(inputs: Target[Int]*) = {
    new Test(inputs, pure = inputs.nonEmpty)
  }

  /**
    * A dummy target that takes any number of inputs, and whose output can be
    * controlled externally, so you can construct arbitrary dataflow graphs and
    * test how changes propagate.
    */
  class Test(val inputs: Seq[Target[Int]],
             val pure: Boolean) extends Target[Int]{
    var counter = 0
    def evaluate(args: Args) = {
      counter + args.args.map(_.asInstanceOf[Int]).sum
    }

    override def sideHash = counter
  }
  def traverse[T: Format](source: Seq[Target[T]]) = {
    new Traverse[T](source)
  }
  class Traverse[T: Format](val inputs: Seq[Target[T]]) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[T, V: Format](source: Target[T], f: T => V) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  class Zipped[T: Format, V: Format](source1: Target[T],
                                     source2: Target[V]) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(0))
    val inputs = List(source1, source1)
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
