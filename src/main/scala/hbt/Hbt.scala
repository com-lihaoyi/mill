package hbt

import java.nio.{file => jnio}

import sourcecode.Enclosing

import scala.collection.mutable
class Args(val args: IndexedSeq[_], val dest: jnio.Path){
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
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
//  case class Command(inputs: Seq[Target[jnio.Path]],
//                     output: Seq[Target[jnio.Path]],
//                     label: String) extends Target[Command.Result]
//  object Command{
//    case class Result(stdout: String,
//                      stderr: String,
//                      writtenFiles: Seq[jnio.Path])
//  }
}
object Hbt{


  def evaluateTargetGraph[T](t: Target[T])(implicit enclosing: Enclosing): T = {
    val targetPaths = mutable.Map.empty[Target[_], List[String]]
    def rec(t: Target[_], path: List[String]): Unit = {
      if (targetPaths.contains(t)) () // do nothing
      else {
        val currentPath =
          if (!t.label.startsWith(enclosing.value)) path.reverse
          else t.label.stripPrefix(enclosing.value).drop(1).split('.').toList

        targetPaths(t) = currentPath
        t.inputs.zipWithIndex.foreach{case (c, i) => rec(c, i.toString :: currentPath)}
      }
    }
    rec(t, Nil)
    val targets = targetPaths.keys.toIndexedSeq
    val targetIndices = targets.zipWithIndex.toMap

    val numberedEdges =
      for(i <- targets.indices)
      yield targets(i).inputs.map(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)

    val results = mutable.Map.empty[Target[_], Any]
    for (cluster <- sortedClusters){
      val Seq(singletonIndex) = cluster
      val target = targets(singletonIndex)
      val inputResults = target.inputs.map(results)
      val targetDestPath = jnio.Paths.get(targetPaths(target).mkString("/"))
      import collection.JavaConverters._
      if (jnio.Files.exists(targetDestPath)){
        jnio.Files.walk(targetDestPath).iterator()
          .asScala
          .toArray
          .reverseIterator
          .map(jnio.Files.deleteIfExists)
      }

      results(target) = target.evaluate(
        new Args(inputResults.toIndexedSeq, targetDestPath)
      )
    }
    results(t).asInstanceOf[T]
  }
}
