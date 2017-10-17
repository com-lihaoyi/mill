package hbt
import java.io.FileOutputStream

import collection.JavaConverters._
import java.nio.{file => jnio}

import java.util.jar.JarEntry
import sourcecode.Enclosing
sealed trait Target[T]{
  def label: String
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
  case class Traverse[T](source: Seq[Target[T]], label: String) extends Target[Seq[T]]
  case class Mapped[T, V](source: Target[T], f: T => V,
                          label: String) extends Target[V]
  case class Zipped[T, V](source: Target[T],
                          source2: Target[V],
                          label: String) extends Target[(T, V)]
  case class Path(path: jnio.Path, label: String) extends Target[jnio.Path]
  case class Command(inputs: Seq[Target[jnio.Path]],
                     output: Seq[Target[jnio.Path]],
                     label: String) extends Target[Command.Result]
  object Command{
    case class Result(stdout: String,
                      stderr: String,
                      writtenFiles: Seq[jnio.Path])
  }
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
}