package forge

import java.nio.{file => jnio}

trait Target[T]{
  val defCtx: DefCtx

  val inputs: Seq[Target[_]]
  def evaluate(args: Args): T

  def map[V](f: T => V)(implicit defCtx: DefCtx) = {
    Target.Mapped(this, f, defCtx)
  }
  def zip[V](other: Target[V])(implicit defCtx: DefCtx) = {
    Target.Zipped(this, other, defCtx)
  }
  def ~[V, R](other: Target[V])
             (implicit s: Implicits.Sequencer[T, V, R], defCtx: DefCtx): Target[R] = {
    this.zip(other).map(s.apply _ tupled)
  }

}

object Target{
  def noop(inputs: Target[_]*)(implicit defCtx: DefCtx) = NoopTarget(inputs, defCtx)
  case class NoopTarget(inputs: Seq[Target[_]], defCtx: DefCtx) extends Target[Unit]{
    def evaluate(args: Args) = ()
  }
  def traverse[T](source: Seq[Target[T]])(implicit defCtx: DefCtx) = {
    Traverse[T](source, defCtx)
  }
  case class Traverse[T](inputs: Seq[Target[T]], defCtx: DefCtx) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  case class Mapped[T, V](source: Target[T], f: T => V,
                          defCtx: DefCtx) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  case class Zipped[T, V](source1: Target[T],
                          source2: Target[V],
                          defCtx: DefCtx) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(0))
    val inputs = List(source1, source1)
  }

  def path(path: jnio.Path)(implicit defCtx: DefCtx) = Path(path, defCtx)
  case class Path(path: jnio.Path, defCtx: DefCtx) extends Target[jnio.Path]{
    def evaluate(args: Args) = path
    val inputs = Nil
  }
  case class Subprocess(inputs: Seq[Target[_]],
                        command: Args => Seq[String],
                        defCtx: DefCtx) extends Target[Subprocess.Result] {

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
