package mill.contrib.codeartifact

import mill._
import scalalib._
import define.ExternalModule

trait CodeartifactPublishModule extends PublishModule {
  def codeartifactUri: String

  def codeartifactSnapshotUri: String

  def publishCodeartifact(
      credentials: String,
      publish: Boolean = true,
      codeartifactUri: String = codeartifactUri,
      codeartifactSnapshotUri: String = codeartifactSnapshotUri,
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] =
    Task.Command {
      val PublishModule.PublishData(artifactInfo, artifacts) =
        publishArtifacts()

      new CodeartifactPublisher(
        codeartifactUri,
        codeartifactSnapshotUri,
        credentials,
        readTimeout,
        connectTimeout,
        Task.log
      ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo)
    }
}

object CodeartifactPublishModule extends ExternalModule {
  def publishAll(
      credentials: String,
      codeartifactUri: String,
      codeartifactSnapshotUri: String,
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ) =
    Task.Command {
      val artifacts = Task.sequence(publishArtifacts.value)().map {
        case data @ PublishModule.PublishData(_, _) => data.withConcretePath
      }
      new CodeartifactPublisher(
        codeartifactUri,
        codeartifactSnapshotUri,
        credentials,
        readTimeout,
        connectTimeout,
        Task.log
      ).publishAll(
        artifacts*
      )
    }

  lazy val millDiscover: mill.define.Discover =
    mill.define.Discover[this.type]
}
