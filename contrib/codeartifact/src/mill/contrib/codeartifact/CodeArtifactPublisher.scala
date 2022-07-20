package mill.contrib.codeartifact

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

import mill.api.Logger
import mill.scalalib.publish.Artifact

class CodeartifactPublisher(
    releaseUri: String,
    snapshotUri: String,
    credentials: String,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger
) {
  private val api = new CodeartifactHttpApi(
    credentials,
    readTimeout = readTimeout,
    connectTimeout = connectTimeout
  )

  private def basePublishPath(artifact: Artifact) =
    Seq(
      artifact.group.replace(".", "/"),
      artifact.id
    ).mkString("/")

  private def versionPublishPath(artifact: Artifact) =
    s"${basePublishPath(artifact)}/${artifact.version}"

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact): Unit = {
    publishAll(fileMapping -> artifact)
  }

  private def currentTimeNumber() =
    DateTimeFormatter
      .ofPattern("yyyyMMddHHmmss")
      .withZone(ZoneId.systemDefault())
      .format(Instant.now())

  private def mavenMetadata(artifact: Artifact) =
    <metadata modelVersion="1.1.0">
      <groupId>{artifact.group}</groupId>
      <artifactId>{artifact.id}</artifactId>
      <versioning>
        <latest>{artifact.version}</latest>
        <release>{artifact.version}</release>
        <versions>
          <version>{artifact.version}</version>
        </versions>
        <lastUpdated>{currentTimeNumber()}</lastUpdated>
      </versioning>
    </metadata>

  def publishAll(artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    log.info("arts: " + artifacts)
    val mappings = for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath = versionPublishPath(artifact)
      val fileMapping = fileMapping0.map {
        case (file, name) => (file, publishPath + "/" + name)
      }

      artifact -> fileMapping.map {
        case (file, name) => name -> os.read.bytes(file)
      }
    }

    val (snapshots, releases) = mappings.partition(_._1.isSnapshot)

    if (snapshots.nonEmpty) {
      publishToRepo(snapshotUri, snapshots.flatMap(_._2), snapshots.map(_._1))

      val artifact = snapshots.head._1
      api.upload(
        s"$snapshotUri/${basePublishPath(artifact)}/maven-metadata.xml",
        mavenMetadata(artifact).buildString(true).toArray.map(_.toByte)
      )
    }
    if (releases.nonEmpty) {
      publishToRepo(releaseUri, releases.flatMap(_._2), releases.map(_._1))

      val artifact = releases.head._1
      api.upload(
        s"$releaseUri/${basePublishPath(artifact)}/maven-metadata.xml",
        mavenMetadata(artifact).buildString(true).toArray.map(_.toByte)
      )
    }
  }

  private def publishToRepo(
      repoUri: String,
      payloads: Seq[(String, Array[Byte])],
      artifacts: Seq[Artifact]
  ): Unit = {
    val publishResults = payloads.map {
      case (fileName, data) =>
        log.info(s"Uploading $fileName")
        val resp = api.upload(s"$repoUri/$fileName", data)
        resp
    }
    reportPublishResults(publishResults, artifacts)
  }

  private def reportPublishResults(
      publishResults: Seq[requests.Response],
      artifacts: Seq[Artifact]
  ) = {
    if (publishResults.forall(_.is2xx)) {
      log.info(
        s"Published ${artifacts.map(_.id).mkString(", ")} to AWS Codeartifact"
      )
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.statusCode}, message: ${response.text()}"
      }
      throw new RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to AWS Codeartifact. Errors: \n${errors
            .mkString("\n")}"
      )
    }
  }
}
