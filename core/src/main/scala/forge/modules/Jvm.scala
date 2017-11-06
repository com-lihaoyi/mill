package forge.modules

import java.io.FileOutputStream
import java.util.jar.{JarEntry, JarOutputStream}

import ammonite.ops._
import forge.define.Target
import forge.eval.PathRef
import forge.util.Args


object Jvm {

  def subprocess(mainClass: String,
                 classPath: Seq[Path]) = {
    import ammonite.ops.ImplicitWd._
    %("java", "-cp", classPath.mkString(":"), mainClass)
  }

  private def createManifest(mainClass: Option[String]) = {
    val m = new java.util.jar.Manifest()
    m.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    m.getMainAttributes.putValue( "Created-By", "Scala Forge" )
    mainClass.foreach(
      m.getMainAttributes.put(java.util.jar.Attributes.Name.MAIN_CLASS, _)
    )
    m
  }

  def createJar(outputPath: Path, inputPaths: Seq[Path], mainClass: Option[String] = None): Option[Path] = {
    rm(outputPath)
    if(inputPaths.isEmpty) None
    else {
      mkdir(outputPath/up)

      val jar = new JarOutputStream(
        new FileOutputStream(outputPath.toIO),
        createManifest(mainClass)
      )

      try{
        assert(inputPaths.forall(exists(_)))
        for{
          p <- inputPaths
          (file, mapping) <-
          if (p.isFile) Iterator(p -> empty/p.last)
          else ls.rec(p).filter(_.isFile).map(sub => sub -> sub.relativeTo(p))
        } {
          val entry = new JarEntry(mapping.toString)
          entry.setTime(file.mtime.toMillis)
          jar.putNextEntry(entry)
          jar.write(read.bytes(file))
          jar.closeEntry()
        }
      } finally {
        jar.close()
      }

      Some(outputPath)
    }
  }
  case class jarUp(roots: Target[PathRef]*) extends Target[PathRef]{

    val inputs = roots
    def evaluate(args: Args): PathRef = {
      createJar(args.dest, args.args.map(_.asInstanceOf[PathRef].path))
      PathRef(args.dest)
    }
  }
}
