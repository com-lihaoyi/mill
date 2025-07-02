package mill.contrib.artifactory

import mill.api.Logger
import mill.util.FileSetContents
import mill.scalalib.publish.Artifact
import os.SubPath

class ArtifactoryPublisher(
    releaseUri: String,
    snapshotUri: String,
    credentials: String,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger
) {
  private val api =
    new ArtifactoryHttpApi(credentials, readTimeout = readTimeout, connectTimeout = connectTimeout)

  def publish(fileMapping: FileSetContents.Path, artifact: Artifact): Unit = {
    publishAll(fileMapping -> artifact)
  }

  def publishAll(artifacts: (FileSetContents.Path, Artifact)*): Unit = {
    log.info("arts: " + artifacts)
    val mappings = for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath = SubPath(Seq(
        artifact.group.replace(".", "/"),
        artifact.id,
        artifact.version
      ).mkString("/"))
      val fileMapping = fileMapping0.mapPaths(name => publishPath / name)

      artifact -> fileMapping.mapContents(_.readFromDisk())
    }

    val (snapshots, releases) = mappings.partition(_._1.isSnapshot)

    if (snapshots.nonEmpty)
      publishToRepo(
        snapshotUri,
        FileSetContents.mergeAll(snapshots.iterator.map(_._2)),
        snapshots.map(_._1)
      )
    if (releases.nonEmpty)
      publishToRepo(
        releaseUri,
        FileSetContents.mergeAll(releases.iterator.map(_._2)),
        releases.map(_._1)
      )
  }

  private def publishToRepo(
      repoUri: String,
      payloads: FileSetContents.Bytes,
      artifacts: Seq[Artifact]
  ): Unit = {
    val publishResults = payloads.contents.iterator.map {
      case (fileName, data) =>
        log.info(s"Uploading $fileName")
        val resp = api.upload(s"$repoUri/$fileName", data.bytesUnsafe)
        resp
    }.toVector
    reportPublishResults(publishResults, artifacts)
  }

  private def reportPublishResults(
      publishResults: Seq[requests.Response],
      artifacts: Seq[Artifact]
  ) = {
    if (publishResults.forall(_.is2xx)) {
      log.info(s"Published ${artifacts.map(_.id).mkString(", ")} to Artifactory")
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.statusCode}, message: ${response.text()}"
      }
      throw new RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to Artifactory. Errors: \n${errors.mkString("\n")}"
      )
    }
  }
}
