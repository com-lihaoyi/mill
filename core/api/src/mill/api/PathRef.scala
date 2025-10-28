package mill.api

import mill.api.DummyOutputStream
import mill.api.daemon.internal.PathRefApi
import mill.constants.PathVars
import upickle.ReadWriter as RW

import java.nio.file as jnio
import java.security.{DigestOutputStream, MessageDigest}
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn
import scala.language.implicitConversions
import scala.util.DynamicVariable
import scala.util.hashing.MurmurHash3

/**
 * A wrapper around `os.Path` that calculates it's hashcode based
 * on the contents of the filesystem underneath it. Used to ensure filesystem
 * changes can bust caches which are keyed off hashcodes.
 */
case class PathRef private[mill] (
    path: os.Path,
    quick: Boolean,
    sig: Int,
    revalidate: PathRef.Revalidate
) extends PathRefApi {
  private[mill] def javaPath = path.toNIO

  private[mill] val mappedPath: String = PathRef.encodeKnownRootsInPath(path)

  /**
   * Apply the current contextual path mapping to this PathRef.
   * Updates [[mappedPath]] but does not recalculate the sig`.
   */
  def remap: PathRef = PathRef(path, quick, sig, revalidate)

  def recomputeSig(): Int = PathRef.apply(path, quick).sig
  def validate(): Boolean = recomputeSig() == sig

  /* Hide case class specific copy method. */
  private def copy(
      path: os.Path = path,
      quick: Boolean = quick,
      sig: Int = sig,
      revalidate: PathRef.Revalidate
  ): PathRef = PathRef(path, quick, sig, revalidate)

  def withRevalidate(revalidate: PathRef.Revalidate): PathRef = copy(revalidate = revalidate)
  def withRevalidateOnce: PathRef = copy(revalidate = PathRef.Revalidate.Once)

  private def toStringPrefix: String = {
    val quick = if (this.quick) "qref:" else "ref:"

    val valid = revalidate match {
      case PathRef.Revalidate.Never => "v0:"
      case PathRef.Revalidate.Once => "v1:"
      case PathRef.Revalidate.Always => "vn:"
    }
    val sig = String.format("%08x", this.sig: Integer)
    s"${quick}${valid}${sig}:"
  }

  override def toString: String = {
    toStringPrefix + path.toString()
  }

  // Instead of using `path` we need to use `mappedPath` to make the hashcode stable as cache key
  override def hashCode(): Int = {
    var h = MurmurHash3.productSeed
    h = MurmurHash3.mix(h, "PathRef".hashCode)
    h = MurmurHash3.mix(h, mappedPath.hashCode)
    h = MurmurHash3.mix(h, quick.##)
    h = MurmurHash3.mix(h, sig.##)
    h = MurmurHash3.mix(h, revalidate.##)
    MurmurHash3.finalizeHash(h, 4)
  }

}

object PathRef {
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

  private[mill] val serializedPaths: DynamicVariable[List[PathRef]] =
    new DynamicVariable(null)

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
              case _: java.nio.file.NoSuchFileException =>
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

  private[mill] def withSerializedPaths[T](block: => T): (T, Seq[PathRef]) = {
    serializedPaths.value = Nil
    try {
      val res = block
      (res, serializedPaths.value)
    } finally serializedPaths.value = null
  }
  private def storeSerializedPaths(pr: PathRef) = {
    if (serializedPaths.value != null) {
      serializedPaths.synchronized {
        serializedPaths.value = pr :: serializedPaths.value
      }
    }
  }

  private[api] type MappedRoots = Seq[(key: String, path: os.Path)]

  object mappedRoots {
    private[PathRef] val rootMapping: DynamicVariable[MappedRoots] = DynamicVariable(Seq())

    def get: MappedRoots = rootMapping.value

    def toMap: Map[String, os.Path] = get.map(m => (m.key, m.path)).toMap

    def withMillDefaults[T](
        outPath: os.Path,
        workspacePath: os.Path = BuildCtx.workspaceRoot,
        homePath: os.Path = os.home
    )(thunk: => T): T = withMapping(
      Seq(
        ("MILL_OUT", outPath),
        ("WORKSPACE", workspacePath),
        // TODO: add coursier here
        ("HOME", homePath)
      )
    )(thunk)

    def withMapping[T](mapping: MappedRoots)(thunk: => T): T = withMapping(_ => mapping)(thunk)

    def withMapping[T](mapping: MappedRoots => MappedRoots)(thunk: => T): T = {
      val newMapping = mapping(rootMapping.value)
      var seenKeys = Set[String]()
      var seenPaths = Set[os.Path]()
      newMapping.foreach { case m =>
        require(!m.key.startsWith("$"), "Key must not start with a `$`.")
        require(m.key != PathVars.ROOT, s"Invalid key, '${PathVars.ROOT}' is a reserved key name.")
        require(
          !seenKeys.contains(m.key),
          s"Key must be unique, but '${m.key}' was given multiple times."
        )
        require(
          !seenPaths.contains(m.path),
          s"Paths must be unique, but '${m.path}' was given multiple times."
        )
        seenKeys += m.key
        seenPaths += m.path
      }
      rootMapping.withValue(newMapping)(thunk)
    }
  }

  private[api] def encodeKnownRootsInPath(p: os.Path): String = {
    // TODO: Do we need to check for '$' and mask it ?
    mappedRoots.get.collectFirst {
      case rep if p.startsWith(rep.path) =>
        s"$$${rep.key}${
            if (p != rep.path) {
              s"/${p.subRelativeTo(rep.path).toString()}"
            } else ""
          }"
    }.getOrElse(p.toString)
  }

  private[api] def decodeKnownRootsInPath(encoded: String): String = {
    if (encoded.startsWith("$")) {
      val offset = 1 // "$".length
      mappedRoots.get.collectFirst {
        case mapping if encoded.startsWith(mapping.key, offset) =>
          s"${mapping.path.toString}${encoded.substring(mapping.key.length + offset)}"
      }.getOrElse(encoded)
    } else {
      encoded
    }
  }

  /**
   * Default JSON formatter for [[PathRef]].
   */
  implicit def jsonFormatter: RW[PathRef] = upickle.readwriter[String].bimap[PathRef](
    p => {
      storeSerializedPaths(p)
      p.toStringPrefix + encodeKnownRootsInPath(p.path)
    },
    {
      case s"$prefix:$valid0:$hex:$pathVal" if prefix == "ref" || prefix == "qref" =>

        val path = os.Path(decodeKnownRootsInPath(pathVal))
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
        storeSerializedPaths(pr)
        pr
      case s =>
        mill.api.BuildCtx.withFilesystemCheckerDisabled(
          PathRef(os.Path(decodeKnownRootsInPath(s), currentOverrideModulePath.value))
        )
    }
  )
  private[mill] val currentOverrideModulePath = DynamicVariable[os.Path](null)

  // scalafix:off; we want to hide the unapply method
  @nowarn("msg=unused")
  private def unapply(pathRef: PathRef): Option[(os.Path, Boolean, Int, Revalidate)] = {
    Some((pathRef.path, pathRef.quick, pathRef.sig, pathRef.revalidate))
  }
  // scalalfix:on
}
