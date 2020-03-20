package mill.contrib.bintray

import mill.api.Logger
import mill.scalalib.publish.Artifact

class BintrayPublisher(owner: String,
                       repo: String,
                       credentials: String,
                       release: Boolean = true,
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

    val uploadResults =
      for {
        (artifact, payloads) <- releases
        response = api.createVersion(artifact.id, artifact.version)
        (fileName, data) <- payloads
        if response.is2xx || response.is3xx
      } yield (artifact, response) -> api.upload(artifact.id, artifact.version, fileName, "", data)

    val groupedUploadResults =
      for {
        ((artifact, createVersionResponse), responses) <- uploadResults.groupBy(_._1).mapValues(_.map(_._2)).toSeq
      } yield artifact -> (responses :+ createVersionResponse)

    if (release) {
      val publishResults =
        for {
          (artifact, responses) <- groupedUploadResults
          if responses.forall(_.is2xx)
        } yield artifact -> (responses :+ api.publish(artifact.id, artifact.version))

      reportPublishResults(publishResults)
    } else {
      reportPublishResults(groupedUploadResults)
    }
  }

  private def reportPublishResults(publishResults: Seq[(Artifact, Seq[requests.Response])]) = {
    val errors =
      for {
        (artifact, responses) <- publishResults
        response <- responses
        if !response.is2xx
      } yield artifact -> s"Code: ${response.statusCode}, message: ${response.text()}"

    val errorsByArtifact = errors.groupBy(_._1).mapValues(_.map(_._2)).toSeq

    if (errorsByArtifact.nonEmpty) {
      throw new RuntimeException(
        s"Failed to publish ${errorsByArtifact.map(_._1).mkString(", ")} to Bintray. Errors: \n${errorsByArtifact.flatMap(_._2).mkString("\n")}"
      )
    }
  }
}
