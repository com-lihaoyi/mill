package mill.api

import upickle.default.ReadWriter as RW

import java.nio.file as jnio
import java.security.{DigestOutputStream, MessageDigest}
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn
import scala.language.implicitConversions
import scala.util.DynamicVariable

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
) extends PathUtils {

  def recomputeSig(): Int = PathRef.apply(path, quick).sig
  def validate(): Boolean = recomputeSig() == sig

  /* Hide case class specific copy method. */
  private def copy(
      path: os.Path = path,
      quick: Boolean = quick,
      sig: Int = sig,
      revalidate: PathRef.Revalidate = revalidate
  ): PathRef = PathRef(path, quick, sig, revalidate)

  def withRevalidate(revalidate: PathRef.Revalidate): PathRef = copy(revalidate = revalidate)
  def withRevalidateOnce: PathRef = copy(revalidate = PathRef.Revalidate.Once)

  override def toString: String = {
    val quick = if (this.quick) "qref:" else "ref:"
    val valid = revalidate match {
      case PathRef.Revalidate.Never => "v0:"
      case PathRef.Revalidate.Once => "v1:"
      case PathRef.Revalidate.Always => "vn:"
    }
    val sig = String.format("%08x", this.sig: Integer)
    quick + valid + sig + ":" + serializeEnvVariables(path)
  }
}

object PathRef extends PathUtils {
  implicit def shellable(p: PathRef): os.Shellable = p.path

  /**
   * This class maintains a cache of already validated paths.
   * It is thread-safe and meant to be shared between threads, e.g. in a ThreadLocal.
   */
  class ValidatedPaths() {
    private val map = new ConcurrentHashMap[Int, PathRef]()

    /**
     * Revalidates the given [[PathRef]], if required.
     * It will only revalidate a [[PathRef]] if it's value for [[PathRef.revalidate]] says so and also considers the previously revalidated paths.
     * @throws PathRefValidationException If a the [[PathRef]] needs revalidation which fails
     */
    def revalidateIfNeededOrThrow(pathRef: PathRef): Unit = {
      def mapKey(pr: PathRef): Int = (pr.path, pr.quick, pr.sig).hashCode()
      pathRef.revalidate match {
        case Revalidate.Never => // ok
        case Revalidate.Once if map.contains(mapKey(pathRef)) => // ok
        case Revalidate.Once | Revalidate.Always =>
          val changedSig = PathRef.apply(pathRef.path, pathRef.quick).sig
          if (pathRef.sig != changedSig) {
            throw new PathRefValidationException(pathRef)
          }
          val _ = map.put(mapKey(pathRef), pathRef)
      }
    }
    def clear(): Unit = map.clear()
  }

  private[mill] val validatedPaths: DynamicVariable[ValidatedPaths] =
    new DynamicVariable[ValidatedPaths](new ValidatedPaths())

  class PathRefValidationException(val pathRef: PathRef)
      extends RuntimeException(s"Invalid path signature detected: ${pathRef}")

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
            try {
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
                is.foreach(os.Internals.transfer(_, digestOut))
              }
            } catch {
              case e: java.nio.file.NoSuchFileException =>
              // If file was deleted after we listed the folder but before we operate on it,
              // `os.perms` or `os.read.inputStream` will crash. In that case, just do nothing,
              // so next time we calculate the `PathRef` we'll get a different hash signature
              // (either with the file missing, or with the file present) and invalidate any
              // caches

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
    // env variables serialized in the toString function
    p => p.toString(),
    s => {
      val Array(prefix, valid0, hex, pathString) = s.split(":", 4)

      val path = deserializeEnvVariables(os.Path(pathString))
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
      val pr = PathRef(path, quick, sig, revalidate = validOrig)
      validatedPaths.value.revalidateIfNeededOrThrow(pr)
      pr
    }
  )

  // scalafix:off; we want to hide the unapply method
  @nowarn("msg=unused")
  private def unapply(pathRef: PathRef): Option[(os.Path, Boolean, Int, Revalidate)] = {
    Some((pathRef.path, pathRef.quick, pathRef.sig, pathRef.revalidate))
  }
  // scalalfix:on
}
