package mill.contrib.bintray

import mill.api.Logger
import mill.scalalib.publish.Artifact

class BintrayPublisher(
    owner: String,
    repo: String,
    credentials: String,
    release: Boolean = true,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger
) {
  private val api = new BintrayHttpApi(
    owner,
    repo,
    credentials,
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    log
  )

  def publish(publishData: BintrayPublishData) = publishAll(publishData)
  def publishAll(publishData: BintrayPublishData*) = {
    val mappings =
      for {
        BintrayPublishData(meta, payload, pkg) <- publishData
      } yield {
        val publishPath = Seq(
          meta.group.replace(".", "/"),
          meta.id,
          meta.version
        ).mkString("/")
        val fileMapping = payload.map {
          case (file, name) => publishPath + "/" + name -> os.read.bytes(file.path)
        }

        (pkg, meta, fileMapping)
      }

    val (snapshots, releases) = mappings.partition(_._2.isSnapshot)

    if (snapshots.nonEmpty)
      throw new RuntimeException(
        s"Bintray does not support snapshots:\n${snapshots.map(_._2)}"
      )

    publishToRepo(releases)
  }

  type BintrayPackage = String
  type FileMapping = Seq[(String, Array[Byte])]

  private def publishToRepo(releases: Seq[(BintrayPackage, Artifact, FileMapping)]): Unit = {
    val uploadResults =
      for {
        (pkg, artifact, payloads) <- releases
        createVersion = api.createVersion(pkg, artifact.version)
        upload =
          for {
            (fileName, data) <- payloads
            if createVersion.is2xx || createVersion.is3xx
          } yield api.upload(pkg, artifact.version, fileName, "", data)
      } yield (pkg, artifact, upload :+ createVersion)

    if (release) {
      val publishResults =
        for {
          (pkg, artifact, responses) <- uploadResults
          if responses.forall(_.is2xx)
        } yield (pkg, artifact, (responses :+ api.publish(pkg, artifact.version)))

      reportPublishResults(publishResults)
    } else {
      reportPublishResults(uploadResults)
    }
  }

  private def reportPublishResults(publishResults: Seq[(
      BintrayPackage,
      Artifact,
      Seq[requests.Response]
  )]) = {
    val errors =
      for {
        (pkg, artifact, responses) <- publishResults
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
