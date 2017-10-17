package hbt
import java.io.FileOutputStream

import collection.JavaConverters._
import java.nio.{file => jnio}

import java.util.jar.JarEntry
import sourcecode.Enclosing
class Args(val args: IndexedSeq[_]){
  def length = args.length
  def apply[T](index: Int): T = {
    if (index >= 0 && index < args.length) args(index).asInstanceOf[T]
    else throw new IndexOutOfBoundsException(s"Index $index outside of range 0 - ${args.length}")
  }
}
sealed trait Target[T]{
  val label: String
  def evaluate(args: Args): T
  val inputs: Seq[Target[_]]

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
    Traverse(source, path.value)
  }
  case class Traverse[T](inputs: Seq[Target[T]], label: String) extends Target[Seq[T]]{
    def evaluate(args: Args) = {
      for (i <- 0 until args.length)
      yield args(i)
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
object Main{
  def compileAll(sources: Target[Seq[jnio.Path]])
                (implicit path: Enclosing): Target[jnio.Path] = {
    for(sources0 <- sources) yield {
      val output = jnio.Paths.get(path.value)
      jnio.Files.createDirectories(output)
      val command =
        Seq("scalac") ++
        sources0.map(_.toString) ++
        Seq("-d", path.value)



      new java.lang.ProcessBuilder()
        .command(command: _*)
        .start()
        .waitFor()

      output
    }
  }

  def list(root: Target[jnio.Path]): Target[Seq[jnio.Path]] = {
    root.map(jnio.Files.list(_).iterator().asScala.toArray[jnio.Path])
  }
  def jarUp(roots: Target[jnio.Path]*)(implicit path: Enclosing): Target[jnio.Path] = {
    for(rootsValue <- Target.traverse(roots)) yield {
      val output = new java.util.jar.JarOutputStream(new FileOutputStream(path.value))
      for{
        root <- rootsValue
        path <- jnio.Files.list(root).iterator().asScala
      }{
        val relative = root.relativize(path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(jnio.Files.readAllBytes(path))
      }
      jnio.Paths.get(path.value)
    }
  }
  def main(args: Array[String]): Unit = {
    val sourceRoot = Target.Path(jnio.Paths.get("test/src"), "sourceRoot")
    val resourceRoot = Target.Path(jnio.Paths.get("test/resources"), "resourceRoot")
    val allSources = list(sourceRoot)
    val classFiles = compileAll(allSources)
    val jar = jarUp(resourceRoot, classFiles)
  }

  def evaluateTargetGraph[T](t: Target[T]): T = {
    ???
//    val evaluated = collection.mutable.Map.empty[Target[_], Any]
//    val forwardEdges
  }
}