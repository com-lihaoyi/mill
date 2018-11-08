package mill.eval

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, FileVisitor}
import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}

import upickle.default.{ReadWriter => RW}
import mill.util.{DummyOutputStream, IO, JsonFormatters}


/**
  * A wrapper around `os.Path` that calculates it's hashcode based
  * on the contents of the filesystem underneath it. Used to ensure filesystem
  * changes can bust caches which are keyed off hashcodes.
  */
case class PathRef(path: os.Path, quick: Boolean, sig: Int){
  override def hashCode() = sig
}

object PathRef{
  def apply(path: os.Path, quick: Boolean = false) = {
    val sig = {
      val digest = MessageDigest.getInstance("MD5")
      val digestOut = new DigestOutputStream(DummyOutputStream, digest)
      if (os.exists(path)){
        for((path, attrs) <- os.walk.attrs(path, includeTarget = true, followLinks = true)){
          digest.update(path.toString.getBytes)
          if (!attrs.isDir) {
            if (quick){
              val value = (attrs.mtime, attrs.size).hashCode()
              digest.update((value >>> 24).toByte)
              digest.update((value >>> 16).toByte)
              digest.update((value >>> 8).toByte)
              digest.update(value.toByte)
            } else if (jnio.Files.isReadable(path.toNIO)) {
              val is = os.read.inputStream(path)
              IO.stream(is, digestOut)
              is.close()
            }
          }
        }
      }

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
        os.Path(path),
        prefix match{ case "qref" => true case "ref" => false},
        // Parsing to a long and casting to an int is the only way to make
        // round-trip handling of negative numbers work =(
        java.lang.Long.parseLong(hex, 16).toInt
      )
    }
  )
}
