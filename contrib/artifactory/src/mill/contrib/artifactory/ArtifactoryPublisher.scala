package mill.contrib.artifactory

import java.net.URI
import mill.api.Logger
import mill.javalib.publish.Artifact
import mill.util.CoursierConfig

class ArtifactoryPublisher(
    releaseUri: String,
    snapshotUri: String,
    credentials: String,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger
) {

  private def prepareCreds(credentials: String, repoUri: String): String = {
    if (credentials.isEmpty) {
      val hostname = URI(repoUri).getHost
      val coursierCreds = CoursierConfig.default().credentials.map(_.get).flatten
      coursierCreds.find(cred =>
        cred.host == hostname && cred.usernameOpt.isDefined && cred.passwordOpt.isDefined
      ) match {
        case Some(cred) => s"${cred.usernameOpt.get}:${cred.passwordOpt.get.value}"
        case None => throw RuntimeException(
            "Consider either using ARTIFACTORY_USERNAME/ARTIFACTORY_PASSWORD environment variables or passing `credentials` argument or setup credential files"
          )
      }
    } else {
      credentials
    }
  }

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
    val api = ArtifactoryHttpApi(
      credentials = prepareCreds(credentials, repoUri),
      readTimeout = readTimeout,
      connectTimeout = connectTimeout
    )
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
      throw RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to Artifactory. Errors: \n${errors.mkString("\n")}"
      )
    }
  }
}
