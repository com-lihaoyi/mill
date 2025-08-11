package mill.contrib.artifactory

import mill.api.Logger
import mill.javalib.publish.Artifact

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

  def publish(fileMapping: Map[os.SubPath, os.Path], artifact: Artifact): Unit = {
    publishAll(fileMapping -> artifact)
  }

  def publishAll(artifacts: (Map[os.SubPath, os.Path], Artifact)*): Unit = {
    log.info("arts: " + artifacts)
    val mappings = for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath = os.SubPath(Seq(
        artifact.group.replace(".", "/"),
        artifact.id,
        artifact.version
      ).mkString("/"))
      val fileMapping = fileMapping0.map { case (name, contents) =>
        publishPath / name -> os.read.bytes(contents)
      }

      artifact -> fileMapping
    }

    val (snapshots, releases) = mappings.partition(_._1.isSnapshot)

    if (snapshots.nonEmpty)
      publishToRepo(snapshotUri, snapshots.iterator.flatMap(_._2).toMap, snapshots.map(_._1))
    if (releases.nonEmpty)
      publishToRepo(releaseUri, releases.iterator.flatMap(_._2).toMap, releases.map(_._1))
  }

  private def publishToRepo(
      repoUri: String,
      payloads: Map[os.SubPath, Array[Byte]],
      artifacts: Seq[Artifact]
  ): Unit = {
    val publishResults = payloads.iterator.map {
      case (fileName, data) =>
        log.info(s"Uploading $fileName")
        val resp = api.upload(s"$repoUri/$fileName", data)
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
