package hbt

import java.io.FileOutputStream
import java.nio.{file => jnio}
import java.util.jar.JarEntry

import sourcecode.Enclosing

import scala.collection.JavaConverters._
import scala.collection.mutable

object Util{
  case class compileAll(sources: Target[Seq[jnio.Path]])
                       (implicit path: Enclosing) extends Target[jnio.Path]{
    val label = path.value
    val inputs = Seq(sources)
    def evaluate(args: Args): jnio.Path = {

      jnio.Files.createDirectories(args.dest)
      val command =
        Seq("scalac") ++
        args[Seq[jnio.Path]](0).map(_.toString) ++
        Seq("-d", args.dest.toString)

      val result = new java.lang.ProcessBuilder()
        .command(command: _*)
        .start()
        .waitFor()

      args.dest
    }
  }

  def list(root: Target[jnio.Path]): Target[Seq[jnio.Path]] = {
    root.map(jnio.Files.list(_).iterator().asScala.toArray[jnio.Path])
  }
  case class jarUp(roots: Target[jnio.Path]*)(implicit path: Enclosing) extends Target[jnio.Path]{
    val label = path.value
    val inputs = roots
    def evaluate(args: Args): jnio.Path = {

      val output = new java.util.jar.JarOutputStream(new FileOutputStream(args.dest.toFile))
      for{
        root0 <- args.args
        root = root0.asInstanceOf[jnio.Path]

        path <- jnio.Files.walk(root).iterator().asScala
        if jnio.Files.isRegularFile(path)
      }{
        val relative = root.relativize(path)
        output.putNextEntry(new JarEntry(relative.toString))
        output.write(jnio.Files.readAllBytes(path))
      }
      output.close()
      args.dest
    }


  }


}