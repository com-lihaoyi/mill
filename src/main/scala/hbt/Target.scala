package hbt

import java.io.InputStreamReader
import java.nio.{file => jnio}

import sourcecode.Enclosing

trait Target[T]{
  val label: String
  val inputs: Seq[Target[_]]
  def evaluate(args: Args): T

  def map[V](f: T => V)(implicit path: Enclosing) = {
    Target.Mapped(this, f, path.value)
  }
  def zip[V](other: Target[V])(implicit path: Enclosing) = {
    Target.Zipped(this, other, path.value)
  }
  def ~[V, R](other: Target[V])
             (implicit s: Implicits.Sequencer[T, V, R]): Target[R] = {
    this.zip(other).map(s.apply _ tupled)
  }

}

object Target{
  def traverse[T](source: Seq[Target[T]])(implicit path: Enclosing) = {
    Traverse[T](source, path.value)
  }
  case class Traverse[T](inputs: Seq[Target[T]], label: String) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i).asInstanceOf[T]
    }

  }
  case class Mapped[T, V](source: Target[T], f: T => V,
                          label: String) extends Target[V]{
    def evaluate(args: Args) = f(args(0))
    val inputs = List(source)
  }
  case class Zipped[T, V](source1: Target[T],
                          source2: Target[V],
                          label: String) extends Target[(T, V)]{
    def evaluate(args: Args) = (args(0), args(0))
    val inputs = List(source1, source1)
  }

  def path(path: jnio.Path)(implicit label: Enclosing) = Path(path, label.value)
  case class Path(path: jnio.Path, label: String) extends Target[jnio.Path]{
    def evaluate(args: Args) = path
    val inputs = Nil
  }
  case class Subprocess(inputs: Seq[Target[_]],
                        command: Args => Seq[String],
                        label: String) extends Target[Subprocess.Result] {

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
