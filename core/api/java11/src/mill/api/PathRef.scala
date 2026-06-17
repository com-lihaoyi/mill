package mill.api

import mill.api.daemon.DummyOutputStream
import mill.api.daemon.internal.PathRefApi
import mill.api.internal.PathAliasing
import upickle.ReadWriter as RW

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
case class PathRef private[mill] (
    path: os.Path,
    quick: Boolean,
    sig: Int,
    revalidate: PathRef.Revalidate
) extends PathRefApi {
  private[mill] def javaPath = path.toNIO

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

  override def toString: String =
    PathRef.PathRefFormat.render(quick, revalidate, sig, path.toString())
}

object PathRef {
  implicit def shellable(p: PathRef): os.Shellable = p.path

  /**
   * Real on-disk absolute path string for `p`, bypassing any `os.Path` serializer
   * that may be in scope. In reproducible mode `os.Path.toString` / `.toIO` /
   * `.toNIO` can return relativized `../mill-workspace/...` / `../mill-home/...`
   * forms so cached values stay workspace-independent. Those aliases only resolve
   * from a cwd where Mill has planted the matching forwarder symlinks (a task
   * `.dest`, the run sandbox, or the workspace root). Use this when the consumer
   * runs in some other directory, or treats relative paths differently from
   * absolute ones (or only accepts absolute paths). For a consumer that does run
   * in an aliased cwd and accepts relative paths, prefer [[toRelString]].
   */
  def toAbsString(p: os.Path): String = toAbsNioPath(p).toString
  def toAbsString(p: PathRef): String = toAbsString(p.path)
  def toAbsNioPath(p: os.Path): jnio.Path = p.wrapped.toAbsolutePath.normalize()
  def toAbsNioPath(p: PathRef): jnio.Path = toAbsNioPath(p.path)
  def toAbsFile(p: os.Path): java.io.File = toAbsNioPath(p).toFile
  def toAbsFile(p: PathRef): java.io.File = toAbsFile(p.path)

  /**
   * Like [[toAbsString]] but also follows symlinks via `toRealPath`, falling back to
   * lexical [[toAbsString]] if the path does not exist on disk. Use when a subprocess
   * walks the path-as-it-exists and lexical `../` collapse would land on the wrong file.
   */
  def toResolvedPathString(p: os.Path): String =
    try p.wrapped.toRealPath().toString
    catch { case _: java.io.IOException => toAbsString(p) }

  /** Same as [[toResolvedPathString]] but returns an [[os.Path]] and falls back to `p` on IO errors. */
  def toResolvedOsPath(p: os.Path): os.Path =
    try os.Path(p.wrapped.toRealPath())
    catch { case _: java.io.IOException => p }

  /**
   * Resolve symlinks in `p`, then translate the result back under `root` when it points inside
   * the same real directory tree. This strips internal forwarder symlinks while preserving the
   * lexical workspace root used by Mill's path aliases.
   */
  private[mill] def toResolvedOsPathAnchored(p: os.Path, root: os.Path): os.Path = {
    val resolvedRoot = toResolvedOsPath(root)

    @scala.annotation.tailrec
    def rec(current: os.Path, missing: List[String]): os.Path = {
      if (os.exists(current) || current.segmentCount == 0) {
        val resolvedCurrent = toResolvedOsPath(current)
        if (resolvedCurrent.startsWith(resolvedRoot)) {
          val anchoredCurrent = root / resolvedCurrent.subRelativeTo(resolvedRoot)
          missing.foldLeft(anchoredCurrent)(_ / _)
        } else p
      } else rec(current / os.up, current.last :: missing)
    }

    rec(p, Nil)
  }

  /**
   * Format `path` as a string for a Mill subprocess whose working directory is `subprocessCwd`.
   * Subprocesses at the workspace root use aliases under the configured output directory;
   * subprocesses in task sandboxes use the historical `../mill-workspace` and `../mill-home`
   * aliases.
   */
  def toRelString(
      path: os.Path,
      subprocessCwd: os.Path = BuildCtx.workspaceRoot,
      workspaceRoot: os.Path = BuildCtx.workspaceRoot
  ): String = {
    val mappings = PathAliasing.aliasMappingForCwd(subprocessCwd, workspaceRoot)

    mappings
      .collectFirst {
        case (root, alias) if path.startsWith(root) =>
          val subPath = path.subRelativeTo(root)
          if (subPath.segments.isEmpty) alias.toString
          else (alias / subPath).toString
      }
      .getOrElse(toAbsString(path))
  }
  def toRelString(
      path: PathRef,
      subprocessCwd: os.Path,
      workspaceRoot: os.Path
  ): String = toRelString(path.path, subprocessCwd, workspaceRoot)
  def toRelString(path: PathRef, subprocessCwd: os.Path): String =
    toRelString(path.path, subprocessCwd)

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

    val sig = PathAliasing.withRawPathSerializer {
      val isPosix = path.wrapped.getFileSystem.supportedFileAttributeViews().contains("posix")
      val digest = MessageDigest.getInstance("MD5")
      val digestOut = DigestOutputStream(DummyOutputStream, digest)

      def updateWithInt(value: Int): Unit = {
        digest.update((value >>> 24).toByte)
        digest.update((value >>> 16).toByte)
        digest.update((value >>> 8).toByte)
        digest.update(value.toByte)
      }

      if (os.exists(path)) {
        for (
          (path, attrs) <-
            os.walk.attrs(path, includeTarget = true, followLinks = false).sortBy(_._1.toString)
        ) {
          val sub = path.subRelativeTo(basePath)
          digest.update(sub.toString().getBytes())
          if (!attrs.isDir) {
            try {
              if (isPosix) updateWithInt(os.perms(path, followLinks = false).value)
              if (quick) {
                val value = (attrs.mtime, attrs.size).hashCode()
                updateWithInt(value)
              } else if (!os.isLink(path) && jnio.Files.isReadable(path.wrapped)) {
                // `.wrapped`, not `.toNIO`: the latter is the relativized alias form which
                // `Files.isReadable` resolves against the caller's cwd and can silently
                // return `false`, dropping the file's content from the PathRef signature.
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

    PathRef(path, quick, sig, revalidate)
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
  implicit def jsonFormatter: RW[PathRef] = upickle.readwriter[String].bimap[PathRef](
    p => {
      storeSerializedPaths(p)
      p.toString()
    },
    { s =>
      PathRefFormat.parse(s) match {
        case Some(parsed) =>
          val path = os.Path(parsed.pathString)
          val pr = PathRef(path, parsed.quick, parsed.sig, revalidate = parsed.revalidate)
          validatedPaths.value.revalidateIfNeededOrThrow(pr)
          storeSerializedPaths(pr)
          pr
        case None =>
          val path =
            if (s.startsWith("//")) os.Path(s.substring(2), BuildCtx.workspaceRoot)
            else os.Path(s, currentOverrideModulePath.value)

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
    final case class Parsed(quick: Boolean, revalidate: Revalidate, sig: Int, pathString: String)

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

    def render(quick: Boolean, revalidate: Revalidate, sig: Int, pathString: String): String =
      s"${quickToken(quick)}:${revalidateToken(revalidate)}:${renderSig(sig)}:$pathString"

    def parse(s: String): Option[Parsed] = {
      val firstColon = s.indexOf(':')
      if (firstColon < 0) None
      else {
        val prefix = s.substring(0, firstColon)
        if (prefix != "ref" && prefix != "qref") None
        else {
          val secondColon = s.indexOf(':', firstColon + 1)
          val thirdColon = if (secondColon < 0) -1 else s.indexOf(':', secondColon + 1)
          if (secondColon < 0 || thirdColon < 0) None
          else Some(Parsed(
            quick = prefix == "qref",
            revalidate = parseRevalidate(s.substring(firstColon + 1, secondColon)),
            sig = parseSig(s.substring(secondColon + 1, thirdColon)),
            pathString = s.substring(thirdColon + 1)
          ))
        }
      }
    }
  }
  private[mill] val currentOverrideModulePath = DynamicVariable[os.Path](null)

  @nowarn("msg=unused")
  private def unapply(pathRef: PathRef): Option[(os.Path, Boolean, Int, Revalidate)] = {
    Some((pathRef.path, pathRef.quick, pathRef.sig, pathRef.revalidate))
  }
}
