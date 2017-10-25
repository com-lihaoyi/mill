package forge

import java.nio.{file => jnio}
trait TargetOps[T]{ this: Target[T] =>
  val defCtx: DefCtx
  def map[V](f: T => V)(implicit defCtx: DefCtx) = {
    new Target.Mapped(this, f, defCtx)
  }
  def zip[V](other: Target[V])(implicit defCtx: DefCtx) = {
    new Target.Zipped(this, other, defCtx)
  }
  def ~[V, R](other: Target[V])
             (implicit s: Implicits.Sequencer[T, V, R], defCtx: DefCtx): Target[R] = {
    this.zip(other).map(s.apply _ tupled)
  }

  override def toString = this.getClass.getName + "@" + defCtx.label
}
trait Target[T] extends TargetOps[T]{
  /**
    * Where in the Scala codebase was this target defined?
    */
  val defCtx: DefCtx
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
    * anyway? e.g. if the files this target represents on disk changed
    */
  def dirty: Boolean = false

}

object Target{
  def test(inputs: Target[Int]*)(implicit defCtx: DefCtx) = new Test(inputs, defCtx)

  /**
    * A dummy target that takes any number of inputs, and whose output can be
    * controlled externally, so you can construct arbitrary dataflow graphs and
    * test how changes propagate.
    */
  class Test(val inputs: Seq[Target[Int]], val defCtx: DefCtx) extends Target[Int]{
    var counter = 0
    var lastCounter = counter
    def evaluate(args: Args) = {
      lastCounter = counter
      counter + args.args.map(_.asInstanceOf[Int]).sum
    }
    override def dirty = lastCounter != counter
  }
  def traverse[T](source: Seq[Target[T]])(implicit defCtx: DefCtx) = {
    new Traverse[T](source, defCtx)
  }
  class Traverse[T](val inputs: Seq[Target[T]], val defCtx: DefCtx) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  class Mapped[T, V](source: Target[T], f: T => V,
                     val defCtx: DefCtx) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  class Zipped[T, V](source1: Target[T],
                     source2: Target[V],
                     val defCtx: DefCtx) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(0))
    val inputs = List(source1, source1)
  }

  def path(path: jnio.Path)(implicit defCtx: DefCtx) = new Path(path, defCtx)
  class Path(path: jnio.Path, val defCtx: DefCtx) extends Target[jnio.Path]{
    def evaluate(args: Args) = path
    val inputs = Nil
  }
  class Subprocess(val inputs: Seq[Target[_]],
                   command: Args => Seq[String],
                   val defCtx: DefCtx) extends Target[Subprocess.Result] {

    def evaluate(args: Args) = {
      jnio.Files.createDirectories(args.dest)
      import ammonite.ops._
      implicit val path = ammonite.ops.Path(args.dest, pwd)
      val output = %%(command(args))
      assert(output.exitCode == 0)
      Subprocess.Result(output, args.dest)
    }
  }
  object Subprocess{
    case class Result(result: ammonite.ops.CommandResult, dest: jnio.Path)
  }
}
