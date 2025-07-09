package mill.contrib.gitlab

import mill.api.Logger
import mill.javalib.publish.Artifact
import requests.Response

class GitlabPublisher(
    upload: GitlabUploader.Upload,
    repo: ProjectRepository,
    log: Logger
) {

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact): Unit =
    publishAll(fileMapping -> artifact)

  def publishAll(artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    log.info("Publishing artifacts: " + artifacts)

    val uploadData = for {
      (items, artifact) <- artifacts
      files = items.map { case (path, name) => name -> os.read.bytes(path) }
    } yield artifact -> files

    uploadData
      .map { case (artifact, data) =>
        publishToRepo(repo, artifact, data)
      }
      .foreach { case (artifact, result) =>
        reportPublishResults(artifact, result)
      }
  }

  private def publishToRepo(
      repo: ProjectRepository,
      artifact: Artifact,
      payloads: Seq[(String, Array[Byte])]
  ): (Artifact, Seq[Response]) = {
    val publishResults = payloads.map { case (fileName, data) =>
      log.info(s"Uploading $fileName")
      val uploadTarget = repo.uploadUrl(artifact)
      val resp = upload(s"$uploadTarget/$fileName", data)
      resp
    }
    artifact -> publishResults
  }

  private def reportPublishResults(
      artifact: Artifact,
      publishResults: Seq[requests.Response]
  ): Unit = {
    if (publishResults.forall(_.is2xx)) {
      log.info(s"Published $artifact to Gitlab")
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.statusCode}, message: ${response.text()}"
      }
      // Or just log? Fail later?
      throw new RuntimeException(
        s"Failed to publish $artifact to Gitlab. Errors: \n${errors.mkString("\n")}"
      )
    }
  }
}
