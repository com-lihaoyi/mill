package mill.scalalib.publish

import coursier.maven.MavenRepository
import coursier.publish.fileset.FileSet
import coursier.publish.upload.HttpURLConnectionUpload
import coursier.publish.upload.logger.InteractiveUploadLogger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.control.NonFatal

object RemoteIvyPublisher {
  case class PublishOptions(
      readTimeout: Option[FiniteDuration] = Some(10.seconds),
      connectionTimeout: Option[FiniteDuration] = Some(10.seconds)
  )

  def publish(
      repository: MavenRepository,
      fileSet: FileSet,
      options: PublishOptions = PublishOptions()
  )(using ec: ExecutionContext) = {
    val upload = HttpURLConnectionUpload.create(
      readTimeoutMs = options.readTimeout.map(_.toMillis.toInt),
      connectionTimeoutMs = options.connectionTimeout.map(_.toMillis.toInt)
    )

    val uploadLogger = InteractiveUploadLogger.create(System.err, dummy = false, isLocal = false)

    val errors =
      try
        upload.uploadFileSet(
          repository,
          fileSet,
          uploadLogger,
          parallel = None
        ).unsafeRun()(using ec)
      catch {
        case NonFatal(e) =>
          // Wrap exception from coursier, as it sometimes throws exceptions from other threads,
          // which lack the current stacktrace.
          throw new Exception(e)
      }

    if (errors.nonEmpty)
      throw new Exception(errors.mkString("\n"))
  }
}
