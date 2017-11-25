package mill.modules

import java.io.FileOutputStream
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.{JarEntry, JarFile, JarOutputStream}

import ammonite.ops._
import mill.define.Task
import mill.eval.PathRef
import mill.util.Args

import scala.collection.mutable


object Jvm {

  def subprocess(mainClass: String,
                 classPath: Seq[Path]) = {
    import ammonite.ops.ImplicitWd._
    %("java", "-cp", classPath.mkString(":"), mainClass)
  }

  private def createManifest(mainClass: Option[String]) = {
    val m = new java.util.jar.Manifest()
    m.getMainAttributes.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    m.getMainAttributes.putValue( "Created-By", "Scala mill" )
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


  def createAssembly(outputPath: Path,
                     inputPaths: Seq[Path],
                     mainClass: Option[String] = None,
                     prependShellScript: String = ""): Option[Path] = {
    rm(outputPath)

    if(inputPaths.isEmpty) None
    else {
      mkdir(outputPath/up)

      val output = new FileOutputStream(outputPath.toIO)

      // Prepend shell script and make it executable
      if (prependShellScript.nonEmpty) {
        output.write((prependShellScript + "\n").getBytes)
        val perms = java.nio.file.Files.getPosixFilePermissions(outputPath.toNIO)
        perms.add(PosixFilePermission.GROUP_EXECUTE)
        perms.add(PosixFilePermission.OWNER_EXECUTE)
        perms.add(PosixFilePermission.OTHERS_EXECUTE)
        java.nio.file.Files.setPosixFilePermissions(outputPath.toNIO, perms)
      }

      val jar = new JarOutputStream(
        output,
        createManifest(mainClass)
      )

      val seen = mutable.Set("META-INF/MANIFEST.MF")
      try{
        assert(inputPaths.forall(exists(_)))

        for{
          p <- inputPaths

          (file, mapping) <-
            if (p.isFile) {
              val jf = new JarFile(p.toIO)
              import collection.JavaConverters._
              for(entry <- jf.entries().asScala if !entry.isDirectory) yield {
                read.bytes(jf.getInputStream(entry)) -> entry.getName
              }
            }
            else {
              ls.rec(p).iterator
                .filter(_.isFile)
                .map(sub => read.bytes(sub) -> sub.relativeTo(p).toString)
            }
          if !seen(mapping)
        } {
          seen.add(mapping)
          val entry = new JarEntry(mapping.toString)
          jar.putNextEntry(entry)
          jar.write(file)
          jar.closeEntry()
        }
      } finally {
        jar.close()
        output.close()
      }

      Some(outputPath)
    }
  }

  def jarUp(roots: Task[PathRef]*) = new Task[PathRef]{

    val inputs = roots
    def evaluate(args: Args) = {
      createJar(args.dest, args.args.map(_.asInstanceOf[PathRef].path))
      PathRef(args.dest)
    }
  }
}
