package hbt
import java.io.FileOutputStream

import collection.JavaConverters._
import java.nio.file.{Path => JPath}
import java.util.jar.JarEntry
import sourcecode.Enclosing
sealed trait Target[T]{
  def path: String
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
  case class Traverse[T](source: Seq[Target[T]], path: String) extends Target[Seq[T]]
  case class Mapped[T, V](source: Target[T], f: T => V,
                          path: String) extends Target[V]
  case class Zipped[T, V](source: Target[T],
                          source2: Target[V],
                          path: String) extends Target[(T, V)]
  case class Path(path: String) extends Target[JPath]
  case class Command(inputs: Seq[Target[JPath]],
                     output: Seq[Target[JPath]],
                     path: String) extends Target[Command.Result]
  object Command{
    case class Result(stdout: String,
                      stderr: String,
                      writtenFiles: Seq[JPath])
  }
}
object Main{
  def compileAll(sources: Target[Seq[JPath]])
                (implicit path: Enclosing): Target[JPath] = {
    for(sources0 <- sources) yield {
      val output = java.nio.file.Paths.get(path.value)
      java.nio.file.Files.createDirectories(output)
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

  def list(root: Target[JPath]): Target[Seq[JPath]] = {
    root.map(java.nio.file.Files.list(_).iterator().asScala.toArray[JPath])
  }
  def jarUp(roots: Target[JPath]*)(implicit path: Enclosing): Target[JPath] = {
    for(rootsValue <- Target.traverse(roots)) yield {
      val output = new java.util.jar.JarOutputStream(new FileOutputStream(path.value))
      for{
        root <- rootsValue
        path <- java.nio.file.Files.list(root).iterator().asScala
      }{
        val relative = root.relativize(path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(java.nio.file.Files.readAllBytes(path))
      }
      java.nio.file.Paths.get(path.value)
    }
  }
  def main(args: Array[String]): Unit = {
    val sourceRoot: Target[JPath] = ???
    val resourceRoot: Target[JPath] = ???
    val classFiles: Target[JPath] = compileAll(list(sourceRoot))
    val jar: Target[JPath] = jarUp(resourceRoot, classFiles)
  }
}