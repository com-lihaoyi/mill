package mill.api

import java.nio.{file => jnio}
import java.security.{DigestOutputStream, MessageDigest}
import scala.util.chaining.scalaUtilChainingOps
import scala.util.Using
import upickle.default.{ReadWriter => RW}


/**
 * A wrapper around `os.Path` that calculates it's hashcode based
 * on the contents of the filesystem underneath it. Used to ensure filesystem
 * changes can bust caches which are keyed off hashcodes.
 */
case class PathRef private (
    path: os.Path,
    quick: Boolean,
    sig: Int,
    revalidate: PathRef.Revalidate
) {

  def validate(): Boolean = PathRef.apply(path, quick).sig == sig

  /* Hide case class specific copy method. */
  private def copy(
      path: os.Path = path,
      quick: Boolean = quick,
      sig: Int = sig,
      revalidate: PathRef.Revalidate = revalidate
  ): PathRef = PathRef(path, quick, sig, revalidate)

  def withRevalidate(revalidate: PathRef.Revalidate): PathRef = copy(revalidate = revalidate)
  def withRevalidateOnce: PathRef = copy(revalidate = PathRef.Revalidate.Once)

  override def toString: String =
    getClass().getSimpleName() + "(path=" + path + ",quick=" + quick + ",sig=" + sig + ")"
}

object PathRef {

  class PathRefValidationException(val pathRef: PathRef)
      extends RuntimeException(s"Invalid path signature detected: ${pathRef.path}")

  sealed trait Revalidate
  object Revalidate {
    case object Never extends Revalidate
    case object Once extends Revalidate
    case object Always extends Revalidate
  }

  def apply(path: os.Path, quick: Boolean, sig: Int, revalidate: Revalidate): PathRef =
    new PathRef(path, quick, sig, revalidate)

  /**
   * Create a [[PathRef]] by recursively digesting the content of a given `path`.
   *
   * @param path The digested path.
   * @param quick If `true` the digest is only based to some file attributes (like mtime and size).
   *              If `false` the digest is created of the files content.
   * @return
   */
  def apply(
      path: os.Path,
      quick: Boolean = false,
      revalidate: Revalidate = Revalidate.Never
  ): PathRef = {
    val basePath = path

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
          val sub = path.subRelativeTo(basePath)
          digest.update(sub.toString().getBytes())
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

    new PathRef(path, quick, sig, revalidate)
  }

  /**
   * Default JSON formatter for [[PathRef]].
   */
  implicit def jsonFormatter: RW[PathRef] = upickle.default.readwriter[String].bimap[PathRef](
    p => {
      val quick = if (p.quick) "qref:" else "ref:"
      val valid = p.revalidate match {
        case Revalidate.Never => "v0:"
        case Revalidate.Once => "v1:"
        case Revalidate.Always => "vn:"
      }
      val sig = String.format("%08x", p.sig: Integer)
      quick + valid + sig + ":" + p.path.toString()
    },
    s => {
      val Array(prefix, valid0, hex, pathString) = s.split(":", 4)

      val path = os.Path(pathString)
      val quick = prefix match {
        case "qref" => true
        case "ref" => false
      }
      val validOrig = valid0 match {
        case "v0" => Revalidate.Never
        case "v1" => Revalidate.Once
        case "vn" => Revalidate.Always
      }
      // Parsing to a long and casting to an int is the only way to make
      // round-trip handling of negative numbers work =(
      val sig = java.lang.Long.parseLong(hex, 16).toInt

      // validate if required
      if (validOrig != Revalidate.Never) {
        val sig = PathRef.apply(path, quick).sig
        throw new PathRefValidationException(PathRef(path, quick, sig, validOrig))
      }

      // Update revalidation
      val validNew = validOrig.pipe {
        case Revalidate.Once => Revalidate.Never
        case x => x
      }

      PathRef(path, quick, sig, validNew)
    }
  )

  /* Hide case class generated unapply method. */
  private def unapply(pathRef: PathRef): Option[(os.Path, Boolean, Int, Revalidate)] =
    Some((pathRef.path, pathRef.quick, pathRef.sig, pathRef.revalidate))
}
