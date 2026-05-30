package mill.exec

import mill.api.{ExecutionPaths, PathRef}

import java.io.{ByteArrayOutputStream, DataInputStream, DataOutputStream, InputStream, OutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.file.attribute.PosixFilePermission
import java.security.{DigestOutputStream, MessageDigest}
import java.time.Duration
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
import scala.util.control.NonFatal

/**
 * A minimal client for a remote cache speaking the
 * [[https://github.com/bazelbuild/remote-apis Bazel Remote Execution]] HTTP protocol (as served
 * by [[https://github.com/buchgr/bazel-remote bazel-remote]], Buildbarn, EngFlow, etc.), letting
 * builds in different folders or on different machines share `out/` task outputs.
 *
 * [[store]] bundles a task's `dest/`/`log`/`meta.json` into one gzipped blob, `PUT`s it to the
 * CAS (`/cas/<sha256>`), then `PUT`s an `ActionResult` referencing it to the Action Cache
 * (`/ac/<key>`); [[load]] reverses that. The protocol
 * [[https://github.com/bazelbuild/remote-apis/blob/6c32c3b917cc5d3cfee680c03179d7552832bb3f/build/bazel/remote/execution/v2/remote_execution.proto#L1216-L1222 disallows]]
 * inlining the blob into the `ActionResult`, hence the two steps. The few `ActionResult` fields
 * are encoded by hand (see [[Protobuf]]) to avoid a `protobuf-java` dependency.
 *
 * A `--remote-cache-location` of `http(s)://` uses the protocol above; a `file:` URL or path
 * uses a local/shared directory directly (see [[Backend]]), with no server. Setting
 * `MILL_REMOTE_CACHE_AUTHORIZATION` sends its value as the `Authorization` header.
 */
private[mill] object BazelRemoteCache {

  private val connectTimeout = Duration.ofSeconds(30)
  private val requestTimeout = Duration.ofSeconds(120)

  private lazy val client: HttpClient =
    HttpClient.newBuilder().connectTimeout(connectTimeout).build()

  private lazy val authHeader: Option[String] = sys.env.get("MILL_REMOTE_CACHE_AUTHORIZATION")

  def actionCacheKey(inputsHash: Int, segmentsRender: String, salt: Option[String]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(ByteBuffer.allocate(4).putInt(inputsHash).array())
    md.update(0.toByte)
    md.update(segmentsRender.getBytes("UTF-8"))
    md.update(0.toByte)
    salt.foreach(s => md.update(s.getBytes("UTF-8")))
    mill.constants.Util.hexArray(md.digest())
  }

  /** Failures are swallowed: an upload must never fail the build. */
  def store(
      paths: ExecutionPaths,
      inputsHash: Int,
      segmentsRender: String,
      location: String,
      salt: Option[String],
      pathRefs: Seq[PathRef],
      workspace: os.Path
  ): Unit = mill.api.BuildCtx.withFilesystemCheckerDisabled {
    // Cache I/O is trusted framework I/O that legitimately writes outside the task's `dest/`.
    val backend = Backend.forLocation(location, workspace)
    val blobFile = os.temp(prefix = "mill-remote-cache-", deleteOnExit = true)
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      val out = new DigestOutputStream(os.write.outputStream(blobFile), digest)
      try writeBlob(paths, pathRefs, out)
      finally out.close()
      val sha = mill.constants.Util.hexArray(digest.digest())

      // Upload the blob before the AC entry that references it, so a reader never sees an AC
      // entry pointing at a missing blob.
      if (backend.putFile(s"cas/$sha", blobFile))
        backend.putBytes(
          s"ac/${actionCacheKey(inputsHash, segmentsRender, salt)}",
          Protobuf.encodeActionResult(sha, os.size(blobFile))
        )
    } catch {
      case NonFatal(_) =>
    } finally os.remove(blobFile)
  }

  /** Reverse of [[store]]: `true` if a full entry was found and unpacked, `false` otherwise. */
  def load(
      paths: ExecutionPaths,
      inputsHash: Int,
      segmentsRender: String,
      location: String,
      salt: Option[String],
      workspace: os.Path
  ): Boolean = mill.api.BuildCtx.withFilesystemCheckerDisabled {
    val backend = Backend.forLocation(location, workspace)
    try {
      backend.getBytes(s"ac/${actionCacheKey(inputsHash, segmentsRender, salt)}") match {
        case None => false
        case Some(acBytes) =>
          Protobuf.decodeBlobDigestHash(acBytes) match {
            case None => false
            case Some(sha) =>
              backend.getStream(s"cas/$sha") match {
                // Blob evicted or not yet propagated: treat as a miss and recompute.
                case None => false
                case Some(is) =>
                  // Wipe stale `dest/` only on a hit; a miss keeps it so a persistent task reuses
                  // its state.
                  if (os.exists(paths.dest)) os.remove.all(paths.dest)
                  try readBlob(paths, is)
                  finally is.close()
                  true
              }
          }
      }
    } catch {
      case NonFatal(_) => false
    }
  }

  // Gzipped archive of a task's outputs, one entry per file/dir/symlink. Files carry mtime so
  // `quick` PathRefs (which hash it) re-validate. See writeFile/writeDir/writeLink for the layout.
  private def writeBlob(
      paths: ExecutionPaths,
      pathRefs: Seq[PathRef],
      out: OutputStream
  ): Unit = {
    val dos = new DataOutputStream(new GZIPOutputStream(out))
    try {
      val referencedSubPaths: Set[Seq[String]] = pathRefs.iterator
        .map(_.path)
        .filter(_.startsWith(paths.dest))
        .map(_.subRelativeTo(paths.dest).segments.toSeq)
        .toSet

      // Also keep ancestors of referenced PathRefs, so intermediate dirs retain their perms.
      def referenced(sub: os.SubPath): Boolean = {
        val segs = sub.segments.toSeq
        referencedSubPaths.exists(ref => segs.startsWith(ref) || ref.startsWith(segs))
      }

      // os.walk yields parents before children — the order readBlob needs to recreate dirs first.
      if (os.exists(paths.dest)) {
        for {
          p <- os.walk(paths.dest)
          sub = p.subRelativeTo(paths.dest)
          if referenced(sub)
        } {
          os.stat(p, followLinks = false).fileType match {
            case os.FileType.SymLink => writeLink(dos, s"dest/$sub", os.readLink(p))
            case os.FileType.Dir => writeDir(dos, s"dest/$sub", os.perms(p).toInt())
            case _ => writeFile(dos, s"dest/$sub", p)
          }
        }
      }
      if (os.exists(paths.log)) writeFile(dos, "log", paths.log)
      writeFile(dos, "json", paths.meta)
    } finally dos.close()
  }

  private def writeDir(out: DataOutputStream, name: String, mode: Int): Unit = {
    out.writeByte('D'); out.writeUTF(name)
    out.writeInt(mode)
  }
  private def writeFile(out: DataOutputStream, name: String, path: os.Path): Unit = {
    out.writeByte('F'); out.writeUTF(name)
    out.writeInt(os.perms(path).toInt())
    out.writeLong(os.mtime(path))
    out.writeLong(os.size(path))
    os.Internals.transfer(os.read.inputStream(path), out)
  }
  private def writeLink(out: DataOutputStream, name: String, target: os.FilePath): Unit = {
    out.writeByte('L'); out.writeUTF(name)
    out.writeUTF(target.toString)
  }

  private def readBlob(paths: ExecutionPaths, in: InputStream): Unit = {
    val dis = new DataInputStream(new GZIPInputStream(in))
    try {
      while ({
        dis.read() match {
          case -1 => false
          case tpe =>
            val name = dis.readUTF()
            // os.SubPath rejects `..`/absolute paths, so a hostile name can't escape dest/log/meta.
            val dest = os.SubPath(name).segments.toList match {
              case "dest" :: rest => rest.foldLeft(paths.dest)(_ / _)
              case "log" :: Nil => paths.log
              case "json" :: Nil => paths.meta
              case _ => sys.error(s"Unexpected remote cache blob entry: $name")
            }
            tpe.toChar match {
              case 'D' =>
                os.makeDir.all(dest, perms = traversable(os.PermSet(dis.readInt())))
              case 'L' =>
                val target = dis.readUTF()
                // Create the parent with default perms; os.symlink would give it this entry's
                // own (non-traversable) perms.
                os.makeDir.all(dest / os.up)
                if (os.exists(dest, followLinks = false)) os.remove(dest)
                os.symlink(dest, os.FilePath(target))
              case _ => // 'F'
                val mode = dis.readInt()
                val mtime = dis.readLong()
                val size = dis.readLong()
                os.makeDir.all(dest / os.up) // see 'L': default perms for parents, not this file's
                val fos = os.write.outputStream(dest, perms = os.PermSet(mode))
                try transferN(dis, fos, size)
                finally fos.close()
                os.mtime.set(dest, mtime)
            }
            true
        }
      }) ()
    } finally dis.close()
  }

  private def traversable(p: os.PermSet): os.PermSet =
    p + PosixFilePermission.OWNER_EXECUTE +
      PosixFilePermission.GROUP_EXECUTE +
      PosixFilePermission.OTHERS_EXECUTE

  private trait Backend {
    def getBytes(key: String): Option[Array[Byte]]
    def getStream(key: String): Option[InputStream]
    def putFile(key: String, file: os.Path): Boolean
    def putBytes(key: String, bytes: Array[Byte]): Boolean
  }

  private object Backend {
    def forLocation(location: String, workspace: os.Path): Backend =
      if (location.startsWith("http://") || location.startsWith("https://"))
        new HttpBackend(location.stripSuffix("/"))
      else if (location.startsWith("file:"))
        new FileBackend(os.Path(java.nio.file.Path.of(URI.create(location))))
      else if (location.startsWith("~/"))
        new FileBackend(os.home / os.SubPath(location.stripPrefix("~/")))
      else new FileBackend(os.Path(location, workspace))
  }

  private class HttpBackend(baseUrl: String) extends Backend {
    private def requestBuilder(key: String): HttpRequest.Builder = {
      val b = HttpRequest.newBuilder(URI.create(s"$baseUrl/$key")).timeout(requestTimeout)
      authHeader.fold(b)(h => b.header("Authorization", h))
    }
    def getBytes(key: String): Option[Array[Byte]] = {
      val resp =
        client.send(requestBuilder(key).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
      Option.when(resp.statusCode() == 200)(resp.body())
    }
    def getStream(key: String): Option[InputStream] = {
      val resp =
        client.send(requestBuilder(key).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
      if (resp.statusCode() == 200) Some(resp.body())
      else { resp.body().close(); None }
    }
    private def put(key: String, body: HttpRequest.BodyPublisher): Boolean =
      client.send(requestBuilder(key).PUT(body).build(), HttpResponse.BodyHandlers.discarding())
        .statusCode() / 100 == 2
    def putFile(key: String, file: os.Path): Boolean =
      put(key, HttpRequest.BodyPublishers.ofFile(file.wrapped))
    def putBytes(key: String, bytes: Array[Byte]): Boolean =
      put(key, HttpRequest.BodyPublishers.ofByteArray(bytes))
  }

  private class FileBackend(dir: os.Path) extends Backend {
    private def path(key: String): os.Path = dir / os.SubPath(key)
    def getBytes(key: String): Option[Array[Byte]] = {
      val p = path(key)
      Option.when(os.exists(p))(os.read.bytes(p))
    }
    def getStream(key: String): Option[InputStream] = {
      val p = path(key)
      Option.when(os.exists(p))(os.read.inputStream(p))
    }
    def putFile(key: String, file: os.Path): Boolean = {
      os.copy.over(file, path(key), createFolders = true)
      true
    }
    def putBytes(key: String, bytes: Array[Byte]): Boolean = {
      os.write.over(path(key), bytes, createFolders = true)
      true
    }
  }

  private def transferN(src: InputStream, dest: OutputStream, n: Long): Unit = {
    val buf = new Array[Byte](8192)
    var remaining = n
    while (remaining > 0) {
      val r = src.read(buf, 0, math.min(buf.length.toLong, remaining).toInt)
      if (r == -1) throw new java.io.EOFException()
      dest.write(buf, 0, r)
      remaining -= r
    }
  }

  /**
   * Hand-rolled encoder/decoder for the [[https://github.com/bazelbuild/remote-apis ActionResult]]
   * fields Mill uses, avoiding a `protobuf-java` dependency: a single output file `"blob"` whose
   * Digest points at the CAS blob.
   */
  private object Protobuf {
    // Field numbers from build/bazel/remote/execution/v2/remote_execution.proto
    private val ActionResultOutputFiles = 2
    private val OutputFilePath = 1
    private val OutputFileDigest = 2
    private val DigestHash = 1
    private val DigestSizeBytes = 2

    private val WireVarint = 0
    private val WireLengthDelim = 2

    def encodeActionResult(blobSha: String, blobSize: Long): Array[Byte] = {
      val digest = concat(
        lengthDelimited(DigestHash, blobSha.getBytes("UTF-8")),
        varintField(DigestSizeBytes, blobSize)
      )
      val outputFile = concat(
        lengthDelimited(OutputFilePath, "blob".getBytes("UTF-8")),
        lengthDelimited(OutputFileDigest, digest)
      )
      lengthDelimited(ActionResultOutputFiles, outputFile)
    }

    def decodeBlobDigestHash(bytes: Array[Byte]): Option[String] =
      firstMessage(bytes, ActionResultOutputFiles)
        .flatMap(firstMessage(_, OutputFileDigest))
        .flatMap(firstString(_, DigestHash))

    private def firstMessage(bytes: Array[Byte], field: Int): Option[Array[Byte]] =
      fields(bytes).collectFirst { case (`field`, WireLengthDelim, v) => v }

    private def firstString(bytes: Array[Byte], field: Int): Option[String] =
      firstMessage(bytes, field).map(new String(_, "UTF-8"))

    private def fields(bytes: Array[Byte]): List[(Int, Int, Array[Byte])] = {
      val out = List.newBuilder[(Int, Int, Array[Byte])]
      var pos = 0
      var continue = true
      while (continue && pos < bytes.length) {
        val (tag, p1) = readVarint(bytes, pos)
        val field = (tag >>> 3).toInt
        val wire = (tag & 0x7).toInt
        wire match {
          case WireVarint =>
            val (_, p2) = readVarint(bytes, p1); pos = p2
          case WireLengthDelim =>
            val (len, p2) = readVarint(bytes, p1)
            val end = math.min(p2 + len.toInt, bytes.length)
            out += ((field, wire, bytes.slice(p2, end))); pos = end
          case 5 => pos = math.min(p1 + 4, bytes.length) // fixed32
          case 1 => pos = math.min(p1 + 8, bytes.length) // fixed64
          case _ => continue = false // groups (deprecated) or invalid
        }
      }
      out.result()
    }

    private def concat(parts: Array[Byte]*): Array[Byte] = {
      val out = new ByteArrayOutputStream()
      parts.foreach(out.write)
      out.toByteArray
    }
    private def tag(field: Int, wire: Int): Array[Byte] = encodeVarint(((field << 3) | wire).toLong)
    private def varintField(field: Int, value: Long): Array[Byte] =
      concat(tag(field, WireVarint), encodeVarint(value))
    private def lengthDelimited(field: Int, value: Array[Byte]): Array[Byte] =
      concat(tag(field, WireLengthDelim), encodeVarint(value.length.toLong), value)

    private def encodeVarint(value0: Long): Array[Byte] = {
      var value = value0
      val out = new ByteArrayOutputStream()
      while ((value & ~0x7fL) != 0) {
        out.write(((value & 0x7f) | 0x80).toInt)
        value >>>= 7
      }
      out.write((value & 0x7f).toInt)
      out.toByteArray
    }
    private def readVarint(bytes: Array[Byte], pos0: Int): (Long, Int) = {
      var result = 0L
      var shift = 0
      var pos = pos0
      var done = false
      while (!done) {
        if (pos >= bytes.length) throw new IllegalArgumentException("truncated varint")
        if (shift > 63) throw new IllegalArgumentException("varint too long")
        val b = bytes(pos); pos += 1
        result |= (b.toLong & 0x7f) << shift
        if ((b & 0x80) == 0) done = true else shift += 7
      }
      (result, pos)
    }
  }
}
