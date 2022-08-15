package mill.api

import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}
import scala.util.Using

import upickle.default.{ReadWriter => RW}

/**
 * A wrapper around `os.Path` that calculates it's hashcode based
 * on the contents of the filesystem underneath it. Used to ensure filesystem
 * changes can bust caches which are keyed off hashcodes.
 */
case class PathRef(path: os.Path, quick: Boolean, sig: Int) {
  override def hashCode(): Int = sig
}

object PathRef {

  /**
   * Create a [[PathRef]] by recursively digesting the content of a given `path`.
   * @param path The digested path.
   * @param quick If `true` the digest is only based to some file attributes (like mtime and size).
   *              If `false` the digest is created of the files content.
   * @return
   */
  def apply(path: os.Path, quick: Boolean = false): PathRef = {
    val sig = {
      val isPosix = path.wrapped.getFileSystem.supportedFileAttributeViews().contains("posix")
      val digest = MessageDigest.getInstance("MD5")
      val digestOut = new DigestOutputStream(DummyOutputStream, digest)
      def updateWithInt(value: Int): Unit = {
        digest.update((value >>> 24).toByte)
        digest.update((value >>> 16).toByte)
        digest.update((value >>> 8).toByte)
        digest.update(value.toByte)
      }
      if (os.exists(path)) {
        for (
          (path, attrs) <-
            os.walk.attrs(path, includeTarget = true, followLinks = true).sortBy(_._1.toString)
        ) {
          digest.update(path.toString.getBytes)
          if (!attrs.isDir) {
            if (isPosix) {
              updateWithInt(os.perms(path, followLinks = false).value)
            }
            if (quick) {
              val value = (attrs.mtime, attrs.size).hashCode()
              updateWithInt(value)
            } else if (jnio.Files.isReadable(path.toNIO)) {
              val is =
                try Some(os.read.inputStream(path))
                catch {
                  case _: jnio.FileSystemException =>
                    // This is known to happen, when we try to digest a socket file.
                    // We ignore the content of this file for now, as we would do,
                    // when the file isn't readable.
                    // See https://github.com/com-lihaoyi/mill/issues/1875
                    None
                }
              is.foreach {
                Using.resource(_) { is =>
                  StreamSupport.stream(is, digestOut)
                }
              }
            }
          }
        }
      }

      java.util.Arrays.hashCode(digest.digest())

    }
    new PathRef(path, quick, sig)
  }

  /**
   * Default JSON formatter for [[PathRef]].
   */
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
        prefix match {
          case "qref" => true
          case "ref" => false
        },
        // Parsing to a long and casting to an int is the only way to make
        // round-trip handling of negative numbers work =(
        java.lang.Long.parseLong(hex, 16).toInt
      )
    }
  )
}
