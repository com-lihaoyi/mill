package mill.eval

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, FileVisitor}
import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}

import upickle.default.{ReadWriter => RW}
import ammonite.ops.Path
import mill.util.{DummyOutputStream, IO, JsonFormatters}


/**
  * A wrapper around `ammonite.ops.Path` that calculates it's hashcode based
  * on the contents of the filesystem underneath it. Used to ensure filesystem
  * changes can bust caches which are keyed off hashcodes.
  */
case class PathRef(path: ammonite.ops.Path, quick: Boolean, sig: Int){
  override def hashCode() = sig
}

object PathRef{
  def apply(path: ammonite.ops.Path, quick: Boolean = false) = {
    val sig = {
      val digest = MessageDigest.getInstance("MD5")
      val digestOut = new DigestOutputStream(DummyOutputStream, digest)
      jnio.Files.walkFileTree(
        path.toNIO,
        java.util.EnumSet.of(jnio.FileVisitOption.FOLLOW_LINKS),
        Integer.MAX_VALUE,
        new FileVisitor[jnio.Path] {
          def preVisitDirectory(dir: jnio.Path, attrs: BasicFileAttributes) = {
            digest.update(dir.toAbsolutePath.toString.getBytes)
            FileVisitResult.CONTINUE
          }

          def visitFile(file: jnio.Path, attrs: BasicFileAttributes) = {
            digest.update(file.toAbsolutePath.toString.getBytes)
            if (quick){
              val value = (path.mtime.toMillis, path.size).hashCode()
              digest.update((value >>> 24).toByte)
              digest.update((value >>> 16).toByte)
              digest.update((value >>> 8).toByte)
              digest.update(value.toByte)
            }else {
              val is = jnio.Files.newInputStream(file)
              IO.stream(is, digestOut)
              is.close()
            }
            FileVisitResult.CONTINUE
          }

          def visitFileFailed(file: jnio.Path, exc: IOException) = FileVisitResult.CONTINUE
          def postVisitDirectory(dir: jnio.Path, exc: IOException) = FileVisitResult.CONTINUE
        }
      )

      java.util.Arrays.hashCode(digest.digest())

    }
    new PathRef(path, quick, sig)
  }

  implicit def jsonFormatter: RW[PathRef] = upickle.default.readwriter[String].bimap[PathRef](
    p => {
      (if (p.quick) "qref" else "ref") + ":" +
      String.format("%08x", p.sig: Integer) + ":" +
      p.path.toString()
    },
    s => {
      val Array(prefix, hex, path) = s.split(":", 3)
      PathRef(
        Path(path),
        prefix match{ case "qref" => true case "ref" => false},
        // Parsing to a long and casting to an int is the only way to make
        // round-trip handling of negative numbers work =(
        java.lang.Long.parseLong(hex, 16).toInt
      )
    }
  )
}
