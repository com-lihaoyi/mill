package mill.contrib.artifactory

import mill._
import mill.api.Result
import javalib._
import mill.contrib.artifactory.ArtifactoryPublishModule.checkArtifactoryCreds
import mill.api.{ExternalModule, Task}

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
  ): Command[Unit] = Task.Command {
    val (artifacts, artifactInfo) = publishArtifacts().withConcretePath
    ArtifactoryPublisher(
      artifactoryUri,
      artifactorySnapshotUri,
      checkArtifactoryCreds(credentials)(),
      readTimeout,
      connectTimeout,
      Task.log
    ).publish(artifacts, artifactInfo)
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
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): Command[Unit] = Task.Command {

    val artifacts = Task.sequence(publishArtifacts.value)().map {
      case data @ PublishModule.PublishData(_, _) => data.withConcretePath
    }
    ArtifactoryPublisher(
      artifactoryUri,
      artifactorySnapshotUri,
      checkArtifactoryCreds(credentials)(),
      readTimeout,
      connectTimeout,
      Task.log
    ).publishAll(
      artifacts*
    )
  }

  private def checkArtifactoryCreds(credentials: String): Task[String] = Task.Anon {
    if (credentials.isEmpty) {
      (for {
        username <- Task.env.get("ARTIFACTORY_USERNAME")
        password <- Task.env.get("ARTIFACTORY_PASSWORD")
      } yield {
        s"$username:$password"
      }).getOrElse("")
    } else {
      credentials
    }
  }

  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]
}
