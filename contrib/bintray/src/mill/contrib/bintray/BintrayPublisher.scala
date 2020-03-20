package mill.contrib.bintray

import mill.api.Logger
import mill.scalalib.publish.Artifact

class BintrayPublisher(owner: String,
                       repo: String,
                       credentials: String,
                       readTimeout: Int,
                       connectTimeout: Int,
                       log: Logger) {
  private val api = new BintrayHttpApi(
    owner,
    repo,
    credentials,
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    log
  )

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact): Unit = {
    publishAll(fileMapping -> artifact)
  }

  def publishAll(artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    log.info("arts: " + artifacts)
    val mappings = for ((fileMapping0, artifact) <- artifacts) yield {
      val publishPath = Seq(
        artifact.group.replace(".", "/"),
        artifact.id,
        artifact.version
      ).mkString("/")
      val fileMapping = fileMapping0.map { case (file, name) => (file, publishPath + "/" + name) }

      artifact -> fileMapping.map {
        case (file, name) => name -> os.read.bytes(file)
      }
    }

    val (snapshots, releases) = mappings.partition(_._1.isSnapshot)

    if (snapshots.nonEmpty)
      throw new RuntimeException(
        s"Bintray does not support snapshots:\n${snapshots.map(_._1)}"
      )

    publishToRepo(releases)
  }
  
  private def publishToRepo(releases: Seq[(Artifact, Seq[(String, Array[Byte])])]): Unit = {

    val publishResults =
      for {
        (artifact, payloads) <- releases
        createVersion = api.createVersion(artifact.id, artifact.version)
        (fileName, data) <- payloads
        if createVersion.is2xx || createVersion.is3xx
      } yield api.upload(artifact.id, artifact.version, fileName, "", data)

    reportPublishResults(publishResults, releases.map(_._1))
  }

  private def reportPublishResults(publishResults: Seq[requests.Response],
                                   artifacts: Seq[Artifact]) = {
    if (publishResults.forall(_.is2xx)) {
      log.info(s"Published ${artifacts.map(_.id).mkString(", ")} to Bintray")
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.statusCode}, message: ${response.text()}"
      }
      throw new RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to Bintray. Errors: \n${errors.mkString("\n")}"
      )
    }
  }
}
