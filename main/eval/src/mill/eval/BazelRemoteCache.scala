package mill.eval

import geny.Writable
import build.bazel.remote.execution.v2.{ActionResult, Digest, OutputFile}
import mill.api.PathRef
import org.apache.commons.compress.archivers.tar.{
  TarArchiveEntry,
  TarArchiveInputStream,
  TarArchiveOutputStream,
  TarConstants
}

import collection.JavaConverters._
import java.io.{InputStream, OutputStream}
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

object BazelRemoteCache {

  def actionCacheUrl(
      url: String,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      remoteCacheSalt: Option[String]
  ) = {
    val finalHash = inputsHash + labelled.segments.render.hashCode + remoteCacheSalt.hashCode()
    val hashString = finalHash.toHexString.reverse.padTo(64, '0').reverse
    s"$url/ac/$hashString"
  }

  def readSpillToDisk[T](
      paths: EvaluatorPaths,
      writable: geny.Writable
  )(f: SpillToDiskOutputStream => T): T = {
    val stdos = new SpillToDiskOutputStream(1024 * 1024, paths.tmp)
    writable.writeBytesTo(stdos)
    try f(stdos)
    finally stdos.close()
  }

  def store(
      paths: EvaluatorPaths,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      url: String,
      remoteCacheSalt: Option[String],
      pathRefs: Set[PathRef]
  ): requests.Response = {

    readSpillToDisk(paths, new BlobWritable(paths, pathRefs)) { data =>
      val digest = MessageDigest.getInstance("SHA-256")
      data.writeBytesTo(new OutputStream {
        override def write(b: Int): Unit = digest.update(b.toByte)
        override def write(b: Array[Byte]): Unit = digest.update(b)
        override def write(b: Array[Byte], off: Int, len: Int): Unit = digest.update(b, off, len)
      })

      val shaDigestString = getHex(digest.digest())
      requests.put(s"$url/cas/$shaDigestString", data = data)
      requests.put(
        actionCacheUrl(url, inputsHash, labelled, remoteCacheSalt),
        data = new ActionResultWritable(data.size, shaDigestString)
      )
    }
  }

  class BlobWritable(paths: EvaluatorPaths, pathRefs: Set[PathRef]) extends Writable {
    override def writeBytesTo(out: OutputStream): Unit = {
      val gzOutput = new GZIPOutputStream(out)
      val tarOutput = new TarArchiveOutputStream(gzOutput)

      tarOutput.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)

      val pathRefSubPaths = pathRefs
        .map(_.path.relativeTo(paths.dest))
        .filter(_.ups == 0)
        .map(_.segments)

      def putEntry(name: String, fileType: Byte, data: Option[os.Path] = None) = {
        val entry = new TarArchiveEntry(name, fileType)
        for (d <- data) {
          entry.setSize(os.size(d))
          entry.setMode(os.perms(d).toInt())
        }

        tarOutput.putArchiveEntry(entry)

        for (d <- data) {
          os.read.stream(d).writeBytesTo(tarOutput)
          tarOutput.closeArchiveEntry()
        }
      }
      if (os.exists(paths.dest)) {
        for {
          p <- os.walk(paths.dest)
          sub = p.subRelativeTo(paths.dest)
          if sub.segments.inits.exists(pathRefSubPaths.contains)
        } {
          os.stat(p, followLinks = false).fileType match {
            case os.FileType.File =>
              putEntry("dest/" + sub.toString, TarConstants.LF_NORMAL, Some(p))

            case os.FileType.Dir =>
              putEntry("dest/" + sub.toString + "/", TarConstants.LF_DIR)
          }
        }
      }
      if (os.exists(paths.log)) {
        putEntry("log", TarConstants.LF_NORMAL, Some(paths.log))
      }

      putEntry("json", TarConstants.LF_NORMAL, Some(paths.meta))

      tarOutput.close()
    }
  }

  class ActionResultWritable(size: Long, shaDigestString: String) extends Writable {

    def writeBytesTo(out: OutputStream): Unit = {
      ActionResult.newBuilder()
        .addOutputFiles(
          OutputFile.newBuilder()
            .setPath("blob")
            .setDigest(
              Digest.newBuilder()
                .setHash(shaDigestString)
                .setSizeBytes(size)
            )
            .build()
        )
        .build()
        .writeTo(out)
    }
  }

  // from https://stackoverflow.com/a/9655224/871202
  def getHex(raw: Array[Byte]): String = {
    val HEXES = "0123456789abcdef"
    val hex = new StringBuilder(2 * raw.length)
    for (b <- raw) {
      hex.append(HEXES.charAt((b & 0xf0) >> 4))
        .append(HEXES.charAt(b & 0x0f))
    }
    hex.toString()
  }

  def load(
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      paths: EvaluatorPaths,
      url: String,
      remoteCacheSalt: Option[String]
  ): Boolean = {

    val response = requests.get(
      actionCacheUrl(url, inputsHash, labelled, remoteCacheSalt),
      check = false
    )

    if (response.statusCode != 200) false
    else {
      val ar = response.readBytesThrough(ActionResult.parser().parseFrom(_))
      val outputFiles = ar.getOutputFilesList.asScala

      val shaDigestString = outputFiles.head.getDigest.getHash

      var statusCode = -1
      readSpillToDisk(
        paths,
        requests.get.stream(
          s"$url/cas/$shaDigestString",
          onHeadersReceived = headers => { statusCode = headers.statusCode }
        )
      ) { data =>
        if (statusCode != 200) false
        else {
          untar(
            data,
            name => {
              os.SubPath(name).segments match {
                case Seq("dest", rest @ _*) => paths.dest / rest
                case Seq("log") => paths.log
                case Seq("json") => paths.meta
              }

            }
          )
        }

        true
      }
    }
  }

  def untar(readable: geny.Readable, destMapping: String => os.Path): Unit =
    readable.readBytesThrough { is =>
      val tarInput = new TarArchiveInputStream(new GZIPInputStream(is))
      try {
        while ({
          tarInput.getNextTarEntry() match {
            case null => false
            case entry =>
              val outputFile = destMapping(entry.getName)
              val dirPerms =
                os.PermSet(entry.getMode) +
                  PosixFilePermission.OWNER_EXECUTE +
                  PosixFilePermission.GROUP_EXECUTE +
                  PosixFilePermission.OTHERS_EXECUTE

              if (entry.isDirectory) os.makeDir.all(outputFile, perms = dirPerms)
              else {
                os.makeDir.all(outputFile / os.up, perms = dirPerms)

                val fos = os.write.outputStream(
                  outputFile,
                  createFolders = true,
                  perms = entry.getMode
                )
                try transfer(tarInput, fos)
                finally fos.close()
              }
              true
          }
        }) ()
      } finally tarInput.close()
    }

  // Copy of os/Internal.scala, but without closing the inputstream at the
  // end because we need to use it again for each subsequent tar file entry
  def transfer0(src: InputStream, sink: (Array[Byte], Int) => Unit) = {
    val buffer = new Array[Byte](8192)
    var r = 0
    while (r != -1) {
      r = src.read(buffer)
      if (r != -1) sink(buffer, r)
    }
//    src.close()
  }

  def transfer(src: InputStream, dest: OutputStream) = transfer0(
    src,
    dest.write(_, 0, _)
  )
}
