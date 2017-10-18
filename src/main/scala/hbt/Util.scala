package hbt

import java.io.FileOutputStream
import java.nio.{file => jnio}
import java.util.jar.JarEntry

import sourcecode.Enclosing

import scala.collection.JavaConverters._
import scala.collection.mutable

object Util{
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


}