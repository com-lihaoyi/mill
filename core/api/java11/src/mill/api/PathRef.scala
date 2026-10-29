package mill.api

import mill.api.daemon.DummyOutputStream
import mill.api.daemon.internal.PathRefApi

import java.nio.file as jnio
import java.security.{DigestOutputStream, MessageDigest}
import java.util.concurrent.ConcurrentHashMap
import scala.annotation.{nowarn, unused}
import scala.language.implicitConversions
import scala.util.DynamicVariable
import scala.util.hashing.MurmurHash3

/**
 * A wrapper around `os.Path` that calculates a `sig` (which ends up in the [[hashCode]])
 * based on the contents of the filesystem underneath it.
 * Used to ensure filesystem changes can bust caches which are keyed off hashcodes.
 */
case class PathRef private[mill] (
    path: os.Path,
    @deprecated("fullSig.isEmpty instead.", "Mill after 1.4.0-M0")
    override val quick: Boolean,
    override val quickSig: Int,
    revalidate: PathRef.Revalidate,
    override val fullSig: Option[Int]
) extends PathRefApi {
  require(quick == fullSig.isEmpty)

  private[mill] def javaPath = path.toNIO

  @deprecated("Use quickSig or fullSig instead.", "Mill after 1.4.0-M0")
  override def sig: Int = fullSig.getOrElse(quickSig)

  /**
   * The path with common mapped path roots replaced, to make it relocatable.
   * See [[MappedRoots]].
   */
  private val mappedPath: String = MappedRoots.encodeKnownRootsInPath(path)

  /**
   * Apply the current contextual path mapping to this PathRef.
   * Updates [[mappedPath]] but does not recalculate the [[sig]].
   */
  def remap: PathRef = new PathRef(path, fullSig.isEmpty, quickSig, revalidate, fullSig)

  def recompute(): PathRef = PathRef.apply(path, fullSig.isEmpty, revalidate)
  @deprecated("Use recompute instead", "Mill after 1.4.0-M0")
  def recomputeSig(): Int = {
    val c = PathRef.calc(path, fullSig.isEmpty)
    c.fullSig.getOrElse(c.quickSig)
  }
  @deprecated("Use validateSigs instead", "Mill after 1.4.0-M0")
  def validate(): Boolean = validateSigs()
  def validateSigs(): Boolean = {
    val fresh = recompute()
    fresh.quickSig == quickSig && fresh.fullSig == fullSig
  }

  /* Hide case class specific copy method. */
  private def copy(
      path: os.Path = path,
      quick: Boolean = quick,
      quickSig: Int = quickSig,
      revalidate: PathRef.Revalidate = revalidate: @nowarn("msg=unused private member"),
      fullSig: Option[Int] = fullSig
  ): PathRef = PathRef(path, quick, quickSig, revalidate, fullSig)

  def withRevalidate(revalidate: PathRef.Revalidate): PathRef = copy(revalidate = revalidate)
  def withRevalidateOnce: PathRef = copy(revalidate = PathRef.Revalidate.Once)

  override def toString: String =
    PathRef.PathRefFormat.render(quickSig, revalidate, fullSig, path.toString())

  // Instead of using `path` we need to use `mappedPath` to make the hashcode stable as cache key
  override def hashCode(): Int = {
    var h = MurmurHash3.productSeed
    h = MurmurHash3.mix(h, "PathRef".hashCode())
    h = MurmurHash3.mix(h, mappedPath.hashCode())
    h = MurmurHash3.mix(h, quickSig.##)
    h = MurmurHash3.mix(h, fullSig.hashCode())
    h = MurmurHash3.mix(h, revalidate.hashCode())
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
            throw PathRefValidationException(pathRef)
          }
          val _ = map.put(mapKey(pathRef), pathRef)
      }
    }
    def clear(): Unit = map.clear()
  }

  private[mill] val validatedPaths: DynamicVariable[ValidatedPaths] =
    DynamicVariable[ValidatedPaths](ValidatedPaths())

  private[mill] val serializedPaths: DynamicVariable[List[PathRef]] =
    DynamicVariable(null)

  class PathRefValidationException(val pathRef: PathRef)
      extends RuntimeException(s"Invalid path signature detected: ${pathRef}")

  sealed trait Revalidate
  object Revalidate {
    case object Never extends Revalidate
    case object Once extends Revalidate
    case object Always extends Revalidate
  }

  @deprecated("Use another overload that supports fullSig parameter", "Mill after 1.4.0-M0")
  def apply(path: os.Path, quick: Boolean, sig: Int, revalidate: Revalidate): PathRef = {
    // we no longer can support this version, as we don't have a quickSig for a non-quick pathref
    val sigs = if (!quick) {
      // FIXME: consider removing the sys-err-out in non-snapshot release
      System.err.println(
        "This method is no longer supported for non-quick PathRef's. Recomputing signatures ..."
      )
      // need to recompute it
      val c = calc(path, quick)
      (quickSig = c.quickSig, fullSig = c.fullSig)
    } else {
      (quickSig = sig, fullSig = None)
    }
    new PathRef(path, quick, sigs.quickSig, revalidate, sigs.fullSig)
  }

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
    val c = calc(path, quick)
    PathRef(path, quick, c.quickSig, revalidate, c.fullSig)
  }

  private def calc(
      path: os.Path,
      quick: Boolean
  ): (
      count: Int,
      isDir: Boolean,
      size: Long,
      quickSig: Int,
      fullSig: Option[Int]
  ) = {
    val basePath = path
    val isDir = os.isDir(path)
    val isPosix = path.wrapped.getFileSystem.supportedFileAttributeViews().contains("posix")

    var size = 0L
    var count = 0

    val (quickSig, fullSig) = {

      val quickDigest = MessageDigest.getInstance("MD5")

      val fullDigest = Option.when(!quick)(MessageDigest.getInstance("MD5"))
      val fullDigestOut = fullDigest.map(d => DigestOutputStream(DummyOutputStream, d))

      if (os.exists(path)) {
        for (
          (path, attrs) <-
            os.walk.attrs(path, includeTarget = true, followLinks = true)
              .sortBy(_._1.toString)
        ) {
          // each entry counts
          count += 1

          // only real files, not symlink or other stuff like sockets
          if (attrs.isFile) {
            size += attrs.size
          }

          val sub = path.subRelativeTo(basePath)

          // Digest sub-path
          quickDigest.update(sub.toString().getBytes())
          fullDigest.foreach(_.update(sub.toString().getBytes()))

          if (!attrs.isDir) {
            try {

              // Digest file permissions
              if (isPosix) {
                // TODO: Should we only digest the effective permissions, at least in the quick version ?
                quickDigest.updateWithInt(os.perms(path, followLinks = false).value)
                fullDigest.foreach(_.updateWithInt(os.perms(path, followLinks = false).value))
              }

              // Digest the file size
              val value = attrs.size.hashCode()
              quickDigest.updateWithInt(value)
              // fullDigest.foreach(_.updateWithInt(value))

              // Digest the file contents
              if (!quick && jnio.Files.isReadable(path.toNIO)) {
                fullDigestOut.foreach { d =>
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
                  is.foreach(os.Internals.transfer(_, d))
                }
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

      (
        java.util.Arrays.hashCode(quickDigest.digest()),
        fullDigest.map(d => java.util.Arrays.hashCode(d.digest()))
      )
    }

    (
      count = count,
      isDir = isDir,
      size = size,
      quickSig = quickSig,
      fullSig = fullSig
    )
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

  /**
   * Default JSON formatter for [[PathRef]].
   */
  implicit def jsonFormatter: upickle.ReadWriter[PathRef] =
    upickle.readwriter[String].bimap[PathRef](
      p => {
        storeSerializedPaths(p)
        PathRef.PathRefFormat.render(
          p.quickSig,
          p.revalidate,
          p.fullSig,
          MappedRoots.encodeKnownRootsInPath(p.path)
        )
      },
      { s =>
        PathRefFormat.parse(s) match {
          case Some(parsed) =>
            val path = os.Path(MappedRoots.decodeKnownRootsInPath(parsed.pathString))
            val pr = PathRef(
              path = path,
              quick = parsed.quick,
              quickSig = parsed.quickSig,
              revalidate = parsed.revalidate,
              fullSig = parsed.fullSig
            )
            validatedPaths.value.revalidateIfNeededOrThrow(pr)
            storeSerializedPaths(pr)
            pr
          case None =>
            val path =
              if (s.startsWith("//")) os.Path(s.substring(2), BuildCtx.workspaceRoot)
              else os.Path(MappedRoots.decodeKnownRootsInPath(s), currentOverrideModulePath.value)

            mill.api.BuildCtx.withFilesystemCheckerDisabled(PathRef(path))
        }
      }
    )

  /**
   * The single encode/decode codec for the [[PathRef.toString]] wire format
   * (`{qref|ref}:{v0|v1|vn}:{08x-sig}:{path}`). Owns the token tables and the
   * signed-hex convention so encode ([[render]]) and decode ([[parse]]) can never
   * drift. This format is an on-disk + remote-cache contract, pinned byte-for-byte
   * by `PathRefTests.json`.
   */
  private[mill] object PathRefFormat {
    final case class Parsed(
        quick: Boolean,
        revalidate: Revalidate,
        @deprecated("Use quickSig for access", "Mill after 1.4.0-M0")
        sig: Int, // quickSig !!
        pathString: String,
        @com.lihaoyi.unroll
        fullSig: Option[Int] = None
    ) {
      def quickSig: Int = sig
    }

    private def quickToken(quick: Boolean): String = if (quick) "qref" else "ref"

    private def revalidateToken(revalidate: Revalidate): String = revalidate match {
      case Revalidate.Never => "v0"
      case Revalidate.Once => "v1"
      case Revalidate.Always => "vn"
    }

    private def parseRevalidate(token: String): Revalidate = token match {
      case "v0" => Revalidate.Never
      case "v1" => Revalidate.Once
      case "vn" => Revalidate.Always
    }

    private def renderSig(sig: Int): String = String.format("%08x", sig: Integer)

    // Parsing to a long and casting to an int is the only way to make
    // round-trip handling of negative numbers work =(
    private def parseSig(hex: String): Int = java.lang.Long.parseLong(hex, 16).toInt

    def render(
        quickSig: Int,
        revalidate: Revalidate,
        fullSig: Option[Int],
        pathString: String
    ): String = {
      s"${quickToken(fullSig.isEmpty)}:${revalidateToken(revalidate)}:${renderSig(quickSig)}:${fullSig.map(renderSig).getOrElse("")}:$pathString"
    }

    def parse(s: String): Option[Parsed] = {
      val colon1 = s.indexOf(':')
      if (colon1 < 0) None
      else {
        val prefix = s.substring(0, colon1)
        if (prefix != "ref" && prefix != "qref") None
        else {
          val colon2 = s.indexOf(':', colon1 + 1)
          val colon3 = if (colon2 < 0) -1 else s.indexOf(':', colon2 + 1)
          val colon4 = if (colon3 < 0) -1 else s.indexOf(':', colon3 + 1)
          if (colon2 < 0 || colon3 < 0 || colon4 < 0) None
          else {
            val quickSig = parseSig(s.substring(colon2 + 1, colon3))
            val fullSig =
              Option(s.substring(colon3 + 1, colon4)).filter(_.nonEmpty).map(parseSig(_))
            Some(Parsed(
              quick = fullSig.isEmpty,
              revalidate = parseRevalidate(s.substring(colon1 + 1, colon2)),
              sig = quickSig,
              pathString = s.substring(colon4 + 1),
              fullSig = fullSig
            ))
          }
        }
      }
    }
  }

  private[mill] val currentOverrideModulePath = DynamicVariable[os.Path](null)

  @unused
  private def unapply(pathRef: PathRef): Option[(os.Path, Boolean, Int, Revalidate)] = {
    Some((
      pathRef.path,
      pathRef.quick,
      pathRef.sig,
      pathRef.revalidate
    ))
  }

  extension (digest: MessageDigest) {
    private def updateWithInt(value: Int): Unit = {
      digest.update((value >>> 24).toByte)
      digest.update((value >>> 16).toByte)
      digest.update((value >>> 8).toByte)
      digest.update(value.toByte)
    }
  }
}
