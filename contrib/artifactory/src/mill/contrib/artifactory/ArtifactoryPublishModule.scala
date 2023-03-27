package mill.contrib.artifactory

import mill._
import mill.api.Result
import scalalib._
import publish.Artifact
import mill.contrib.artifactory.ArtifactoryPublishModule.checkArtifactoryCreds
import mill.define.{ExternalModule, Task}

trait ArtifactoryPublishModule extends PublishModule {
  def artifactoryUri: String

  def artifactorySnapshotUri: String

  /**
   * Publish all given artifacts to Artifactory.
   * Uses environment variables ARTIFACTORY_USERNAME and ARTIFACTORY_PASSWORD as
   * credentials.
   *
   * @param credentials Artifactory credentials in format username:password.
   *                    If specified, environment variables will be ignored.
   *                    <i>Note: consider using environment variables over this argument due
   *                    to security reasons.</i>
   */
  def publishArtifactory(
      credentials: String = "",
      artifactoryUri: String = artifactoryUri,
      artifactorySnapshotUri: String = artifactorySnapshotUri,
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new ArtifactoryPublisher(
      artifactoryUri,
      artifactorySnapshotUri,
      checkArtifactoryCreds(credentials)(),
      readTimeout,
      connectTimeout,
      T.log
    ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo)
  }
}

object ArtifactoryPublishModule extends ExternalModule {

  /**
   * Publish all given artifacts to Artifactory.
   * Uses environment variables ARTIFACTORY_USERNAME and ARTIFACTORY_PASSWORD as
   * credentials.
   *
   * @param credentials Artifactory credentials in format username:password.
   *                    If specified, environment variables will be ignored.
   *                    <i>Note: consider using environment variables over this argument due
   *                    to security reasons.</i>
   */
  def publishAll(
      credentials: String = "",
      artifactoryUri: String,
      artifactorySnapshotUri: String,
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ) = T.command {

    val x: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map {
      case PublishModule.PublishData(a, s) => (s.map { case (p, f) => (p.path, f) }, a)
    }
    new ArtifactoryPublisher(
      artifactoryUri,
      artifactorySnapshotUri,
      checkArtifactoryCreds(credentials)(),
      readTimeout,
      connectTimeout,
      T.log
    ).publishAll(
      x: _*
    )
  }

  private def checkArtifactoryCreds(credentials: String): Task[String] = T.task {
    if (credentials.isEmpty) {
      (for {
        username <- T.env.get("ARTIFACTORY_USERNAME")
        password <- T.env.get("ARTIFACTORY_PASSWORD")
      } yield {
        Result.Success(s"$username:$password")
      }).getOrElse(
        Result.Failure(
          "Consider using ARTIFACTORY_USERNAME/ARTIFACTORY_PASSWORD environment variables or passing `credentials` argument"
        )
      )
    } else {
      Result.Success(credentials)
    }
  }

  import mill.main.TokenReaders._

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
