package mill.eval

import geny.Writable
import build.bazel.remote.execution.v2.{
  ActionResult,
  Digest,
  ExecutedActionMetadata,
  OutputDirectory,
  OutputFile,
  OutputSymlink
}
import com.google.protobuf.ByteString
import mill.api.PathRef

import collection.JavaConverters._
import java.io.OutputStream
import java.nio.file.attribute.PosixFilePermission
import scala.collection.mutable

object BazelRemoteCache {

  def remoteCacheTaskUrl(
      url: String,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      remoteCacheSalt: Option[String]
  ) = {
    val finalHash = inputsHash + labelled.segments.render.hashCode + remoteCacheSalt.hashCode()
    val hashString = finalHash.toHexString.reverse.padTo(64, '0').reverse
    s"$url/ac/$hashString"
  }

  def store(
      paths: EvaluatorPaths,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      url: String,
      remoteCacheSalt: Option[String],
      pathRefs: Set[PathRef]
  ): requests.Response = {
    requests.put(
      remoteCacheTaskUrl(url, inputsHash, labelled, remoteCacheSalt),
      data = new ActionResultWritable(paths, pathRefs)
    )
  }

  def load(
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      paths: EvaluatorPaths,
      url: String,
      remoteCacheSalt: Option[String]
  ): Boolean = {

    val response = requests.get.stream(
      remoteCacheTaskUrl(url, inputsHash, labelled, remoteCacheSalt),
      check = false,
      onHeadersReceived = headers => if (headers.statusCode != 200) return false
    )

    val ar = response.readBytesThrough(ActionResult.parser().parseFrom(_))
    val outputFiles = ar.getOutputFilesList.asScala

    val (mutable.Seq(meta), rest) = outputFiles.partition(_.getPath == "json")

    def writeOut(f: OutputFile, writePath: os.Path) = {
      val writable = new Writable {
        def writeBytesTo(out: OutputStream): Unit = f.getContents.writeTo(out)
      }
      os.write.over(writePath, writable, createFolders = true)
    }

    writeOut(meta, paths.meta)

    for (f <- rest) {
      val writePath = f.getPath match {
        case "log" => paths.log
        case s"dest/$rest" => paths.dest / os.SubPath(rest)
      }

      writeOut(f, writePath)
    }

    true
  }

  class ActionResultWritable(paths: EvaluatorPaths, pathRefs: Set[PathRef]) extends Writable {
    def readByteString(p: os.Path) = {
      val stream = os.read.inputStream(p)
      try ByteString.readFrom(stream)
      finally stream.close()
    }

    def writeBytesTo(out: OutputStream): Unit = {
      val pathRefSubPaths = pathRefs.map(_.path.subRelativeTo(paths.dest).segments)
      val outputFiles = mutable.Buffer.empty[OutputFile]
      val outputDirectories = mutable.Buffer.empty[OutputDirectory]
      val outputSymlinks = mutable.Buffer.empty[OutputSymlink]
      if (os.exists(paths.dest)) {
        for {
          p <- os.walk.stream(paths.dest)
          sub = p.subRelativeTo(paths.dest)
          if (sub.segments.inits.exists(pathRefSubPaths.contains))
        } {
          val pathString = s"dest/$sub"
          os.stat(p, followLinks = false).fileType match {
            case os.FileType.File =>
              outputFiles += OutputFile.newBuilder()
                .setPath(pathString)
                .setDigest(
                  Digest.newBuilder()
                    .setHash("a" * 64)
                    .setSizeBytes(os.size(p))
                )
                .setContents(readByteString(p))
                .setIsExecutable(os.perms(p).contains(PosixFilePermission.OWNER_EXECUTE))
                .build()
            case os.FileType.Dir =>
              outputDirectories += OutputDirectory.newBuilder()
                .setPath(pathString)
                .build()
            case os.FileType.SymLink =>
              outputSymlinks += OutputSymlink.newBuilder()
                .setPath(pathString)
                .setTarget(os.readLink(p).toString)
                .build()
          }
        }
      }

      if (os.exists(paths.log)) outputFiles.append(
        OutputFile.newBuilder()
          .setPath("log")
          .setDigest(
            Digest.newBuilder()
              .setHash("a" * 64)
              .setSizeBytes(os.size(paths.log))
          )
          .setContents(readByteString(paths.log))
          .build()
      )
      outputFiles.append(
        OutputFile.newBuilder()
          .setPath("json")
          .setDigest(
            Digest.newBuilder()
              .setHash("a" * 64)
              .setSizeBytes(os.size(paths.meta))
          )
          .setContents(readByteString(paths.meta))
          .build()
      )

      ActionResult.newBuilder()
        .addAllOutputFiles(outputFiles.asJava)
        .addAllOutputSymlinks(outputSymlinks.asJava)
        .setExecutionMetadata(
          ExecutedActionMetadata.newBuilder()
            .setWorker("Mill")
        )
        .build()
        .writeTo(out)
    }
  }

}
