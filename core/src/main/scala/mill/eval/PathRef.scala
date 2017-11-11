package mill.eval

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, FileVisitor}
import java.nio.{file => jnio}
import java.security.MessageDigest

import ammonite.ops.Path
import mill.util.JsonFormatters
import play.api.libs.json.{Format, Json}


/**
  * A wrapper around `ammonite.ops.Path` that calculates it's hashcode based
  * on the contents of the filesystem underneath it. Used to ensure filesystem
  * changes can bust caches which are keyed off hashcodes.
  */
case class PathRef(path: ammonite.ops.Path){
  val md5Hash = {
    val digest = MessageDigest.getInstance("MD5")

    val buffer = new Array[Byte](16 * 1024)
    jnio.Files.walkFileTree(
      path.toNIO,
      new FileVisitor[jnio.Path] {
        def preVisitDirectory(dir: jnio.Path, attrs: BasicFileAttributes) = {
          digest.update(dir.toAbsolutePath.toString.getBytes)
          FileVisitResult.CONTINUE
        }

        def visitFile(file: jnio.Path, attrs: BasicFileAttributes) = {
          digest.update(file.toAbsolutePath.toString.getBytes)
          val is = jnio.Files.newInputStream(file)
          def rec(): Unit = {
            val length = is.read(buffer)
            if (length != -1){
              digest.update(buffer, 0, length)
              rec()
            }
          }
          rec()
          FileVisitResult.CONTINUE
        }

        def visitFileFailed(file: jnio.Path, exc: IOException) = FileVisitResult.CONTINUE
        def postVisitDirectory(dir: jnio.Path, exc: IOException) = FileVisitResult.CONTINUE
      }
    )

    java.util.Arrays.hashCode(digest.digest())

  }
  override def hashCode() = md5Hash
}

object PathRef{
  private implicit val pathFormat: Format[Path] = JsonFormatters.pathFormat
  implicit def jsonFormatter: Format[PathRef] = Json.format
}
