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

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact, pkg: String): Unit = {
    publishAll(fileMapping -> artifact -> pkg)
  }

  def publishAll(artifacts: ((Seq[(os.Path, String)], Artifact), String)*): Unit = {
    log.info("arts: " + artifacts)
    val mappings = for (((fileMapping0, artifact), pkg) <- artifacts) yield {
      val publishPath = Seq(
        artifact.group.replace(".", "/"),
        artifact.id,
        artifact.version
      ).mkString("/")
      val fileMapping = fileMapping0.map { case (file, name) => (file, publishPath + "/" + name) }

      pkg -> artifact -> fileMapping.map {
        case (file, name) => name -> os.read.bytes(file)
      }
    }

    val (snapshots, releases) = mappings.partition {
      case ((_, artifact), _) => artifact.isSnapshot
    }

    if (snapshots.nonEmpty)
      throw new RuntimeException(
        s"Bintray does not support snapshots:\n${snapshots.map(_._1)}"
      )

    publishToRepo(releases)
  }
  
  private def publishToRepo(releases: Seq[((String, Artifact), Seq[(String, Array[Byte])])]): Unit = {

    val uploadResults =
      for {
        ((pkg, artifact), payloads) <- releases
        response = api.createVersion(pkg, artifact.version)
        (fileName, data) <- payloads
        if response.is2xx || response.is3xx
      } yield ((pkg, artifact), response) -> api.upload(pkg, artifact.version, fileName, "", data)

    val groupedUploadResults =
      for {
        (((pkg, artifact), createVersionResponse), responses) <- uploadResults.groupBy(_._1).mapValues(_.map(_._2)).toSeq
      } yield pkg -> artifact -> (responses :+ createVersionResponse)

    if (release) {
      val publishResults =
        for {
          ((pkg, artifact), responses) <- groupedUploadResults
          if responses.forall(_.is2xx)
        } yield pkg -> artifact -> (responses :+ api.publish(pkg, artifact.version))

      reportPublishResults(publishResults)
    } else {
      reportPublishResults(groupedUploadResults)
    }
  }

  private def reportPublishResults(publishResults: Seq[((String, Artifact), Seq[requests.Response])]) = {
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
